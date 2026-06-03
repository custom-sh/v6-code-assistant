package com.ims.code.helper.generator

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.RenameProcessor
import com.ims.code.helper.config.ImsProjectSettings
import com.ims.code.helper.util.ImsBundle
import com.ims.code.helper.util.NotificationHelper

/**
 * 项目初始化器
 * 统一扫描 + 规则驱动：将模板项目中的 Demo/demo/DEMO 占位符替换为项目编码的对应大小写形式
 *
 * 处理顺序：
 * 1. 重命名包 demo → {projectCode}（RenameProcessor，自动更新所有 import 和引用）
 * 2. 重命名 Java 类 Demo* → {Code}*（RenameProcessor，自动更新所有引用）
 * 3. 文本替换剩余的 demo/Demo/DEMO（字符串、注释等前两步未覆盖的部分）
 */
class ProjectInitializer(private val project: Project) {

    private val log = Logger.getInstance(ProjectInitializer::class.java)

    /**
     * 初始化项目入口。
     *
     * 两个平台约束必须同时满足，否则 RenameProcessor 会失败/断言：
     * 1. 重构不能在写操作内启动——它自带写操作；旧实现把 RenameProcessor 包在
     *    WriteCommandAction 里，run() 会抛 IllegalStateException 被吞掉，包名因此从未真正改过。
     * 2. 重构必须在「写安全上下文」执行——本方法由配置面板按钮的 **原始 Swing 监听器** 触发，
     *    那不是写安全上下文，直接跑会触发 "Access is allowed from write-safe contexts only"。
     *    用 invokeLater 调度即可获得写安全上下文。
     */
    fun initializeProject(settings: ImsProjectSettings): Boolean {
        val code = settings.projectCode.trim()
        if (code.isBlank() || !code.all { it.isLetterOrDigit() }) {
            NotificationHelper.error(project,
                ImsBundle.message("init.error.title"),
                ImsBundle.message("init.error.invalid.code"))
            return false
        }

        val modules = buildModuleList(settings)
        if (modules.isEmpty()) {
            NotificationHelper.error(project,
                ImsBundle.message("init.error.title"),
                ImsBundle.message("init.error.no.valid.paths"))
            return false
        }

        val variants = deriveCaseVariants(code)
        val projectName = settings.projectName.trim()

        // 在写安全上下文中执行 PSI 重构（见方法注释第 2 点）
        ApplicationManager.getApplication().invokeLater({
            runInitialization(modules, variants, projectName)
        }, ModalityState.current())

        return true
    }

    /**
     * 实际的初始化流程，运行在写安全上下文（invokeLater 回调）中。
     * 重构（Step 1/2）必须在写操作 **之外** 执行；只有纯文本替换（Step 3）才放进 WriteCommandAction。
     *
     * 防污染保护：包/类重命名一旦失败立即抛出异常中止——绝不在重构未完成时跑文本替换，
     * 否则会把模板改成「内容已替换、目录/类名没改」的夹生状态。
     */
    private fun runInitialization(modules: List<Pair<ModuleType, VirtualFile>>, variants: CaseVariants, projectName: String) {
        val moduleDirs = modules.map { it.second }.toTypedArray()

        // 初始刷新文件系统
        VfsUtil.markDirtyAndRefresh(false, true, true, *moduleDirs)

        // 前置检测：扫描所有文件，检查是否还有 demo/Demo/DEMO 占位符
        val hasPlaceholders = modules.any { (_, dir) -> hasAnyPlaceholder(dir) }
        if (!hasPlaceholders) {
            NotificationHelper.info(project,
                ImsBundle.message("init.success.title"),
                ImsBundle.message("init.already.initialized"))
            return
        }

        try {
            // Step 1: 重命名包 demo → {projectCode}（重构，不可包裹写操作）
            val packageCount = renameDemoPackages(modules, variants)
            // 包重命名移动了目录，同步刷新 VFS 再继续
            VfsUtil.markDirtyAndRefresh(false, true, true, *moduleDirs)

            // Step 2: 重命名类 Demo* → {Code}*（重构，不可包裹写操作；会连带重命名 .java 文件）
            var classCount = 0
            for ((_, dir) in modules) {
                for (javaFile in collectFiles(dir).javaFiles) {
                    if (renameDemoClass(javaFile, variants)) classCount++
                }
            }
            VfsUtil.markDirtyAndRefresh(false, true, true, *moduleDirs)

            // Step 2.5: 自愈修复——类名已改但文件名未改的夹生状态（上次中断导致）
            var healCount = 0
            for ((_, dir) in modules) {
                for (javaFile in collectFiles(dir).javaFiles) {
                    if (healFileName(javaFile)) healCount++
                }
            }
            if (healCount > 0) {
                VfsUtil.markDirtyAndRefresh(false, true, true, *moduleDirs)
            }

            // Step 3: 文本替换兜底（注释/字符串/配置）+ 配置文件重命名（普通文档编辑，必须在写操作中执行）
            var textReplaceCount = 0
            WriteCommandAction.runWriteCommandAction(project, ImsBundle.message("init.progress.title"), null, {
                for ((type, dir) in modules) {
                    val collected = collectFiles(dir)
                    if (collected.pomXml?.let { replaceTextInFile(it, variants) } == true) textReplaceCount++
                    for (javaFile in collected.javaFiles) {
                        if (replaceTextInFile(javaFile, variants)) textReplaceCount++
                        // Server 模块的 ColumnType.java 需要额外处理 SystemName 内部类
                        if (type == ModuleType.SERVER && javaFile.name.endsWith("ColumnType.java")) {
                            if (processColumnTypeSystemName(javaFile, projectName)) textReplaceCount++
                        }
                    }
                    for (configFile in collected.configFiles) {
                        if (replaceTextInFile(configFile, variants)) textReplaceCount++
                        renameFileIfNeeded(configFile, variants)
                    }
                }
            })

            val totalCount = packageCount + classCount + healCount + textReplaceCount
            if (totalCount == 0) {
                NotificationHelper.info(project,
                    ImsBundle.message("init.success.title"),
                    ImsBundle.message("init.already.initialized"))
            } else {
                NotificationHelper.info(project,
                    ImsBundle.message("init.success.title"),
                    ImsBundle.message("init.success.message", totalCount))
            }
        } catch (e: InitAbortException) {
            log.error("Project initialization aborted: ${e.message}", e.cause)
            NotificationHelper.error(project,
                ImsBundle.message("init.error.title"),
                e.message ?: ImsBundle.message("init.error.unknown"))
        } catch (e: Exception) {
            log.error("Project initialization failed", e)
            NotificationHelper.error(project,
                ImsBundle.message("init.error.title"),
                e.message ?: ImsBundle.message("init.error.unknown"))
        }
    }

    /**
     * 检测目录中是否还有 demo/Demo/DEMO 占位符（用于前置检测，判断是否需要初始化）
     */
    private fun hasAnyPlaceholder(dir: VirtualFile): Boolean {
        var found = false
        VfsUtil.visitChildrenRecursively(dir, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (found) return false // 已找到，提前终止
                if (file.isDirectory) {
                    if (file.name.equals("demo", ignoreCase = true)) {
                        found = true
                        return false
                    }
                    return file.name !in SKIP_DIRS
                }
                // 检查文件名
                if (file.name.contains("demo", ignoreCase = true)) {
                    found = true
                    return false
                }
                // 检查文件内容
                val documentManager = FileDocumentManager.getInstance()
                val document = documentManager.getDocument(file)
                if (document != null) {
                    val text = document.text
                    if (text.contains("demo") || text.contains("Demo") || text.contains("DEMO")) {
                        found = true
                        return false
                    }
                }
                return true
            }
        })
        return found
    }

    // ========== 包重命名 ==========

    /**
     * 重命名包 demo → {projectCode}
     * 使用 RenameProcessor 对 PsiPackage 重命名，效果等同于 IDEA 的 Refactor → Rename Package
     *
     * 防污染保护：任何包重命名失败立即抛出 InitAbortException 中止，
     * 绝不让后续文本替换在目录/类名未改的夹生状态上执行。
     *
     * @return 实际重命名的包数量
     */
    private fun renameDemoPackages(modules: List<Pair<ModuleType, VirtualFile>>, variants: CaseVariants): Int {
        val psiFacade = JavaPsiFacade.getInstance(project)

        // 从已收集的 Java 文件中提取所有包含 "demo" 段的包名
        val packageNames = mutableSetOf<String>()
        for ((_, dir) in modules) {
            collectPackageNames(dir, packageNames)
        }

        // 过滤出以 .demo 结尾或就是 demo 的包，按深度从深到浅排序（先改子包再改父包）
        val demoPackages = packageNames
            .filter { it == "demo" || it.endsWith(".demo") }
            .sortedByDescending { it.count { c -> c == '.' } }
            .toSet()

        log.info("Found demo packages to rename: $demoPackages")

        var renamedCount = 0
        for (qualifiedName in demoPackages) {
            val pkg = psiFacade.findPackage(qualifiedName)
            if (pkg != null) {
                val newQualifiedName = buildNewPackageQualifiedName(qualifiedName, variants.lowercase)
                try {
                    // searchInComments/searchInNonJavaFiles 必须为 false：
                    // 一旦在注释/字符串/非 Java 文件里搜到「非代码引用」，平台会强制弹出
                    // Refactoring Preview（非模态，需手动点 Do Refactor），run() 会提前返回、重命名卡住不执行。
                    // 关掉它们即可让 run() 同步执行完；注释/字符串/配置里的 demo 由 Step 3 文本替换兜底。
                    // 代码引用（import、类型引用）无论这两个开关如何都会被自动更新。
                    val processor = RenameProcessor(
                        project,
                        pkg,
                        newQualifiedName,
                        false,  // searchInComments
                        false   // searchInNonJavaFiles
                    )
                    processor.setPreviewUsages(false)
                    processor.run()
                    log.info("Renamed package $qualifiedName -> $newQualifiedName")
                    renamedCount++
                } catch (e: Exception) {
                    // 防污染：包重命名失败 → 立即中止，不继续跑文本替换
                    throw InitAbortException("包重命名失败: $qualifiedName → $newQualifiedName", e)
                }
            } else {
                // findPackage 返回 null：该路径不在当前打开工程的源码根内，PSI 无法解析
                log.warn("Could not find PsiPackage for $qualifiedName")
            }
        }
        return renamedCount
    }

    /**
     * 递归扫描目录，收集所有 Java 文件的包名
     */
    private fun collectPackageNames(dir: VirtualFile, packageNames: MutableSet<String>) {
        val psiManager = PsiManager.getInstance(project)
        VfsUtil.visitChildrenRecursively(dir, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) {
                    return file.name !in SKIP_DIRS
                }
                if (file.extension == "java") {
                    val psiFile = psiManager.findFile(file) as? PsiJavaFile
                    val pkgName = psiFile?.packageName
                    if (!pkgName.isNullOrBlank()) {
                        var name: String = pkgName
                        while (name.contains(".")) {
                            packageNames.add(name)
                            name = name.substringBeforeLast(".")
                        }
                        packageNames.add(name)
                    }
                }
                return true
            }
        })
    }

    /**
     * 构建新的包全限定名
     * e.g. com.demo → com.sys, com.demo.controller → com.sys.controller
     */
    private fun buildNewPackageQualifiedName(qualifiedName: String, newSimpleName: String): String {
        val lastDot = qualifiedName.lastIndexOf('.')
        return if (lastDot > 0) {
            qualifiedName.substring(0, lastDot + 1) + newSimpleName
        } else {
            newSimpleName
        }
    }

    // ========== Java 类重命名 ==========

    /**
     * 重命名以 Demo 开头的顶层类 Demo* → {Code}*（自动更新所有引用，并连带重命名 .java 文件）。
     * 必须在写操作之外调用——RenameProcessor 会自行开启写操作。
     *
     * 防污染保护：类重命名失败立即抛出 InitAbortException 中止。
     */
    private fun renameDemoClass(javaVFile: VirtualFile, variants: CaseVariants): Boolean {
        val psiManager = PsiManager.getInstance(project)
        val psiFile = psiManager.findFile(javaVFile) as? PsiJavaFile
        val psiClass = psiFile?.classes?.firstOrNull() ?: return false

        val oldClassName = psiClass.name ?: return false
        if (!oldClassName.startsWith("Demo")) return false

        val newClassName = variants.pascal + oldClassName.removePrefix("Demo")
        try {
            // 同包重命名：关掉 searchInComments/searchInNonJavaFiles，避免强制弹出 Refactoring Preview（见包重命名处注释）
            val processor = RenameProcessor(
                project,
                psiClass,
                newClassName,
                false,  // searchInComments
                false   // searchInNonJavaFiles
            )
            processor.setPreviewUsages(false)
            processor.run()
            log.info("Renamed class $oldClassName -> $newClassName")
        } catch (e: Exception) {
            // 防污染：类重命名失败 → 立即中止
            throw InitAbortException("类重命名失败: $oldClassName → $newClassName", e)
        }
        return true
    }

    /**
     * 自愈修复：检测 Java 文件名与内部首个顶层类名不一致时，将文件名改为与类名一致。
     * 典型场景：上次初始化跑到一半，RenameProcessor 已把类名从 DemoXxx 改为 XxxXxx，
     * 但文件名没跟着改，导致编译报错「public class XxxXxx is defined in file DemoXxx.java」。
     *
     * @return true 表示实际修复了文件名，false 表示无需修复
     */
    private fun healFileName(javaVFile: VirtualFile): Boolean {
        val fileName = javaVFile.nameWithoutExtension
        val psiManager = PsiManager.getInstance(project)
        val psiFile = psiManager.findFile(javaVFile) as? PsiJavaFile ?: return false
        val className = psiFile.classes.firstOrNull()?.name ?: return false

        // 文件名已与类名一致，无需修复
        if (fileName == className) return false

        val newName = "$className.java"
        try {
            javaVFile.rename(this, newName)
            log.info("Healed file name: $fileName.java -> $newName")
        } catch (e: Exception) {
            throw InitAbortException("文件名修复失败: $fileName.java → $newName", e)
        }
        return true
    }

    // ========== 配置文件处理 ==========

    private fun renameFileIfNeeded(virtualFile: VirtualFile, variants: CaseVariants) {
        val oldName = virtualFile.name
        val newName = oldName
            .replace("DEMO", variants.upper)
            .replace("Demo", variants.pascal)
            .replace("demo", variants.lowercase)
        if (oldName != newName) {
            try {
                virtualFile.rename(this, newName)
            } catch (e: Exception) {
                // 防污染：文件重命名失败 → 立即中止
                throw InitAbortException("文件重命名失败: $oldName → $newName", e)
            }
        }
    }

    // ========== 通用文本替换 ==========

    /**
     * 替换文件中的 DEMO/Demo/demo 占位符。
     * @return true 表示实际替换了内容，false 表示文件无需替换
     */
    private fun replaceTextInFile(virtualFile: VirtualFile, variants: CaseVariants): Boolean {
        val documentManager = FileDocumentManager.getInstance()
        val document = documentManager.getDocument(virtualFile) ?: return false

        val text = document.text
        val newText = text
            .replace("DEMO", variants.upper)
            .replace("Demo", variants.pascal)
            .replace("demo", variants.lowercase)
        if (text != newText) {
            document.setText(newText)
            documentManager.saveDocument(document)
            return true
        }
        return false
    }

    /**
     * 处理 ColumnType.java 中的 SystemName 内部类（使用 PSI AST，精准定位类和字段边界）：
     * - 如果 SystemName 内部类存在且包含 CUSTOM 字段，将 CUSTOM 的值替换为 projectName
     * - 如果 SystemName 内部类不存在，在 SystemCode 内部类下方插入 SystemName 内部类
     *
     * @return true 表示实际修改了文件，false 表示无需修改
     */
    private fun processColumnTypeSystemName(virtualFile: VirtualFile, projectName: String): Boolean {
        if (projectName.isBlank()) return false

        val psiManager = PsiManager.getInstance(project)
        val psiFile = psiManager.findFile(virtualFile) as? PsiJavaFile ?: return false
        val topLevelClass = psiFile.classes.firstOrNull() ?: return false

        // 在顶层类中查找 SystemName 和 SystemCode 内部类
        val systemNameInner = topLevelClass.innerClasses.firstOrNull { it.name == "SystemName" }
        val systemCodeInner = topLevelClass.innerClasses.firstOrNull { it.name == "SystemCode" }

        if (systemNameInner != null) {
            // SystemName 已存在，替换 CUSTOM 字段值
            val customField = systemNameInner.fields.firstOrNull { it.name == "CUSTOM" }
            if (customField != null) {
                val currentInitializer = customField.initializer?.text
                val newInitializer = "\"$projectName\""
                if (currentInitializer != newInitializer) {
                    val initializerElement = customField.initializer ?: return false
                    val factory = JavaPsiFacade.getElementFactory(project)
                    initializerElement.replace(factory.createExpressionFromText(newInitializer, psiFile))
                    return true
                }
            }
            return false
        }

        // SystemName 不存在，在 SystemCode 内部类后插入
        if (systemCodeInner != null) {
            val factory = JavaPsiFacade.getElementFactory(project)
            val systemNameClassText = """
                public static class SystemName extends CoreColumnType.SystemName {
                    /**
                     * {@value} - 项目名称
                     */
                    public static final String CUSTOM = "$projectName";
                }""".trimIndent()
            val systemNameClass = factory.createClassFromText(systemNameClassText, psiFile)
            // createClassFromText 返回的是带外层 PsiJavaFile 包装的，需要取 innerClass
            val innerClass = systemNameClass.innerClasses.firstOrNull { it.name == "SystemName" } ?: return false
            systemCodeInner.parent.addAfter(innerClass, systemCodeInner)
            return true
        }

        return false
    }

    // ========== 文件扫描 ==========

    private fun collectFiles(moduleDir: VirtualFile): CollectedFiles {
        var pomXml: VirtualFile? = null
        val javaFiles = mutableListOf<VirtualFile>()
        val configFiles = mutableListOf<VirtualFile>()

        VfsUtil.visitChildrenRecursively(moduleDir, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) {
                    return file.name !in SKIP_DIRS
                }
                when {
                    file.name == "pom.xml" -> pomXml = file
                    file.extension == "java" -> javaFiles.add(file)
                    file.extension in CONFIG_EXTENSIONS -> configFiles.add(file)
                }
                return true
            }
        })

        return CollectedFiles(pomXml, javaFiles, configFiles)
    }

    // ========== 辅助方法 ==========

    private fun buildModuleList(settings: ImsProjectSettings): List<Pair<ModuleType, VirtualFile>> {
        val result = mutableListOf<Pair<ModuleType, VirtualFile>>()
        val lfs = LocalFileSystem.getInstance()

        for (type in listOf(ModuleType.SERVER, ModuleType.WEB, ModuleType.PDA)) {
            val path = when (type) {
                ModuleType.SERVER -> settings.serverPath
                ModuleType.WEB -> settings.webPath
                ModuleType.PDA -> settings.pdaPath
            }
            if (path.isNotBlank()) {
                val vFile = lfs.refreshAndFindFileByPath(path)
                if (vFile != null && vFile.isDirectory) {
                    result.add(type to vFile)
                }
            }
        }
        return result
    }

    private fun deriveCaseVariants(projectCode: String): CaseVariants {
        return CaseVariants(
            lowercase = projectCode.lowercase(),
            pascal = projectCode.replaceFirstChar { it.uppercase() },
            upper = projectCode.uppercase()
        )
    }

    companion object {
        fun initializeProject(project: Project, settings: ImsProjectSettings): Boolean {
            return ProjectInitializer(project).initializeProject(settings)
        }

        private val SKIP_DIRS = setOf(
            "target", "build", "out", ".gradle", "node_modules", ".git", ".idea"
        )

        private val CONFIG_EXTENSIONS = setOf("xml", "yml", "yaml", "properties")
    }
}

data class CaseVariants(
    val lowercase: String,
    val pascal: String,
    val upper: String
)

data class CollectedFiles(
    val pomXml: VirtualFile?,
    val javaFiles: List<VirtualFile>,
    val configFiles: List<VirtualFile>
)

/**
 * 初始化中止异常：包/类/文件重命名失败时抛出，阻止后续文本替换在夹生状态上执行。
 */
class InitAbortException(message: String, cause: Throwable? = null) : Exception(message, cause)
