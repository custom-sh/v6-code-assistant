package com.ims.code.helper.ai.agent

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.ims.code.helper.rag.SensitiveContentRedactor
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

/**
 * 为轻量 Agent 提供 IDEA 索引和编辑器只读能力
 * @author shenwl
 * @date 2026/08/25
 */
internal class ProjectAgentIdeTools(
    private val project: Project,
    projectRoot: Path
) {
    private val root = projectRoot.toAbsolutePath().normalize()
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun execute(tool: String, arguments: JsonObject): ProjectAgentTools.Result = try {
        when (tool) {
            "get_editor_context" -> getEditorContext()
            "find_symbol" -> findSymbol(arguments)
            "find_references" -> findReferences(arguments)
            "find_implementations" -> findImplementations(arguments)
            "get_call_hierarchy" -> getCallHierarchy(arguments)
            "get_current_diagnostics" -> getCurrentDiagnostics()
            "get_module_dependencies" -> getModuleDependencies(arguments)
            "analyze_impact" -> analyzeImpact(arguments)
            else -> error("Tool is not allowed: $tool")
        }
    } catch (_: IndexNotReadyException) {
        errorResult("IDE indexes are still being built; use list_files, search_text, or read_file as a fallback")
    } catch (error: Exception) {
        errorResult(error.message ?: error.javaClass.simpleName)
    }

    private fun getEditorContext(): ProjectAgentTools.Result {
        val snapshot = selectedEditorSnapshot()
            ?: return errorResult("No project file is open in the active editor")
        val context = readAction {
            val file = PsiDocumentManager.getInstance(project).getPsiFile(snapshot.document)
            val target = file?.findReferenceAt(snapshot.caretOffset)?.resolve()
                ?: file?.findElementAt(snapshot.caretOffset)?.let {
                    PsiTreeUtil.getParentOfType(it, PsiNamedElement::class.java, false)
                }
            val startLine = (snapshot.caretLine - EDITOR_CONTEXT_RADIUS).coerceAtLeast(0)
            val endLine = (snapshot.caretLine + EDITOR_CONTEXT_RADIUS)
                .coerceAtMost(snapshot.document.lineCount - 1)
            val startOffset = snapshot.document.getLineStartOffset(startLine)
            val endOffset = snapshot.document.getLineEndOffset(endLine)
            val excerpt = snapshot.document.getText(TextRange(startOffset, endOffset))
                .lineSequence()
                .mapIndexed { index, line -> "${startLine + index + 1}: $line" }
                .joinToString("\n")
            buildString {
                append("Active file: ").append(snapshot.path).append('\n')
                append("Language: ").append(file?.language?.displayName ?: "unknown").append('\n')
                append("Caret: line ").append(snapshot.caretLine + 1)
                    .append(", column ").append(snapshot.caretColumn + 1).append('\n')
                if (snapshot.selection.isNotBlank()) {
                    append("Selected text:\n").append(snapshot.selection.take(MAX_SELECTION_CHARS)).append('\n')
                }
                if (target != null) append("Symbol at caret: ").append(symbolDescription(target)).append('\n')
                if (snapshot.openFiles.isNotEmpty()) {
                    append("Open project files: ").append(snapshot.openFiles.joinToString(", ")).append('\n')
                }
                append("Nearby code:\n").append(excerpt)
            }
        }
        return success(
            SensitiveContentRedactor.redact(context),
            listOf(ProjectAgentTools.Source(snapshot.path, snapshot.caretLine + 1))
        )
    }

    private fun findSymbol(arguments: JsonObject): ProjectAgentTools.Result {
        requireIndexesReady()
        val name = requiredSymbolName(arguments)
        val symbols = readAction { findSymbols(name) }
        if (symbols.isEmpty()) return success("No project symbol found with exact name: $name")
        return locationsResult(symbols, "Definitions for $name")
    }

    private fun findReferences(arguments: JsonObject): ProjectAgentTools.Result {
        requireIndexesReady()
        val target = readAction { resolveSymbol(arguments) }
            ?: return success("No matching project symbol was found")
        val references = readAction {
            val found = mutableListOf<PsiElement>()
            ReferencesSearch.search(target, projectScope()).forEach { reference ->
                found += reference.element
                found.size < MAX_RESULTS
            }
            found
        }
        if (references.isEmpty()) return success("No project references found for ${symbolDescription(target)}")
        return locationsResult(references, "References to ${symbolDescription(target)}")
    }

    private fun findImplementations(arguments: JsonObject): ProjectAgentTools.Result {
        requireIndexesReady()
        val target = readAction { resolveSymbol(arguments) }
            ?: return success("No matching project symbol was found")
        val implementations = readAction {
            when (target) {
                is PsiClass -> ClassInheritorsSearch.search(target, projectScope(), true)
                    .findAll().take(MAX_RESULTS)
                is PsiMethod -> OverridingMethodsSearch.search(target, projectScope(), true)
                    .findAll().take(MAX_RESULTS)
                else -> emptyList()
            }
        }
        if (implementations.isEmpty()) {
            return success("No project implementations found for ${symbolDescription(target)}")
        }
        return locationsResult(implementations, "Implementations of ${symbolDescription(target)}")
    }

    private fun getCallHierarchy(arguments: JsonObject): ProjectAgentTools.Result {
        requireIndexesReady()
        val target = readAction { resolveSymbol(arguments) as? PsiMethod }
            ?: return success("No matching project method was found")
        val direction = arguments.string("direction")?.lowercase() ?: "both"
        require(direction in setOf("callers", "callees", "both")) {
            "direction must be callers, callees, or both"
        }
        val (content, sources) = readAction {
            val relatedElements = mutableListOf<PsiElement>(target)
            val text = buildString {
                append("Method: ").append(symbolDescription(target))
                if (direction != "callees") {
                    val callers = mutableListOf<PsiElement>()
                    MethodReferencesSearch.search(target, projectScope(), true).forEach { reference ->
                        callers += reference.element
                        callers.size < MAX_RESULTS
                    }
                    relatedElements += callers
                    append("\nCallers:\n")
                    append(locationLines(callers).ifEmpty { listOf("(none found)") }.joinToString("\n"))
                }
                if (direction != "callers") {
                    val callees = PsiTreeUtil.findChildrenOfType(target, PsiCallExpression::class.java)
                        .asSequence()
                        .mapNotNull {
                            when (it) {
                                is PsiMethodCallExpression -> it.resolveMethod()
                                is PsiNewExpression -> it.resolveMethod()
                                else -> null
                            }
                        }
                        .distinctBy(::symbolKey)
                        .take(MAX_RESULTS)
                        .toList()
                    relatedElements += callees
                    append("\nCallees:\n")
                    append(locationLines(callees).ifEmpty { listOf("(none found)") }.joinToString("\n"))
                }
            }
            text to relatedElements.mapNotNull(::sourceOf)
                .distinctBy { it.path to it.line }
                .take(MAX_RESULTS)
        }
        return success(content, sources)
    }

    private fun getCurrentDiagnostics(): ProjectAgentTools.Result {
        val snapshot = selectedEditorSnapshot()
            ?: return errorResult("No project file is open in the active editor")
        val diagnostics = readAction {
            val result = mutableListOf<String>()
            val file = PsiDocumentManager.getInstance(project).getPsiFile(snapshot.document)
            if (file != null) {
                PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
                    .take(MAX_DIAGNOSTICS)
                    .forEach { error ->
                        result += diagnosticLine(snapshot.document, error.textOffset, "ERROR", error.errorDescription)
                    }
            }
            DaemonCodeAnalyzerEx.processHighlights(
                snapshot.document,
                project,
                HighlightSeverity.INFORMATION,
                0,
                snapshot.document.textLength
            ) { info ->
                val description = info.description
                if (!description.isNullOrBlank()) {
                    result += diagnosticLine(snapshot.document, info.startOffset, info.severity.toString(), description)
                }
                result.size < MAX_DIAGNOSTICS
            }
            result.distinct().take(MAX_DIAGNOSTICS)
        }
        val content = if (diagnostics.isEmpty()) {
            "No current IDEA diagnostics are available for ${snapshot.path}. The editor analysis may still be running."
        } else {
            "Current IDEA diagnostics for ${snapshot.path}:\n${diagnostics.joinToString("\n")}"
        }
        return success(content, listOf(ProjectAgentTools.Source(snapshot.path, 1)))
    }

    private fun getModuleDependencies(arguments: JsonObject): ProjectAgentTools.Result {
        val requestedModule = arguments.string("module")?.trim().orEmpty()
        val content = readAction {
            val modules = ModuleManager.getInstance(project).modules
                .filter { requestedModule.isBlank() || it.name == requestedModule }
            require(modules.isNotEmpty()) {
                if (requestedModule.isBlank()) "No IDEA modules found" else "IDEA module not found: $requestedModule"
            }
            modules.joinToString("\n\n") { module ->
                val moduleDependencies = mutableListOf<String>()
                val libraries = mutableListOf<String>()
                ModuleRootManager.getInstance(module).orderEntries.forEach { entry ->
                    when (entry) {
                        is ModuleOrderEntry -> moduleDependencies += entry.moduleName
                        is LibraryOrderEntry -> entry.libraryName?.let(libraries::add)
                    }
                }
                buildString {
                    append("Module: ").append(module.name)
                    append("\nModule dependencies: ")
                    append(moduleDependencies.distinct().sorted().ifEmpty { listOf("(none)") }.joinToString(", "))
                    append("\nLibraries: ")
                    append(libraries.distinct().sorted().take(MAX_LIBRARIES).ifEmpty { listOf("(none)") }.joinToString(", "))
                }
            }
        }
        return success(content)
    }

    private fun analyzeImpact(arguments: JsonObject): ProjectAgentTools.Result {
        requireIndexesReady()
        val target = readAction { resolveSymbol(arguments) }
            ?: return success("No matching project symbol was found")
        val (content, sources) = readAction {
            val references = ReferencesSearch.search(target, projectScope()).findAll()
                .map { it.element }
                .take(MAX_RESULTS)
            val implementations = when (target) {
                is PsiClass -> ClassInheritorsSearch.search(target, projectScope(), true)
                    .findAll().take(MAX_RESULTS)
                is PsiMethod -> OverridingMethodsSearch.search(target, projectScope(), true)
                    .findAll().take(MAX_RESULTS)
                else -> emptyList()
            }
            val targetSource = sourceOf(target)
            val referenceLines = locationLines(references)
            val implementationLines = locationLines(implementations)
            val allElements = listOfNotNull(target) + references + implementations
            val modules = allElements.mapNotNull { element ->
                val file = element.navigationElement.containingFile?.virtualFile ?: return@mapNotNull null
                ProjectFileIndex.getInstance(project).getModuleForFile(file)?.name
            }.distinct().sorted()
            val text = buildString {
                append("Target:\n")
                append(targetSource?.let { "${it.path}:${it.line}: ${symbolDescription(target)}" }
                    ?: symbolDescription(target))
                append("\nReferences:\n")
                append(referenceLines.ifEmpty { listOf("(none found)") }.joinToString("\n"))
                append("\nImplementations:\n")
                append(implementationLines.ifEmpty { listOf("(none found)") }.joinToString("\n"))
                append("\nAffected modules:\n")
                append(modules.ifEmpty { listOf("(none found)") }.joinToString(", "))
            }
            val evidenceSources = listOfNotNull(targetSource) + references.mapNotNull(::sourceOf) +
                implementations.mapNotNull(::sourceOf)
            text to evidenceSources.distinctBy { it.path to it.line }.take(MAX_RESULTS)
        }
        return success(content, sources)
    }

    private fun selectedEditorSnapshot(): EditorSnapshot? {
        var snapshot: EditorSnapshot? = null
        val action = {
            val manager = FileEditorManager.getInstance(project)
            val editor = manager.selectedTextEditor
            if (editor != null) snapshot = snapshot(editor, manager)
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeAndWait(action)
        return snapshot
    }

    private fun snapshot(editor: Editor, manager: FileEditorManager): EditorSnapshot? {
        val path = editor.virtualFile?.let(::relativePath) ?: return null
        if (isBlockedPath(path)) return null
        val caret = editor.caretModel.logicalPosition
        return EditorSnapshot(
            document = editor.document,
            path = path,
            caretOffset = editor.caretModel.offset,
            caretLine = caret.line,
            caretColumn = caret.column,
            selection = editor.selectionModel.selectedText.orEmpty(),
            openFiles = manager.openFiles.mapNotNull(::relativePath).filterNot(::isBlockedPath).take(MAX_OPEN_FILES)
        )
    }

    private fun resolveSymbol(arguments: JsonObject): PsiNamedElement? {
        val name = requiredSymbolName(arguments)
        val path = arguments.string("path")?.trim().orEmpty()
        val line = arguments.int("line", 0)
        val candidates = findSymbols(name)
        return candidates.firstOrNull { candidate ->
            val source = sourceOf(candidate)
            (path.isBlank() || source?.path == path.replace('\\', '/')) &&
                (line <= 0 || source?.line == line)
        } ?: candidates.firstOrNull()
    }

    private fun findSymbols(name: String): List<PsiNamedElement> {
        val cache = PsiShortNamesCache.getInstance(project)
        val scope = projectScope()
        return buildList {
            addAll(cache.getClassesByName(name, scope))
            addAll(cache.getMethodsByName(name, scope))
            addAll(cache.getFieldsByName(name, scope))
        }.distinctBy(::symbolKey).take(MAX_RESULTS)
    }

    private fun locationsResult(elements: Collection<PsiElement>, heading: String): ProjectAgentTools.Result {
        val lines = readAction { locationLines(elements) }
        val sources = readAction {
            elements.mapNotNull(::sourceOf).distinctBy { it.path to it.line }.take(MAX_RESULTS)
        }
        return success("$heading:\n${lines.joinToString("\n")}", sources)
    }

    private fun locationLines(elements: Collection<PsiElement>): List<String> = elements
        .mapNotNull { element ->
            val source = sourceOf(element) ?: return@mapNotNull null
            "${source.path}:${source.line}: ${symbolDescription(element)}"
        }
        .distinct()
        .take(MAX_RESULTS)

    private fun sourceOf(element: PsiElement): ProjectAgentTools.Source? {
        val navigation = element.navigationElement
        val file = navigation.containingFile ?: return null
        val path = file.virtualFile?.let(::relativePath) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val offset = navigation.textOffset.coerceIn(0, document.textLength)
        return ProjectAgentTools.Source(path, document.getLineNumber(offset) + 1)
    }

    private fun relativePath(file: VirtualFile): String? {
        val path = runCatching { Path.of(file.path).toAbsolutePath().normalize() }.getOrNull()
        if (path != null && path.startsWith(root)) {
            return root.relativize(path).toString().replace('\\', '/')
        }
        ProjectFileIndex.getInstance(project).getContentRootForFile(file)?.let { contentRoot ->
            VfsUtilCore.getRelativePath(file, contentRoot, '/')?.let { return it }
        }
        return null
    }

    private fun symbolDescription(element: PsiElement): String = when (element) {
        is PsiClass -> "class ${element.qualifiedName ?: element.name.orEmpty()}"
        is PsiMethod -> buildString {
            append("method ")
            element.containingClass?.qualifiedName?.let { append(it).append('.') }
            append(element.name).append('(')
            append(element.parameterList.parameters.joinToString(", ") { it.type.presentableText })
            append(')')
        }
        is PsiField -> "field ${element.containingClass?.qualifiedName?.let { "$it." }.orEmpty()}${element.name}"
        is PsiNamedElement -> element.name?.let { "symbol $it" } ?: element.javaClass.simpleName
        else -> element.javaClass.simpleName
    }

    private fun symbolKey(element: PsiElement): String {
        val source = sourceOf(element)
        return "${source?.path}:${source?.line}:${symbolDescription(element)}"
    }

    private fun diagnosticLine(document: Document, offset: Int, severity: String, description: String): String {
        val safeOffset = offset.coerceIn(0, document.textLength)
        return "line ${document.getLineNumber(safeOffset) + 1}: [$severity] $description"
    }

    private fun requiredSymbolName(arguments: JsonObject): String {
        val name = arguments.string("name")?.trim().orEmpty()
        require(name.length in 1..MAX_SYMBOL_NAME_CHARS) {
            "name must contain 1-$MAX_SYMBOL_NAME_CHARS characters"
        }
        return name
    }

    private fun requireIndexesReady() {
        if (DumbService.isDumb(project)) throw IndexNotReadyException.create()
    }

    private fun projectScope(): GlobalSearchScope = GlobalSearchScope.projectScope(project)

    private fun success(
        content: String,
        sources: List<ProjectAgentTools.Source> = emptyList()
    ): ProjectAgentTools.Result {
        val limited = content.take(MAX_RESULT_CHARS)
        val payload = gson.toJson(
            linkedMapOf(
                "ok" to true,
                "content" to limited,
                "truncated" to (content.length > limited.length)
            )
        )
        return ProjectAgentTools.Result(payload, sources)
    }

    private fun errorResult(message: String): ProjectAgentTools.Result = ProjectAgentTools.Result(
        gson.toJson(mapOf("ok" to false, "error" to message.take(MAX_ERROR_CHARS)))
    )

    private fun <T> readAction(action: () -> T): T =
        ApplicationManager.getApplication().runReadAction<T>(action)

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.int(name: String, default: Int): Int =
        get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.runCatching { asInt }?.getOrNull() ?: default

    private fun isBlockedPath(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return name == "local.properties" || name == ".env" || name.startsWith(".env.") ||
            name == "id_rsa" || BLOCKED_EXTENSIONS.any(name::endsWith)
    }

    private data class EditorSnapshot(
        val document: Document,
        val path: String,
        val caretOffset: Int,
        val caretLine: Int,
        val caretColumn: Int,
        val selection: String,
        val openFiles: List<String>
    )

    companion object {
        private const val MAX_RESULTS = 40
        private const val MAX_DIAGNOSTICS = 40
        private const val MAX_LIBRARIES = 80
        private const val MAX_OPEN_FILES = 12
        private const val MAX_SYMBOL_NAME_CHARS = 160
        private const val MAX_SELECTION_CHARS = 4_000
        private const val MAX_RESULT_CHARS = 10_000
        private const val MAX_ERROR_CHARS = 500
        private const val EDITOR_CONTEXT_RADIUS = 35
        private val BLOCKED_EXTENSIONS = setOf(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore")
    }
}
