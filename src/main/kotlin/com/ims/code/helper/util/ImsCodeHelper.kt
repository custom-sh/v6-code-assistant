package com.ims.code.helper.util

import com.ims.code.helper.config.ImsProjectSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.net.URL
import java.net.URLClassLoader

/**
 * 插件公共工具类
 * @author shenwl
 * @date 2026/07/12
 */
object ImsCodeHelper {

    private val log = Logger.getInstance(ImsCodeHelper::class.java)

    /**
     * 将文本写入文件并通知 IDE VFS 刷新，使已打开的编辑器立即看到变更。
     * 用异步刷新避免在 EDT 上被 VFS 锁阻塞（此方法从多处 UI/Action 调用）。
     */
    private fun writeTextAndRefresh(file: File, text: String) {
        file.writeText(text)
        if (ApplicationManager.getApplication() != null) {
            VfsUtil.findFileByIoFile(file, true)?.refresh(true, true)
        }
    }

    // Module directory names for auto-detection (also referenced by ImsProjectSettingsPanel)
    private val MODULE_NAMES = listOf("server", "web", "pda")
    private val SKIP_DIRS = setOf(".git", ".idea", "node_modules", "target", "build", "out", "dist", ".gradle")

    /**
     * 打开设置面板时携带的「上下文文件」旁路通道。
     *
     * 场景:workspace 目录下有多个平级子项目(每个都有 server/web/pda)时,
     * 单纯从 project.basePath 起 BFS 无法判断用户想配的是哪一个。
     * OpenSettingsAction 在 ProjectView 右键触发时把 e.getData(VIRTUAL_FILE) 写进这里,
     * Panel 打开后读取以精确定位所属子项目;读完立即清空,避免污染后续常规入口打开。
     */
    val SETTINGS_CONTEXT_FILE: Key<VirtualFile> = Key.create("ims.settings.context.file")

    // Pre-compiled Regex constants (avoids recompilation on every method call)
    private val JAR_VERSION_REGEX = Regex("""-(\d+(?:\.\d+)*)""")
    private val DEVSERVER_REGEX = Regex("""devServer\s*:\s*\{""")
    private val SERVER_PORT_SET_REGEX = Regex("""^server\.port\s*=.*$""")
    private val SERVER_PORT_GET_REGEX = Regex("""^server\.port\s*=\s*(\d+)\s*$""")
    private val PORT_NUM_REGEX = Regex("""port\s*:\s*\d+""")
    private val PORT_CAPTURE_REGEX = Regex("""port\s*:\s*(\d+)""")
    private val URL_HOST_PORT_REGEX = Regex("""http://([^:/\s'"]+):(\d+)""")
    private val PRO_VERSION_SET_REGEX = Regex("""(appUtils\.pro_version\s*=\s*['"])([^'"]+)(['"])""")
    private val PRO_VERSION_GET_REGEX = Regex("""appUtils\.pro_version\s*=\s*['"]([^'"]+)['"]""")
    private val GET_VERSION_BLOCK_REGEX = Regex("""getVersion\s*\(\s*\)\s*\{([^}]+)\}""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val RETURN_VERSION_REGEX = Regex("""return\s*"([^"]+)"""")
    private val PACKAGE_JSON_FILE_REF_REGEX = Regex(""""([^"]+)":\s*"file:[^"]*?-(\d+\.\d+\.\d+)[^"]*"""")

    data class ValidationMessage(val title: String, val message: String)

    data class ModulePaths(
        val server: String,
        val web: String,
        val pda: String,
        val client: String
    )

    data class PathStatus(
        val serverValid: Boolean,
        val webValid: Boolean,
        val pdaValid: Boolean
    ) {
        val hasServer: Boolean get() = serverValid
        val hasWeb: Boolean get() = webValid
        val hasPda: Boolean get() = pdaValid
        val hasAnyTarget: Boolean get() = webValid || pdaValid

        /** At least one module path is available (for init) */
        fun hasAnyPath(): Boolean = serverValid || webValid || pdaValid
    }

    /**
     * Check whether the configured paths actually exist on disk.
     */
    fun checkPaths(settings: ImsProjectSettings): PathStatus {
        return PathStatus(
            serverValid = isValidDirectory(settings.serverPath),
            webValid = isValidDirectory(settings.webPath),
            pdaValid = isValidDirectory(settings.pdaPath)
        )
    }

    /**
     * Validate that all required paths for a sync operation are configured and exist.
     * When persisted paths are invalid, tries auto-detection from the open project as fallback,
     * so that users who haven't clicked Apply in settings can still use sync features.
     * Returns null if everything is fine, or a [ValidationMessage] describing what's wrong.
     */
    fun validateForSync(project: Project, settings: ImsProjectSettings): ValidationMessage? {
        val status = checkPathsWithFallback(project, settings)
        return when {
            !status.hasServer -> ValidationMessage(
                ImsBundle.message("validate.error.title"),
                ImsBundle.message("validate.error.server_path")
            )
            !status.hasAnyTarget -> ValidationMessage(
                ImsBundle.message("validate.error.title"),
                ImsBundle.message("validate.error.target_path")
            )
            else -> null
        }
    }

    /**
     * Check paths with auto-detection fallback for any missing/invalid persisted values.
     * Only attempts auto-detection when a path is invalid; never overwrites valid persisted values.
     */
    private fun checkPathsWithFallback(project: Project, settings: ImsProjectSettings): PathStatus {
        var server = isValidDirectory(settings.serverPath)
        var web = isValidDirectory(settings.webPath)
        var pda = isValidDirectory(settings.pdaPath)

        // If any path is missing, try auto-detection as a one-shot fallback
        if (!server || !web || !pda) {
            val (autoServer, autoWeb, autoPda) = autoDetectModulePaths(project)
            if (!server && autoServer.isNotBlank()) server = isValidDirectory(autoServer)
            if (!web && autoWeb.isNotBlank()) web = isValidDirectory(autoWeb)
            if (!pda && autoPda.isNotBlank()) pda = isValidDirectory(autoPda)
        }

        return PathStatus(server, web, pda)
    }

    internal fun modulePathsFromContainer(container: File): ModulePaths =
        ModulePaths(
            File(container, "server").takeIf { it.isDirectory }?.absolutePath ?: "",
            File(container, "web").takeIf { it.isDirectory }?.absolutePath ?: "",
            File(container, "pda").takeIf { it.isDirectory }?.absolutePath ?: "",
            File(container, "client").takeIf { it.isDirectory }?.absolutePath ?: ""
        )

    /** 自动识别 Server/Web/PDA 以及可选的 Client 模块路径。 */
    fun detectModulePaths(project: Project): ModulePaths {
        val root = project.basePath?.let { File(it) } ?: return ModulePaths("", "", "", "")
        val container = findModuleContainer(root) ?: return ModulePaths("", "", "", "")
        return modulePathsFromContainer(container)
    }

    /** 保留三端路径接口，供现有同步与启动功能使用。 */
    fun autoDetectModulePaths(project: Project): Triple<String, String, String> {
        val paths = detectModulePaths(project)
        return Triple(paths.server, paths.web, paths.pda)
    }

    /**
     * Resolve a single effective path: use persisted value if valid, otherwise try auto-detection.
     * Used by StartWeb/StartPDA actions to silently recover from unapplied settings.
     * @param persistedPath The path from persisted settings (may be blank or invalid)
     * @param moduleName One of "server", "web", "pda"
     * @return The resolved path, or empty string if not found.
     */
    fun resolveEffectivePath(project: Project, persistedPath: String, moduleName: String): String {
        if (isValidDirectory(persistedPath)) return persistedPath
        val (s, w, p) = autoDetectModulePaths(project)
        return when (moduleName.lowercase()) {
            "server" -> s
            "web" -> w
            "pda" -> p
            else -> ""
        }
    }

    /**
     * Scan from root directory to find the container that contains server/web/pda subdirectories.
     * Uses bounded BFS (max depth 3), skips heavy directories (.git, node_modules, etc.).
     * Also checks the parent directory in case the opened project is itself a module subdirectory.
     * Public so [ImsProjectSettingsPanel] can delegate auto-detection to it.
     */
    fun findModuleContainer(root: File, maxDepth: Int = 3): File? {
        fun score(dir: File): Int {
            val children = dir.listFiles()?.filter { it.isDirectory } ?: return 0
            return MODULE_NAMES.count { name -> children.any { it.name.equals(name, ignoreCase = true) } }
        }

        var best: File? = null
        var bestScore = 0
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val s = score(dir)
            if (s > bestScore) { bestScore = s; best = dir }
            if (bestScore == MODULE_NAMES.size) break // all three found, early exit
            if (depth < maxDepth) {
                dir.listFiles()
                    ?.filter { it.isDirectory && it.name !in SKIP_DIRS && !it.name.startsWith(".") }
                    ?.forEach { queue.add(it to depth + 1) }
            }
        }

        // The opened project might itself be a module (e.g. directly opened "server")
        root.parentFile?.let { parent ->
            val s = score(parent)
            if (s > bestScore) { bestScore = s; best = parent }
        }

        return best
    }

    /**
     * 给定一个上下文文件/目录,沿目录树向上查找第一个「同级下同时包含 server/web/pda」的祖先目录。
     *
     * 用于多子项目 workspace 场景:用户在 ProjectView 右键某个子项目的文件/目录进设置,
     * 或在多子项目下正在编辑某个文件,依此精确定位目标子项目 container,避免 BFS 命中字典序第一。
     *
     * 匹配口径:祖先目录下必须同时存在 server + web + pda 三个子目录(与 [findModuleContainer]
     * 的 score==3 早退判据一致);任一缺失即继续向上找。
     *
     * @param contextFile 用户右键选中的文件/目录,或当前活动编辑器文件
     * @return 匹配到的 container 目录;找不到时返回 null
     */
    fun findContainerForFile(contextFile: File): File? {
        fun hasAllModules(dir: File): Boolean {
            val children = dir.listFiles()?.filter { it.isDirectory } ?: return false
            return MODULE_NAMES.all { name -> children.any { it.name.equals(name, ignoreCase = true) } }
        }

        // 文件本身作为祖先起点:目录直接看自身,文件从其父目录开始
        var cur: File? = if (contextFile.isDirectory) contextFile else contextFile.parentFile
        while (cur != null) {
            if (hasAllModules(cur)) return cur
            cur = cur.parentFile
        }
        return null
    }

    /**
     * Validate that at least one module path exists (for init operations that can work
     * with partial configuration).
     * Returns null if ok, or a [ValidationMessage] if nothing is configured.
     */
    fun validateForInit(settings: ImsProjectSettings): ValidationMessage? {
        val status = checkPaths(settings)
        return if (!status.hasAnyPath()) {
            ValidationMessage(
                ImsBundle.message("validate.error.title"),
                ImsBundle.message("validate.error.no_paths")
            )
        } else null
    }

    /**
     * Get the display text for a single path status, used in the settings panel.
     */
    fun pathStatusText(path: String): String {
        return when {
            path.isBlank() -> ImsBundle.message("validate.status.not_configured")
            !File(path).exists() -> ImsBundle.message("validate.status.not_exist")
            !File(path).isDirectory -> ImsBundle.message("validate.status.not_directory")
            else -> ImsBundle.message("validate.status.valid")
        }
    }

    // ========== 版本获取 ==========

    /** 缓存已加载的版本号 key=path, value=version */
    private val versionCache = mutableMapOf<String, String>()

    /** 清除版本缓存，强制下次获取时重新读取 */
    fun clearVersionCache() {
        versionCache.clear()
    }

    // ========== 平台版本获取 ==========

    /**
     * 获取 Server 端平台版本号，通过读取 Constants.PLATFORM_VERSION 常量值。
     */
    fun getServerPlatformVersion(project: Project, serverPath: String): String {
        if (serverPath.isBlank()) return ""
        val cacheKey = "platform:$serverPath"
        versionCache[cacheKey]?.let { return it }
        val version = readPlatformVersionFromLibDir(File(serverPath, "lib"))
        if (version.isNotEmpty()) {
            versionCache[cacheKey] = version
        }
        return version
    }

    /** 获取 CS 端平台版本号，读取 client/lib 下的核心平台 jar。 */
    fun getClientPlatformVersion(clientPath: String): String {
        if (clientPath.isBlank()) return ""
        val cacheKey = "clientPlatform:$clientPath"
        versionCache[cacheKey]?.let { return it }
        val version = readPlatformVersionFromLibDir(File(clientPath, "lib"))
        if (version.isNotEmpty()) versionCache[cacheKey] = version
        return version
    }

    /**
     * 从指定 lib 目录读取平台版本号（通过 classloading Constants.PLATFORM_VERSION）。
     * 供 getServerPlatformVersion() 和 TemplateScanner.resolveTargetVersion() 复用。
     */
    fun readPlatformVersionFromLibDir(libDir: File): String {
        return try {
            if (!libDir.isDirectory) return ""

            val allJars = libDir.listFiles()?.filter { it.name.endsWith(".jar") } ?: return ""
            // lib 下可能残留多个版本的 core jar（升级失败/回滚不完整时）。listFiles 顺序不保证，
            // 必须按版本号排序取最高，否则读到的版本会随目录顺序漂移，进而触发"三端版本不一致"
            // 误报与重复弹窗。
            val coreJars = allJars.filter {
                it.name.startsWith("ims-fa-core-") && !it.name.contains("javadoc") && !it.name.contains("sources")
            }
            val coreJar = coreJars.maxByOrNull { jarVersionSortKey(it.name) } ?: return ""

            // 把选中的最高版本 core jar 放 classpath 最前，并排除其它版本的 core jar，
            // 确保 Constants（含 PLATFORM_VERSION）确定地来自选中的那个 jar。
            val otherJars = allJars.filter { it !in coreJars }
            val jarUrls = (listOf(coreJar) + otherJars).map { it.toURI().toURL() }.toTypedArray()
            // URLClassLoader 在 Windows 上会持有每个 jar 的文件句柄（锁定文件），
            // 必须 use{} 关闭。否则平台升级时 lib 下的 jar 被本插件锁住、无法移动/覆盖，
            // 导致 Server 大版本升级失败并回滚（回滚 moveInto 同样被锁），三端版本不一致。
            URLClassLoader(jarUrls, javaClass.classLoader).use { classLoader ->
                val clazz = classLoader.loadClass("com.pangus.ims.fa.core.util.Constants")
                val field = clazz.getField("PLATFORM_VERSION")
                val version = field.get(null) as? String ?: ""
                if (version.isNotBlank()) {
                    log.info("Platform version resolved from lib: $version (from ${coreJar.name}, dir=${libDir.path})")
                }
                version
            }
        } catch (e: Exception) {
            log.debug("Platform version not resolved from ${libDir.path}", e)
            ""
        }
    }

    /** 从 jar 文件名提取版本号并归一化为可字典序比较的 key：
     *  "ims-fa-core-6.8.1.jar" → "000006.000008.000001"，无版本号返回 "" */
    private fun jarVersionSortKey(jarName: String): String {
        val ver = JAR_VERSION_REGEX.find(jarName.removeSuffix(".jar"))?.groupValues?.get(1) ?: return ""
        return ver.split(".").joinToString(".") { (it.toIntOrNull() ?: 0).toString().padStart(6, '0') }
    }

    /**
     * 获取 Web 端平台版本号，从 package.json 的 dependencies 中
     * 所有 file 依赖（ims-fa-core, ims-fa-web, ims-web）提取版本号。
     * 三个包版本一致时返回版本号，不一致时返回空字符串。
     */
    fun getWebPlatformVersion(webPath: String): String {
        if (webPath.isBlank()) return ""
        val cacheKey = "webPlatform:$webPath"
        versionCache[cacheKey]?.let { return it }
        return try {
            val packageJson = File(webPath, "package.json")
            if (!packageJson.isFile) return ""
            val allVersions = extractAllPlatformVersions(packageJson)
            val version = resolvePlatformVersion(allVersions)
            versionCache[cacheKey] = version
            if (version.isNotBlank()) {
                log.info("Web platform version resolved: $version")
            } else if (allVersions.isNotEmpty()) {
                log.warn("Web platform versions inconsistent: $allVersions")
            }
            version
        } catch (e: Exception) {
            log.debug("Failed to resolve version", e)
            ""
        }
    }

    /**
     * 获取 PDA 端平台版本号，从 package.json 的 dependencies 中
     * 所有 file 依赖（ims-fa-core, ims-fa-pda, ims-pda）提取版本号。
     * 三个包版本一致时返回版本号，不一致时返回空字符串。
     */
    fun getPdaPlatformVersion(pdaPath: String): String {
        if (pdaPath.isBlank()) return ""
        val cacheKey = "pdaPlatform:$pdaPath"
        versionCache[cacheKey]?.let { return it }
        return try {
            val packageJson = File(pdaPath, "package.json")
            if (!packageJson.isFile) return ""
            val allVersions = extractAllPlatformVersions(packageJson)
            val version = resolvePlatformVersion(allVersions)
            versionCache[cacheKey] = version
            if (version.isNotBlank()) {
                log.info("PDA platform version resolved: $version")
            } else if (allVersions.isNotEmpty()) {
                log.warn("PDA platform versions inconsistent: $allVersions")
            }
            version
        } catch (e: Exception) {
            log.debug("Failed to resolve version", e)
            ""
        }
    }

    /**
     * 检查 Web 端平台版本是否一致。
     * @return 不一致时返回 ValidationMessage（含包名和版本详情），一致时返回 null
     */
    fun checkWebPlatformVersionConsistency(webPath: String): ValidationMessage? {
        if (webPath.isBlank()) return null
        return try {
            val packageJson = File(webPath, "package.json")
            if (!packageJson.isFile) return null
            val allVersions = extractAllPlatformVersions(packageJson)
            buildConsistencyWarning(allVersions, "Web")
        } catch (e: Exception) {
            log.debug("Failed to check version consistency", e)
            null
        }
    }

    /**
     * 检查 PDA 端平台版本是否一致。
     * @return 不一致时返回 ValidationMessage，一致时返回 null
     */
    fun checkPdaPlatformVersionConsistency(pdaPath: String): ValidationMessage? {
        if (pdaPath.isBlank()) return null
        return try {
            val packageJson = File(pdaPath, "package.json")
            if (!packageJson.isFile) return null
            val allVersions = extractAllPlatformVersions(packageJson)
            buildConsistencyWarning(allVersions, "PDA")
        } catch (e: Exception) {
            log.debug("Failed to check version consistency", e)
            null
        }
    }

    // ========== 项目版本获取 ==========

    /**
     * 获取 Server 端项目版本号。
     * 在 src/main/java 下 util 包中找 *Configure.java（排除 AppConfigure.java），
     * 读取其 getVersion() 返回值。不同项目的 Configure 类名随项目编码变化，按文件名模式匹配即可。
     */
    fun getServerProjectVersion(project: Project, serverPath: String, projectCode: String): String {
        return getJavaProjectVersion("Server", serverPath, "serverProj:$serverPath")
    }

    /** 获取 CS 端项目版本号，读取 client/src/main/java 下 util 包中的 *Configure.getVersion()。 */
    fun getClientProjectVersion(clientPath: String): String {
        return getJavaProjectVersion("CS", clientPath, "clientProj:$clientPath")
    }

    private fun getJavaProjectVersion(moduleName: String, modulePath: String, cacheKey: String): String {
        if (modulePath.isBlank()) return ""
        versionCache[cacheKey]?.let { return it }

        val moduleDir = File(modulePath)
        var version = ""

        // 1. 搜索源码树：src/main/java/**/util/*Configure.java（排除 AppConfigure）
        val srcDir = File(moduleDir, "src/main/java")
        if (srcDir.isDirectory) {
            val configFile = srcDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith("Configure.java") && it.name != "AppConfigure.java" }
                .firstOrNull { it.parentFile?.name.equals("util", ignoreCase = true) }
            if (configFile != null) {
                version = parseGetVersionFromSource(configFile)
                if (version.isNotBlank()) {
                    log.info("$moduleName project version resolved from source: $version (${configFile.path})")
                }
            }
            if (version.isBlank()) {
                log.info("$moduleName project version: no *Configure.java (excluding AppConfigure) found in util package")
            }
        }

        // 2. 回退：尝试从编译产物 classloading（同样排除 AppConfigure）
        if (version.isBlank()) {
            version = tryLoadConfigureVersion(moduleDir)
        }

        // 只缓存非空结果：为空可能是编译前尚未生成产物，编译后应重取而非返回旧空值
        if (version.isNotBlank()) versionCache[cacheKey] = version
        return version
    }

    /**
     * 获取 Web 端项目版本号，从 src/main.js 中读取 appUtils.pro_version 的值。
     */
    fun getWebProjectVersion(webPath: String): String {
        if (webPath.isBlank()) return ""
        val cacheKey = "webProj:$webPath"
        versionCache[cacheKey]?.let { return it }
        return try {
            val mainJs = File(webPath, "src/main.js")
            if (!mainJs.isFile) return ""
            val version = parseProVersionFromMainJs(mainJs)
            versionCache[cacheKey] = version
            if (version.isNotBlank()) log.info("Web project version resolved: $version")
            version
        } catch (e: Exception) {
            log.debug("Failed to resolve version", e)
            ""
        }
    }

    /**
     * 获取 PDA 端项目版本号，从 src/main.js 中读取 appUtils.pro_version 的值。
     */
    fun getPdaProjectVersion(pdaPath: String): String {
        if (pdaPath.isBlank()) return ""
        val cacheKey = "pdaProj:$pdaPath"
        versionCache[cacheKey]?.let { return it }
        return try {
            val mainJs = File(pdaPath, "src/main.js")
            if (!mainJs.isFile) return ""
            val version = parseProVersionFromMainJs(mainJs)
            versionCache[cacheKey] = version
            if (version.isNotBlank()) log.info("PDA project version resolved: $version")
            version
        } catch (e: Exception) {
            log.debug("Failed to resolve version", e)
            ""
        }
    }

    // ========== 项目编码/名称读取 ==========

    // ColumnType 内部类名：SystemCode.CUSTOM = 项目编码，SystemName.CUSTOM = 项目名称
    private const val SYSTEM_CODE = "SystemCode"
    private const val SYSTEM_NAME = "SystemName"

    /**
     * 判断是否为模板工程（未初始化）。
     * 判定依据：ImsSystemDml.java 所在包名为 demo。
     * 已初始化的项目包名会被重命名为项目编码（如 swl、ptsn）。
     */
    fun isDemoProject(serverPath: String): Boolean {
        if (serverPath.isBlank()) return false
        val srcDir = File(serverPath, "src/main/java")
        if (!srcDir.isDirectory) return false
        val dmlFile = srcDir.walkTopDown()
            .filter { it.isFile && it.name == "ImsSystemDml.java" }
            .firstOrNull { it.parentFile?.name.equals("dml", ignoreCase = true) }
            ?: return false
        // 检查 ImsSystemDml.java 所在包路径中是否包含 "demo" 目录
        val relativePath = dmlFile.relativeTo(srcDir).path.replace('\\', '/')
        return "/demo/" in "/$relativePath/"
    }

    /**
     * 从 Server 端 dml 包下的 ImsSystemDml.java 中读取项目编码和项目名称。
     * 解析构造函数中 add(...) 的前两个参数：
     * - 第一个参数 = 项目编码
     * - 第二个参数 = 项目名称
     * 优先使用 PSI resolve 解析常量引用；PSI 失败时 fallback 到正则文本匹配。
     * @return Pair(项目编码, 项目名称)，未找到时返回 Pair("", "")
     */
    fun getProjectCodeAndName(project: Project, serverPath: String): Pair<String, String> {
        if (serverPath.isBlank()) return Pair("", "")
        val cacheKey = "projCodeName:$serverPath"
        versionCache[cacheKey]?.let {
            val parts = it.split("|", limit = 2)
            return Pair(parts[0], parts.getOrElse(1) { "" })
        }

        val srcDir = File(serverPath, "src/main/java")
        if (!srcDir.isDirectory) return Pair("", "")

        // 在 src 下搜索 dml 包中的 ImsSystemDml.java
        val dmlFile = srcDir.walkTopDown()
            .filter { it.isFile && it.name == "ImsSystemDml.java" }
            .firstOrNull { it.parentFile?.name.equals("dml", ignoreCase = true) }

        if (dmlFile == null) {
            log.info("ImsSystemDml.java not found in dml package")
            return Pair("", "")
        }

        // 优先 PSI 解析
        val psiResult = parseProjectCodeAndNameFromDml(project, dmlFile)
        if (psiResult.first.isNotBlank() || psiResult.second.isNotBlank()) {
            versionCache[cacheKey] = "${psiResult.first}|${psiResult.second}"
            return psiResult
        }

        // PSI 解析失败，fallback 到正则文本匹配
        val regexResult = parseProjectCodeAndNameByRegex(dmlFile, srcDir)
        if (regexResult.first.isNotBlank() || regexResult.second.isNotBlank()) {
            versionCache[cacheKey] = "${regexResult.first}|${regexResult.second}"
            return regexResult
        }

        // ImsSystemDml 的 add() 都没取到，回退到 XxxColumnType 内部类：
        // SystemCode.CUSTOM = 项目编码；SystemName.CUSTOM = 项目名称，取不到则用项目编码兜底
        val columnTypeResult = readCodeAndNameFromColumnType(dmlFile, srcDir)
        if (columnTypeResult.first.isNotBlank() || columnTypeResult.second.isNotBlank()) {
            val code = columnTypeResult.first
            val name = columnTypeResult.second.ifBlank { code }
            versionCache[cacheKey] = "$code|$name"
            return Pair(code, name)
        }

        return Pair("", "")
    }

    /**
     * 通过 PSI 解析 ImsSystemDml.java 构造函数中 add() 调用的前两个参数。
     * 支持字符串字面量和常量引用（PSI resolve 到字段定义取值）。
     */
    private fun parseProjectCodeAndNameFromDml(project: Project, dmlFile: File): Pair<String, String> {
        try {
            val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(dmlFile.absolutePath)
            if (virtualFile == null) return Pair("", "")

            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
            if (psiFile == null) return Pair("", "")

            // 找 ImsSystemDml 类的构造函数中的 add() 调用
            val constructor = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(psiFile, com.intellij.psi.PsiMethod::class.java)
                .firstOrNull { it.name == "<init>" && it.containingClass?.name == "ImsSystemDml" }
            if (constructor == null) return Pair("", "")

            // 在构造函数中找 add(...) 方法调用
            val addCall = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(constructor.body, com.intellij.psi.PsiCallExpression::class.java)
                .firstOrNull { (it as? com.intellij.psi.PsiMethodCallExpression)?.methodExpression?.referenceName == "add" }
            if (addCall == null) return Pair("", "")

            val args = (addCall as com.intellij.psi.PsiMethodCallExpression).argumentList.expressions
            if (args.size < 2) return Pair("", "")

            val code = resolveExpressionValue(args[0])
            val name = resolveExpressionValue(args[1])
            return Pair(code, name)
        } catch (e: Exception) {
            log.debug("Failed to parse ImsSystemDml via PSI", e)
            return Pair("", "")
        }
    }

    /**
     * 解析 PSI 表达式的值：
     * - 字符串字面量 → 直接取值
     * - 常量引用（如 XxxColumnType.SystemCode.CUSTOM）→ resolve 到字段定义取字面量值
     */
    private fun resolveExpressionValue(expression: com.intellij.psi.PsiExpression): String {
        // 1. 字符串字面量：直接取值
        if (expression is com.intellij.psi.PsiLiteralExpression && expression.value is String) {
            return expression.value as String
        }
        // 2. 常量引用：resolve 到字段定义，取其字面量值
        val reference = (expression as? com.intellij.psi.PsiReferenceExpression)?.resolve()
        if (reference is com.intellij.psi.PsiField) {
            val initializer = reference.initializer
            if (initializer is com.intellij.psi.PsiLiteralExpression && initializer.value is String) {
                return initializer.value as String
            }
        }
        return ""
    }

    /**
     * 正则 fallback：当 PSI resolve 失败时，通过文本匹配从 ImsSystemDml.java 提取项目编码和名称。
     *
     * 支持两种模式：
     * 1. 字符串字面量：add("code", "name", ...) → 直接提取
     * 2. 常量引用：add(XxxColumnType.SystemCode.CUSTOM, XxxColumnType.SystemName.CUSTOM, ...)
     *    → 定位 XxxColumnType.java 文件，用正则匹配 CUSTOM = "value"
     */
    private fun parseProjectCodeAndNameByRegex(dmlFile: File, srcDir: File): Pair<String, String> {
        try {
            // 先剔除注释，避免从被注释掉的 add(...) 中误取编码/名称
            val content = stripComments(dmlFile.readText())

            // 匹配 add( 第一个参数, 第二个参数,
            // 参数可能是字符串字面量 "xxx" 或常量引用 Xxx.Yyy.ZZZ
            val addRegex = Regex("""add\s*\(\s*([^,]+)\s*,\s*([^,]+)\s*,""")
            val addMatch = addRegex.find(content) ?: return Pair("", "")

            val arg1 = addMatch.groupValues[1].trim()
            val arg2 = addMatch.groupValues[2].trim()

            val code = resolveArgByRegex(arg1, dmlFile, srcDir)
            val name = resolveArgByRegex(arg2, dmlFile, srcDir)
            return Pair(code, name)
        } catch (e: Exception) {
            log.debug("Failed to parse ImsSystemDml by regex", e)
            return Pair("", "")
        }
    }

    /**
     * 兜底：ImsSystemDml 取不到项目编码/名称时，直接从项目自身 XxxColumnType 读取。
     *
     * 项目自身 ColumnType 位于与 ImsSystemDml 同一个项目编码包下的 util 子包
     * （ImsSystemDml 在 `…/<projectCode>/dbinit/dml/`，ColumnType 在 `…/<projectCode>/util/`），
     * 故以 dmlFile 为锚点定位 util 目录，不依赖 ImsSystemDml 的 import（add() 被注释时
     * 项目 ColumnType 不会被 import）。从其内部类取：
     * - SystemCode.CUSTOM = 项目编码
     * - SystemName.CUSTOM = 项目名称
     *
     * 项目 ColumnType 常只定义 SystemCode 而 SystemName 继承自平台 CoreColumnType（值为空占位），
     * 此时名称留空，由调用方用编码兜底。
     *
     * @return Pair(项目编码, 项目名称)，任一取不到留空（名称的兜底由调用方处理）
     */
    private fun readCodeAndNameFromColumnType(dmlFile: File, srcDir: File): Pair<String, String> {
        try {
            val columnTypeFile = findProjectColumnTypeFile(dmlFile) ?: return Pair("", "")
            val code = readColumnTypeCustomValue(columnTypeFile, SYSTEM_CODE)
            val name = readColumnTypeCustomValue(columnTypeFile, SYSTEM_NAME)
            return Pair(code, name)
        } catch (e: Exception) {
            log.debug("Failed to read project code/name from ColumnType", e)
            return Pair("", "")
        }
    }

    /**
     * 以 ImsSystemDml 为锚点定位项目自身 XxxColumnType 源文件。
     *
     * ImsSystemDml 位于 `…/<projectCode>/dbinit/dml/ImsSystemDml.java`，项目 ColumnType 位于
     * `…/<projectCode>/util/`。从 dmlFile 回溯到 `<projectCode>` 目录（dbinit 的上一级），
     * 在其下 `util` 子目录搜索以 `ColumnType.java` 结尾、且定义了 SystemCode.CUSTOM 的源文件。
     * 排除平台 CoreColumnType（包名含 fa.core 或类名 CoreColumnType）。
     */
    private fun findProjectColumnTypeFile(dmlFile: File): File? {
        // dmlFile: …/<projectCode>/dbinit/dml/ImsSystemDml.java → projectPkg = …/<projectCode>
        val projectPkg = dmlFile.parentFile?.parentFile?.parentFile ?: return null
        val utilDir = File(projectPkg, "util")
        if (!utilDir.isDirectory) return null
        return utilDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("ColumnType.java") }
            .filter { it.name != "CoreColumnType.java" }
            .firstOrNull { readColumnTypeCustomValue(it, SYSTEM_CODE).isNotBlank() }
    }

    /**
     * 从 XxxColumnType.java 读取指定内部类（SystemCode/SystemName）下 CUSTOM 字段的字符串值。
     * 精确定位内部类块，避免同名 CUSTOM 匹配到错误内部类（与 [resolveArgByRegex] 同思路）。
     */
    private fun readColumnTypeCustomValue(columnTypeFile: File, innerClassName: String): String {
        val fileContent = columnTypeFile.readText()
        val innerClassRegex = Regex(
            """public\s+static\s+class\s+${Regex.escape(innerClassName)}\s+[^{]*\{((?:[^{}]|\{[^{}]*\})*)\}""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val innerMatch = innerClassRegex.find(fileContent) ?: return ""
        val innerClassBody = innerMatch.groupValues[1]
        val fieldRegex = Regex("""public\s+static\s+final\s+String\s+CUSTOM\s*=\s*"([^"]*)"""")
        return fieldRegex.find(innerClassBody)?.groupValues?.get(1) ?: ""
    }

    /**
     * 剔除 Java 源码中的注释：块注释、行注释。
     * 字符串字面量内的注释标记不处理——ImsSystemDml 的 add() 参数不会有跨行的复杂字符串，
     * 简单实现即可，目的只是不让被注释掉的 add(...) 干扰正则匹配。
     */
    private fun stripComments(source: String): String {
        // 先去块注释，再去行注释
        val noBlock = Regex("""/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL)).replace(source, "")
        val noLine = noBlock.lines().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
        return noLine
    }

    /**
     * 解析 add() 的单个参数值：
     * - 字符串字面量 "xxx" → 直接取值
     * - 常量引用 XxxColumnType.SystemCode.CUSTOM → 找到 XxxColumnType.java，匹配 CUSTOM = "value"
     */
    private fun resolveArgByRegex(arg: String, dmlFile: File, srcDir: File): String {
        // 1. 字符串字面量
        val literalRegex = Regex("""^"(.*)"$""")
        literalRegex.find(arg)?.let { return it.groupValues[1] }

        // 2. 常量引用：如 PtsnColumnType.SystemCode.CUSTOM
        //    提取类名前缀（PtsnColumnType）、内部类名（SystemCode/SystemName）、字段名（CUSTOM）
        val refRegex = Regex("""^(\w+ColumnType)\.(\w+)\.(\w+)$""")
        val refMatch = refRegex.find(arg) ?: return ""

        val className = refMatch.groupValues[1]       // e.g. PtsnColumnType
        val innerClassName = refMatch.groupValues[2]   // e.g. SystemCode / SystemName
        val fieldName = refMatch.groupValues[3]        // e.g. CUSTOM

        // 从 dmlFile 的 import 中找到 ColumnType 类的完整包路径
        val dmlContent = dmlFile.readText()
        val importRegex = Regex("""import\s+(.+\.${Regex.escape(className)})\s*;""")
        val importMatch = importRegex.find(dmlContent) ?: return ""

        val fullPackage = importMatch.groupValues[1]
        val relativePath = fullPackage.replace('.', '/') + ".java"

        // 在 srcDir 下查找该文件
        val columnTypeFile = srcDir.walkTopDown()
            .filter { it.isFile && it.path.replace('\\', '/').endsWith(relativePath) }
            .firstOrNull() ?: return ""

        val fileContent = columnTypeFile.readText()

        // 先定位内部类 {innerClassName} 的花括号块，避免同名 CUSTOM 字段匹配到错误的内部类
        val innerClassRegex = Regex(
            """public\s+static\s+class\s+${Regex.escape(innerClassName)}\s+[^{]*\{((?:[^{}]|\{[^{}]*\})*)\}""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val innerMatch = innerClassRegex.find(fileContent) ?: return ""
        val innerClassBody = innerMatch.groupValues[1]

        // 在内部类体内匹配: public static final String CUSTOM = "value";
        val fieldRegex = Regex("""public\s+static\s+final\s+String\s+${Regex.escape(fieldName)}\s*=\s*"([^"]*)"""")
        val fieldMatch = fieldRegex.find(innerClassBody) ?: return ""

        return fieldMatch.groupValues[1]
    }

    // ========== 端口读取 ==========

    /**
     * 从 Server 端根目录下 application.properties 中读取 server.port 的值。
     * @return 端口号，未找到时返回 0
     */
    fun getServerPort(serverPath: String): Int {
        if (serverPath.isBlank()) return 0
        val cacheKey = "serverPort:$serverPath"
        versionCache[cacheKey]?.let { return it.toIntOrNull() ?: 0 }
        val port = readApplicationPortFromProperties(serverPath)
        if (port > 0) {
            versionCache[cacheKey] = port.toString()
        }
        return port
    }

    /**
     * 将端口号写回 Server 端根目录下 application.properties 中的 server.port。
     * 如果文件中已有 server.port 行则替换，否则在文件末尾追加。
     */
    fun setServerPort(serverPath: String, port: Int) {
        if (writeApplicationPort(serverPath, port, "serverPort:$serverPath", "Server")) {
            syncDevConfigPort(serverPath, port)
        }
    }

    /** 从 client/application.properties 读取 CS 端 server.port。 */
    fun getClientPort(clientPath: String): Int {
        if (clientPath.isBlank()) return 0
        val cacheKey = "clientPort:$clientPath"
        versionCache[cacheKey]?.let { return it.toIntOrNull() ?: 0 }
        val port = readApplicationPortFromProperties(clientPath)
        if (port > 0) versionCache[cacheKey] = port.toString()
        return port
    }

    /** 将 CS 端端口写回 client/application.properties。 */
    fun setClientPort(clientPath: String, port: Int) {
        writeApplicationPort(clientPath, port, "clientPort:$clientPath", "CS")
    }

    private fun writeApplicationPort(modulePath: String, port: Int, cacheKey: String, moduleName: String): Boolean {
        if (modulePath.isBlank() || port <= 0) return false
        val propsFile = File(modulePath, "application.properties")
        if (!propsFile.isFile) return false
        try {
            val lines = propsFile.readLines().toMutableList()
            val regex = SERVER_PORT_SET_REGEX
            var replaced = false
            for (i in lines.indices) {
                val trimmed = lines[i].trim()
                if (trimmed.startsWith("#")) continue
                if (regex.matches(trimmed)) {
                    if (trimmed.substringAfter('=').trim().toIntOrNull() == port) {
                        versionCache[cacheKey] = port.toString()
                        return true
                    }
                    lines[i] = "server.port=$port"
                    replaced = true
                    break
                }
            }
            if (!replaced) {
                lines.add("server.port=$port")
            }
            writeTextAndRefresh(propsFile, lines.joinToString("\n", postfix = "\n"))
            versionCache[cacheKey] = port.toString()
            log.info("$moduleName port updated in ${propsFile.path}: $port")
            return true
        } catch (e: Exception) {
            log.warn("Failed to write server.port to ${propsFile.path}", e)
            return false
        }
    }

    /**
     * Server 端口变更后，同步更新 Web/PDA 的 src/ajax/ajaxDevConfig.js。
     * 当 url 中的 host 为本机任一 IP 且端口与变更后不同时，替换端口号。
     */
    private fun syncDevConfigPort(serverPath: String, serverPort: Int) {
        // 通过 server 路径推算同级 web/pda 目录
        val parentDir = File(serverPath).parentFile ?: return
        val targets = listOf(
            File(parentDir, "web") to "web",
            File(parentDir, "pda") to "pda"
        )
        for ((dir, label) in targets) {
            val devConfigFile = File(dir, "src/ajax/ajaxDevConfig.js")
            if (!devConfigFile.isFile) continue
            try {
                val content = devConfigFile.readText()
                // 匹配 http://host:port，提取 host 和 port
                val urlRegex = URL_HOST_PORT_REGEX
                val match = urlRegex.find(content) ?: continue
                val host = match.groupValues[1]
                val currentPort = match.groupValues[2].toIntOrNull() ?: continue
                // 仅当 host 为本机 IP 且端口与变更后不同时才更新
                if (!isLocalAddress(host)) continue
                if (currentPort == serverPort) continue
                val newContent = urlRegex.replace(content) { result ->
                    "http://${result.groupValues[1]}:$serverPort"
                }
                writeTextAndRefresh(devConfigFile, newContent)
                log.info("$label devConfig URL port synced to $serverPort in ${devConfigFile.path}")
            } catch (e: Exception) {
                log.warn("Failed to sync devConfig port in ${devConfigFile.path}", e)
            }
        }
    }

    /** 判断 host 是否为本机地址（127.0.0.1、localhost、本机任一网卡 IP） */
    private fun isLocalAddress(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1") return true
        try {
            val localAddresses = java.net.NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.map { it.hostAddress }
                ?.toSet() ?: emptySet()
            return host in localAddresses
        } catch (e: Exception) {
            log.debug("Failed to enumerate local addresses", e)
            return false
        }
    }

    /**
     * 从 Server 根目录下 application.properties 中读取 server.port。
     */
    private fun readApplicationPortFromProperties(modulePath: String): Int {
        val propsFile = File(modulePath, "application.properties")
        if (!propsFile.isFile) return 0
        try {
            val port = parseServerPortFromProperties(propsFile)
            if (port > 0) {
                log.info("Server port resolved from ${propsFile.path}: $port")
            }
            return port
        } catch (e: Exception) {
            log.debug("Failed to read server.port from ${propsFile.path}", e)
            return 0
        }
    }

    /** 从 properties 文件中解析 server.port=XXXX */
    private fun parseServerPortFromProperties(file: File): Int {
        val regex = SERVER_PORT_GET_REGEX
        var result = 0
        file.forEachLine { line ->
            if (result > 0) return@forEachLine  // 已找到，跳过后续行
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) return@forEachLine  // 跳过注释行
            regex.matchEntire(trimmed)?.let {
                result = it.groupValues[1].toIntOrNull() ?: 0
            }
        }
        return result
    }

    // ========== Web 端口读写 ==========

    /**
     * 从 Web 端根目录下 vue.config.js 中读取 devServer.port 的值。
     * @return 端口号，未找到时返回 0
     */
    fun getWebPort(webPath: String): Int {
        if (webPath.isBlank()) return 0
        val cacheKey = "webPort:$webPath"
        versionCache[cacheKey]?.let { return it.toIntOrNull() ?: 0 }
        val port = readDevServerPortFromVueConfig(webPath)
        if (port > 0) {
            versionCache[cacheKey] = port.toString()
        }
        return port
    }

    /**
     * 将端口号写回 Web 端根目录下 vue.config.js 中 devServer 内的 port。
     * 只替换 devServer 块内的第一个 port 值，不影响文件其他位置。
     */
    fun setWebPort(webPath: String, port: Int) {
        if (webPath.isBlank() || port <= 0) return
        val vueConfigFile = File(webPath, "vue.config.js")
        if (!vueConfigFile.isFile) return
        try {
            val content = vueConfigFile.readText()
            // 定位 devServer: {
            val devServerRegex = DEVSERVER_REGEX
            val devServerMatch = devServerRegex.find(content) ?: return
            // 在 devServer 之后查找 port: xxx
            val afterDevServer = content.substring(devServerMatch.range.first)
            val portRegex = Regex("""port\s*:\s*\d+""")
            val portMatch = portRegex.find(afterDevServer) ?: return
            if (portMatch.value.substringAfter(':').trim().toIntOrNull() == port) {
                versionCache["webPort:$webPath"] = port.toString()
                return
            }
            // 计算绝对位置，只替换这一处
            val absStart = devServerMatch.range.first + portMatch.range.first
            val absEnd = devServerMatch.range.first + portMatch.range.last + 1
            val newContent = content.substring(0, absStart) + "port: $port" + content.substring(absEnd)
            writeTextAndRefresh(vueConfigFile, newContent)
            versionCache["webPort:$webPath"] = port.toString()
            log.info("Web port updated in ${vueConfigFile.path}: $port")
        } catch (e: Exception) {
            log.warn("Failed to write devServer.port to ${vueConfigFile.path}", e)
        }
    }

    /**
     * 从 Web 根目录下 vue.config.js 中读取 devServer.port。
     */
    private fun readDevServerPortFromVueConfig(webPath: String): Int {
        val vueConfigFile = File(webPath, "vue.config.js")
        if (!vueConfigFile.isFile) return 0
        try {
            return parseDevServerPortFromVueConfig(vueConfigFile)
        } catch (e: Exception) {
            log.debug("Failed to read devServer.port from ${vueConfigFile.path}", e)
            return 0
        }
    }

    /** 从 vue.config.js 中解析 devServer 下的 port: XXXX */
    private fun parseDevServerPortFromVueConfig(file: File): Int {
        val content = file.readText()
        // 定位 devServer: {
        val devServerRegex = DEVSERVER_REGEX
        val devServerMatch = devServerRegex.find(content) ?: return 0
        val afterDevServer = content.substring(devServerMatch.range.first)
        // 在 devServer 块内找 port: xxx
        val portRegex = Regex("""port\s*:\s*(\d+)""")
        val portMatch = portRegex.find(afterDevServer) ?: return 0
        val port = portMatch.groupValues[1].toIntOrNull() ?: 0
        if (port > 0) {
            log.info("devServer.port resolved from ${file.path}: $port")
        }
        return port
    }

    // ========== PDA 端口读写 ==========

    /**
     * 从 PDA 端根目录下 vue.config.js 中读取 devServer.port 的值。
     * 与 Web 端口读取逻辑完全一致。
     * @return 端口号，未找到时返回 0
     */
    fun getPdaPort(pdaPath: String): Int {
        if (pdaPath.isBlank()) return 0
        val cacheKey = "pdaPort:$pdaPath"
        versionCache[cacheKey]?.let { return it.toIntOrNull() ?: 0 }
        val port = readDevServerPortFromVueConfig(pdaPath)
        if (port > 0) {
            versionCache[cacheKey] = port.toString()
        }
        return port
    }

    /**
     * 将端口号写回 PDA 端根目录下 vue.config.js 中 devServer 内的 port。
     * 与 Web 端口写入逻辑完全一致，只替换 devServer 块内的第一个 port 值。
     */
    fun setPdaPort(pdaPath: String, port: Int) {
        if (pdaPath.isBlank() || port <= 0) return
        val vueConfigFile = File(pdaPath, "vue.config.js")
        if (!vueConfigFile.isFile) return
        try {
            val content = vueConfigFile.readText()
            val devServerRegex = DEVSERVER_REGEX
            val devServerMatch = devServerRegex.find(content) ?: return
            val afterDevServer = content.substring(devServerMatch.range.first)
            val portRegex = PORT_NUM_REGEX
            val portMatch = portRegex.find(afterDevServer) ?: return
            if (portMatch.value.substringAfter(':').trim().toIntOrNull() == port) {
                versionCache["pdaPort:$pdaPath"] = port.toString()
                return
            }
            val absStart = devServerMatch.range.first + portMatch.range.first
            val absEnd = devServerMatch.range.first + portMatch.range.last + 1
            val newContent = content.substring(0, absStart) + "port: $port" + content.substring(absEnd)
            writeTextAndRefresh(vueConfigFile, newContent)
            versionCache["pdaPort:$pdaPath"] = port.toString()
            log.info("PDA port updated in ${vueConfigFile.path}: $port")
        } catch (e: Exception) {
            log.warn("Failed to write devServer.port to ${vueConfigFile.path}", e)
        }
    }

    // ========== 版本号写入 ==========

    /**
     * 更新 Server 端 *Configure.java 中 getVersion() 的返回值。
     * 找到 util 包下非 AppConfigure 的 *Configure.java，替换 return "旧版本" 为 return "新版本"。
     * 只替换版本号字符串本身，不改变方法体其他内容。
     * @return true 写入成功，false 失败
     */
    fun setServerProjectVersion(serverPath: String, newVersion: String): Boolean {
        if (serverPath.isBlank() || newVersion.isBlank()) return false
        return try {
            val srcDir = File(serverPath, "src/main/java")
            if (!srcDir.isDirectory) return false
            val configFile = srcDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith("Configure.java") && it.name != "AppConfigure.java" }
                .firstOrNull { it.parentFile?.name.equals("util", ignoreCase = true) }
                ?: return false
            val content = configFile.readText()
            // 精确匹配 getVersion() 方法体中的最后一个 return "版本号" 语句
            // 先定位方法体范围（从方法声明到右大括号），再在方法体内找 return "版本号"
            // 这样不会误匹配方法体外或其他方法内的 return
            val methodRegex = GET_VERSION_BLOCK_REGEX
            val methodMatch = methodRegex.find(content)
            if (methodMatch == null) {
                log.warn("setServerProjectVersion: getVersion() method body not matched in ${configFile.path}")
                return false
            }
            val methodBody = methodMatch.groupValues[1]
            val returnRegex = Regex("""return\s*"([^"]+)"""")
            val returnMatches = returnRegex.findAll(methodBody).toList()
            if (returnMatches.isEmpty()) {
                log.warn("setServerProjectVersion: no return statement found in getVersion() body in ${configFile.path}")
                return false
            }
            // 取最后一个 return 语句中的版本号（方法体可能有 if 分支，最后一个 return 是最终版本）
            val lastReturn = returnMatches.last()
            val oldVersion = lastReturn.groupValues[1]
            if (oldVersion == newVersion) {
                log.info("Server project version already $newVersion, no change needed")
                return true
            }
            // 计算版本号在完整文件中的绝对位置（方法体偏移 + 方法体内偏移）
            val methodBodyOffset = methodMatch.groups[1]!!.range.first
            val versionOffsetInBody = lastReturn.groups[1]!!.range.first
            val versionLength = lastReturn.groups[1]!!.range.last - versionOffsetInBody + 1
            val absoluteStart = methodBodyOffset + versionOffsetInBody
            val absoluteEnd = absoluteStart + versionLength
            val newContent = content.substring(0, absoluteStart) + newVersion + content.substring(absoluteEnd)
            writeTextAndRefresh(configFile, newContent)
            // 清除缓存，下次读取时重新解析
            versionCache.remove("serverProj:$serverPath")
            log.info("Server project version updated from $oldVersion to $newVersion in ${configFile.path}")
            true
        } catch (e: Exception) {
            log.warn("Failed to write server project version", e)
            false
        }
    }

    /**
     * 更新 Web 端 src/main.js 中 appUtils.pro_version 的值。
     * @return true 写入成功，false 失败
     */
    fun setWebProjectVersion(webPath: String, newVersion: String): Boolean {
        if (webPath.isBlank() || newVersion.isBlank()) return false
        return try {
            val mainJs = File(webPath, "src/main.js")
            if (!mainJs.isFile) return false
            val content = mainJs.readText()
            val regex = PRO_VERSION_SET_REGEX
            val match = regex.find(content)
            val newContent = if (match != null) {
                content.replaceRange(match.range, match.groupValues[1] + newVersion + match.groupValues[3])
            } else {
                content
            }
            if (newContent != content) {
                writeTextAndRefresh(mainJs, newContent)
                versionCache.remove("webProj:$webPath")
                log.info("Web project version updated to $newVersion in ${mainJs.path}")
            }
            true
        } catch (e: Exception) {
            log.warn("Failed to write web project version", e)
            false
        }
    }

    /**
     * 更新 PDA 端 src/main.js 中 appUtils.pro_version 的值。
     * @return true 写入成功，false 失败
     */
    fun setPdaProjectVersion(pdaPath: String, newVersion: String): Boolean {
        if (pdaPath.isBlank() || newVersion.isBlank()) return false
        return try {
            val mainJs = File(pdaPath, "src/main.js")
            if (!mainJs.isFile) return false
            val content = mainJs.readText()
            val regex = PRO_VERSION_SET_REGEX
            val match = regex.find(content)
            val newContent = if (match != null) {
                content.replaceRange(match.range, match.groupValues[1] + newVersion + match.groupValues[3])
            } else {
                content
            }
            if (newContent != content) {
                writeTextAndRefresh(mainJs, newContent)
                versionCache.remove("pdaProj:$pdaPath")
                log.info("PDA project version updated to $newVersion in ${mainJs.path}")
            }
            true
        } catch (e: Exception) {
            log.warn("Failed to write pda project version", e)
            false
        }
    }

    // ========== 内部工具方法 ==========

    /**
     * 从 package.json 的 dependencies 中提取所有 file 类型依赖的版本号。
     * 匹配 "ims-fa-core": "file:./lib/ims-fa-core-6.7.4.tgz" 格式，
     * 返回 (包名, 版本号) 列表。
     */
    private fun extractAllPlatformVersions(packageJson: File): List<Pair<String, String>> {
        val content = packageJson.readText()
        val regex = PACKAGE_JSON_FILE_REF_REGEX
        return regex.findAll(content).map { match ->
            match.groupValues[1] to match.groupValues[2]
        }.toList()
    }

    /**
     * 从多个包的版本列表中解析统一的平台版本。
     * 所有版本一致时返回该版本号，不一致或为空时返回空字符串。
     */
    private fun resolvePlatformVersion(allVersions: List<Pair<String, String>>): String {
        if (allVersions.isEmpty()) return ""
        val distinct = allVersions.map { it.second }.distinct()
        return if (distinct.size == 1) distinct.first() else ""
    }

    /**
     * 构建版本不一致警告消息。
     * @return 不一致时返回 ValidationMessage，一致或为空时返回 null
     */
    private fun buildConsistencyWarning(allVersions: List<Pair<String, String>>, moduleName: String): ValidationMessage? {
        val distinct = allVersions.map { it.second }.distinct()
        if (distinct.size <= 1) return null
        val groups = allVersions.groupBy { it.second }
        val maxSize = groups.maxOf { it.value.size }
        val commonVersion = groups.filterValues { it.size == maxSize }.keys.singleOrNull()
        val detail = allVersions.joinToString("<br>") { (name, ver) ->
            val label = "&nbsp;&nbsp;$name&nbsp;&nbsp;"
            if (commonVersion != null && ver != commonVersion) {
                "$label<b>$ver</b>"
            } else {
                "$label$ver"
            }
        }
        val recommendation = if (commonVersion != null) {
            ImsBundle.message("validate.version.recommend.target", commonVersion)
        } else {
            ImsBundle.message("validate.version.recommend.manual")
        }
        return ValidationMessage(
            ImsBundle.message("validate.version.inconsistent.title", moduleName),
            "<html>" + ImsBundle.message("validate.version.inconsistent.message", moduleName) +
                "<br>" + detail + "<br>" + recommendation + "</html>"
        )
    }

    /** 从 main.js 中解析 appUtils.pro_version 的值 */
    private fun parseProVersionFromMainJs(mainJs: File): String {
        val content = mainJs.readText()
        val regex = PRO_VERSION_GET_REGEX
        return regex.find(content)?.groupValues?.get(1) ?: ""
    }

    /** 从 Java 源码中解析 getVersion() 方法的返回字符串 */
    private fun parseGetVersionFromSource(file: File): String {
        return try {
            val content = file.readText()
            // 精确匹配 getVersion() 方法体，取最后一个 return "版本号"
            val methodRegex = GET_VERSION_BLOCK_REGEX
            val methodBody = methodRegex.find(content)?.groupValues?.get(1) ?: return ""
            val returnRegex = RETURN_VERSION_REGEX
            returnRegex.findAll(methodBody).lastOrNull()?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            log.debug("Failed to resolve version", e)
            ""
        }
    }

    /** 从编译产物中通过 classloading 读取 Configure.getVersion()（排除 AppConfigure） */
    private fun tryLoadConfigureVersion(serverDir: File): String {
        return try {
            val urls = mutableListOf<URL>()
            val targetDir = File(serverDir, "target/classes")
            if (targetDir.isDirectory) urls.add(targetDir.toURI().toURL())
            val libDir = File(serverDir, "lib")
            if (libDir.isDirectory) {
                libDir.listFiles()?.filter { it.name.endsWith(".jar") }?.forEach { urls.add(it.toURI().toURL()) }
            }
            if (urls.isEmpty()) return ""

            // 在 target/classes 下定位 util 包中的 *Configure.class（排除 AppConfigure）
            val classFile = targetDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith("Configure.class") && it.name != "AppConfigure.class" }
                .firstOrNull { it.parentFile?.name.equals("util", ignoreCase = true) }
                ?: return ""

            val relPath = classFile.relativeTo(targetDir).path
            val fqcn = relPath.removeSuffix(".class").replace(File.separatorChar, '.')
            // 同 readPlatformVersionFromLibDir：URLClassLoader 必须 use{} 关闭，
            // 否则会锁住 lib 下的 jar，导致后续平台升级无法替换 lib。
            URLClassLoader(urls.toTypedArray(), javaClass.classLoader).use { classLoader ->
                val clazz = classLoader.loadClass(fqcn)

                // 先尝试静态方法
                try {
                    val method = clazz.getMethod("getVersion")
                    val version = method.invoke(null) as? String
                    if (!version.isNullOrBlank()) return version
                } catch (_: NoSuchMethodException) { /* fall through */ }

                // 再尝试实例方法（无参构造）
                try {
                    val instance = clazz.getDeclaredConstructor().newInstance()
                    val method = clazz.getMethod("getVersion")
                    val version = method.invoke(instance) as? String ?: ""
                    return version
                } catch (_: Exception) { "" }
            }
        } catch (e: Exception) {
            log.debug("Failed to resolve version", e)
            ""
        }
    }

    private fun isValidDirectory(path: String): Boolean {
        if (path.isBlank()) return false
        return File(path).isDirectory
    }
}
