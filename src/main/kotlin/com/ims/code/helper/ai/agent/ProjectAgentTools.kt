package com.ims.code.helper.ai.agent

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ims.code.helper.rag.SensitiveContentRedactor
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 项目分析 Agent 的受限只读工具集
 * @author shenwl
 * @date 2026/08/25
 */
internal class ProjectAgentTools(projectRoot: Path) {
    private val root = projectRoot.toRealPath()
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    data class Source(val path: String, val line: Int = 1)

    data class Result(val payload: String, val sources: List<Source> = emptyList())

    fun inspectProjectOverview(): Result {
        val tree = listFiles(JsonObject().apply {
            addProperty("path", "")
            addProperty("depth", MAX_LIST_DEPTH)
        })
        val overviewFiles = findOverviewFiles()
        val excerpts = overviewFiles.map { file ->
            val relative = relativePath(file)
            val result = readFile(JsonObject().apply {
                addProperty("path", relative)
                addProperty("startLine", 1)
                addProperty("endLine", BOOTSTRAP_READ_LINES)
            })
            relative to result
        }
        val content = buildString {
            append("Project tree snapshot:\n")
            append(resultContent(tree).take(MAX_BOOTSTRAP_TREE_CHARS))
            excerpts.forEach { (path, result) ->
                append("\n\nOverview file ").append(path).append(":\n")
                append(resultContent(result).take(MAX_BOOTSTRAP_FILE_CHARS))
            }
        }
        return success(
            content = content,
            truncated = false,
            sources = excerpts.flatMap { it.second.sources },
            maxChars = MAX_BOOTSTRAP_RESULT_CHARS
        )
    }

    fun getProjectContext(): Result {
        val fingerprint = projectContextFingerprint()
        return ProjectAgentContextCache.get(root, fingerprint) { inspectProjectOverview() }
    }

    fun execute(tool: String, arguments: JsonObject): Result = runCatching {
        when (tool) {
            "list_files" -> listFiles(arguments)
            "search_text" -> searchText(arguments)
            "read_file" -> readFile(arguments)
            "get_project_context" -> getProjectContext()
            else -> error("Tool is not allowed: $tool")
        }
    }.getOrElse { errorResult(it.message ?: it.javaClass.simpleName) }

    private fun listFiles(arguments: JsonObject): Result {
        val directory = resolveExisting(arguments.string("path").orEmpty())
        require(Files.isDirectory(directory)) { "Path is not a directory" }
        val depth = arguments.int("depth", 2).coerceIn(1, MAX_LIST_DEPTH)
        val entries = mutableListOf<String>()
        var scanned = 0
        var truncated = false
        Files.walkFileTree(directory, emptySet(), depth, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != directory && isIgnored(dir)) return FileVisitResult.SKIP_SUBTREE
                if (dir != directory) entries += "D ${relativePath(dir)}/"
                scanned++
                if (scanned >= MAX_SCANNED_ENTRIES) {
                    truncated = true
                    return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                scanned++
                if (!Files.isSymbolicLink(file) && !isIgnored(file)) {
                    val relative = relativePath(file)
                    entries += if (attrs.isDirectory) "D $relative/" else "F $relative"
                }
                if (scanned >= MAX_SCANNED_ENTRIES) {
                    truncated = true
                    return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }
        })
        val sorted = entries.sorted()
        val selected = sorted.take(MAX_LIST_ENTRIES)
        return success(
            selected.joinToString("\n").ifBlank { "(empty directory)" },
            truncated = truncated || sorted.size > selected.size
        )
    }

    private fun searchText(arguments: JsonObject): Result {
        val query = arguments.string("query")?.trim().orEmpty()
        require(query.length in 2..MAX_QUERY_CHARS) { "query must contain 2-$MAX_QUERY_CHARS characters" }
        val directory = resolveExisting(arguments.string("path").orEmpty())
        require(Files.isDirectory(directory)) { "Path is not a directory" }
        val matches = mutableListOf<String>()
        val sources = mutableListOf<Source>()
        var scannedFiles = 0
        var visitedEntries = 0
        var truncated = false
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != directory && isIgnored(dir)) return FileVisitResult.SKIP_SUBTREE
                visitedEntries++
                return limitSearchIfNeeded()
            }

            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                visitedEntries++
                if (!isReadableTextFile(path)) return limitSearchIfNeeded()
                scannedFiles++
                runCatching {
                    Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                        var lineNumber = 0
                        while (matches.size < MAX_SEARCH_MATCHES) {
                            val line = reader.readLine() ?: break
                            lineNumber++
                            if (!line.contains(query, ignoreCase = true)) continue
                            val relative = relativePath(path)
                            matches += "$relative:$lineNumber: ${line.trim().take(MAX_MATCH_LINE_CHARS)}"
                            sources += Source(relative, lineNumber)
                        }
                    }
                }

                return limitSearchIfNeeded()
            }

            private fun limitSearchIfNeeded(): FileVisitResult {
                if (visitedEntries >= MAX_SEARCH_ENTRIES ||
                    scannedFiles >= MAX_SEARCH_FILES ||
                    matches.size >= MAX_SEARCH_MATCHES
                ) {
                    truncated = true
                    return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }
        })
        val content = if (matches.isEmpty()) "No matches found for: $query" else matches.joinToString("\n")
        return success(
            SensitiveContentRedactor.redact(content),
            truncated = truncated,
            sources = sources.distinctBy { it.path to it.line }
        )
    }

    private fun readFile(arguments: JsonObject): Result {
        val rawPath = arguments.string("path")?.trim().orEmpty()
        require(rawPath.isNotEmpty()) { "path is required" }
        val file = resolveExisting(rawPath)
        require(isReadableTextFile(file)) { "File is not a supported readable text file" }
        val startLine = arguments.int("startLine", 1).coerceAtLeast(1)
        val requestedEnd = arguments.int("endLine", startLine + DEFAULT_READ_LINES - 1)
        val endLine = requestedEnd.coerceAtLeast(startLine).coerceAtMost(startLine + MAX_READ_LINES - 1)
        val output = StringBuilder()
        var lineNumber = 0
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber++
                if (lineNumber < startLine) continue
                if (lineNumber > endLine) break
                output.append(lineNumber).append(": ").append(line).append('\n')
            }
        }
        val relative = relativePath(file)
        val content = output.toString().trimEnd().ifBlank {
            "No lines available in requested range $startLine-$endLine"
        }
        return success(
            SensitiveContentRedactor.redact(content),
            truncated = requestedEnd > endLine,
            sources = listOf(Source(relative, startLine))
        )
    }

    private fun resolveExisting(rawPath: String): Path {
        val candidate = root.resolve(rawPath.ifBlank { "." }).normalize()
        require(candidate.startsWith(root)) { "Path must stay inside the project root" }
        val realPath = candidate.toRealPath()
        require(realPath.startsWith(root)) { "Path must stay inside the project root" }
        return realPath
    }

    private fun isReadableTextFile(path: Path): Boolean {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path) || isIgnored(path) || isBlockedFile(path)) return false
        if (runCatching { Files.size(path) }.getOrDefault(Long.MAX_VALUE) > MAX_FILE_BYTES) return false
        val name = path.fileName.toString()
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in TEXT_EXTENSIONS || name in TEXT_FILE_NAMES
    }

    private fun isIgnored(path: Path): Boolean {
        if (!path.normalize().startsWith(root)) return true
        return root.relativize(path.normalize()).any { it.toString().lowercase() in IGNORED_DIRECTORIES }
    }

    private fun isBlockedFile(path: Path): Boolean {
        val name = path.fileName.toString().lowercase()
        return name == "local.properties" || name == ".env" || name.startsWith(".env.") ||
            name == "id_rsa" || BLOCKED_EXTENSIONS.any(name::endsWith)
    }

    private fun relativePath(path: Path): String =
        root.relativize(path).toString().replace('\\', '/')

    private fun findOverviewFiles(): List<Path> {
        val candidates = mutableListOf<Pair<Int, Path>>()
        var visitedEntries = 0
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && isIgnored(dir)) return FileVisitResult.SKIP_SUBTREE
                visitedEntries++
                return if (visitedEntries >= MAX_BOOTSTRAP_ENTRIES) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                visitedEntries++
                if (isReadableTextFile(file)) {
                    overviewPriority(relativePath(file))?.let { candidates += it to file }
                }
                return if (visitedEntries >= MAX_BOOTSTRAP_ENTRIES) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
        })
        return candidates
            .sortedWith(compareBy<Pair<Int, Path>>({ it.first }, { relativePath(it.second) }))
            .map { it.second }
            .distinct()
            .take(MAX_BOOTSTRAP_FILES)
    }

    private fun projectContextFingerprint(): String {
        val rootStamp = runCatching {
            "${Files.getLastModifiedTime(root).toMillis()}:${Files.size(root)}"
        }.getOrDefault("root-unavailable")
        val overviewStamp = findOverviewFiles().joinToString("|") { file ->
            val metadata = runCatching {
                "${relativePath(file)}:${Files.getLastModifiedTime(file).toMillis()}:${Files.size(file)}"
            }.getOrDefault("${relativePath(file)}:unavailable")
            metadata
        }
        return "$rootStamp|$overviewStamp"
    }

    private fun overviewPriority(relativePath: String): Int? {
        val normalized = relativePath.lowercase()
        val name = normalized.substringAfterLast('/')
        val depth = normalized.count { it == '/' }
        val extension = name.substringAfterLast('.', "")
        return when {
            name == "readme" || name.startsWith("readme.") -> depth
            normalized.endsWith("meta-inf/plugin.xml") -> 10 + depth
            depth == 0 && name in BUILD_MANIFEST_NAMES -> 20
            name == "pom.xml" -> 30 + depth
            normalized.startsWith("src/") && extension in SOURCE_EXTENSIONS -> 100 + depth
            else -> null
        }
    }

    private fun resultContent(result: Result): String = runCatching {
        JsonParser.parseString(result.payload).asJsonObject.get("content").asString
    }.getOrDefault(result.payload)

    private fun success(
        content: String,
        truncated: Boolean,
        sources: List<Source> = emptyList(),
        maxChars: Int = MAX_TOOL_RESULT_CHARS
    ): Result {
        val limited = content.take(maxChars)
        val payload = gson.toJson(
            linkedMapOf(
                "ok" to true,
                "content" to limited,
                "truncated" to (truncated || content.length > limited.length)
            )
        )
        return Result(payload, sources)
    }

    private fun errorResult(message: String): Result = Result(
        gson.toJson(mapOf("ok" to false, "error" to message.take(MAX_ERROR_CHARS)))
    )

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.int(name: String, default: Int): Int =
        get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.runCatching { asInt }?.getOrNull() ?: default

    companion object {
        private const val MAX_LIST_DEPTH = 3
        private const val MAX_LIST_ENTRIES = 300
        private const val MAX_SCANNED_ENTRIES = 2_000
        private const val MAX_SEARCH_ENTRIES = 20_000
        private const val MAX_SEARCH_FILES = 4_000
        private const val MAX_SEARCH_MATCHES = 60
        private const val MAX_QUERY_CHARS = 120
        private const val MAX_MATCH_LINE_CHARS = 300
        private const val DEFAULT_READ_LINES = 160
        private const val MAX_READ_LINES = 240
        private const val MAX_FILE_BYTES = 1_048_576L
        private const val MAX_TOOL_RESULT_CHARS = 6_000
        private const val MAX_ERROR_CHARS = 500
        private const val MAX_BOOTSTRAP_ENTRIES = 5_000
        private const val MAX_BOOTSTRAP_FILES = 2
        private const val BOOTSTRAP_READ_LINES = 160
        private const val MAX_BOOTSTRAP_TREE_CHARS = 5_000
        private const val MAX_BOOTSTRAP_FILE_CHARS = 5_000
        private const val MAX_BOOTSTRAP_RESULT_CHARS = 15_000

        private val IGNORED_DIRECTORIES = setOf(
            ".git", ".svn", ".hg", ".idea", ".gradle", "node_modules", "build", "out", "target", "dist",
            ".next", "coverage"
        )
        private val BLOCKED_EXTENSIONS = setOf(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore")
        private val TEXT_EXTENSIONS = setOf(
            "kt", "kts", "java", "groovy", "xml", "json", "yaml", "yml", "toml", "ini", "conf",
            "properties", "gradle", "md", "txt", "sql", "js", "jsx", "ts", "tsx", "vue", "html",
            "css", "scss", "less", "py", "go", "rs", "cs", "c", "cc", "cpp", "h", "hpp", "sh",
            "ps1", "bat", "cmd"
        )
        private val TEXT_FILE_NAMES = setOf(
            "gradlew", "mvnw", "Dockerfile", "Makefile", ".gitignore", ".dockerignore", ".editorconfig"
        )
        private val BUILD_MANIFEST_NAMES = setOf(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "package.json", "pyproject.toml", "cargo.toml", "go.mod"
        )
        private val SOURCE_EXTENSIONS = setOf(
            "kt", "java", "groovy", "scala", "js", "jsx", "ts", "tsx", "vue", "py", "go", "rs", "cs",
            "c", "cc", "cpp", "h", "hpp"
        )
    }
}
