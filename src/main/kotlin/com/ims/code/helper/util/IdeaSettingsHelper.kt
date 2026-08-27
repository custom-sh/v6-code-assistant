package com.ims.code.helper.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.LanguageLevelModuleExtension
import com.intellij.openapi.roots.LanguageLevelProjectExtension
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.encoding.EncodingManager
import com.intellij.openapi.vfs.encoding.EncodingProjectManager
import com.intellij.pom.java.LanguageLevel
import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.CodeInsightWorkspaceSettings
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.charset.Charset

/**
 * IDEA 原生设置读写工具
 * @author shenwl
 * @date 2026/07/12
 */
object IdeaSettingsHelper {

    private val JDK_VERSION_REGEX = Regex("""(\d+)\.(\d+)""")
    private val LOG = Logger.getInstance(IdeaSettingsHelper::class.java)

    // ═══════════════════════════════════════════════════════════
    // Java 文件自动软换行
    //
    // 勾选即镜像 IDEA 原生两项设置（editor.xml）：
    //   USE_SOFT_WRAPS  —— 软换行总开关（开启 MAIN_EDITOR，等价于勾选 "Soft-wrap these files"）
    //   SOFT_WRAP_FILE_MASKS —— 启用软换行的文件名掩码，分号分隔，追加 *.java
    //
    // 读：复用 setUseSoftWraps(true) 的自愈语义——先开总开关，再读 masks，
    //     能把 null 字段自动落为真实字符串，避免 getSoftWrapFileMasks() 返回默认值。
    // 写：直接写字段，保证 *.java 追加进去并持久化。
    // ═══════════════════════════════════════════════════════════

    /**
     * *.java 软换行是否真正生效：总开关开启且 mask 含 *.java。
     */
    fun isJavaSoftWrapEnabled(): Boolean {
        val settings = getEditorSettings()
        // 仅当总开关开启时，masks 才有意义；避免误读默认值。
        if (!settings.isUseSoftWraps) return false
        return getMasksSafe(settings).any { it == "*.java" }
    }

    /**
     * 勾选：开启软换行总开关（MAIN_EDITOR）并在 mask 追加 *.java；
     * 取消：仅从 mask 移除 *.java（不动总开关，避免关掉用户对其他文件类型的软换行）。
     * 在 write action 内执行，避免在设置对话框 apply 等 Write-unsafe 上下文触发断言。
     */
    fun setJavaSoftWrapEnabled(enabled: Boolean) {
        if (isJavaSoftWrapEnabled() == enabled) return
        WriteAction.run<RuntimeException> {
            val settings = getEditorSettings()
            if (enabled) {
                // 先开总开关：setUseSoftWraps(true) 会把 null 的 masks 字段落为真实字符串，
                // 之后 getSoftWrapFileMasks() 才返回存储值而非默认值。
                settings.isUseSoftWraps = true
            }
            val items = getMasksSafe(settings).toMutableList()
            if (enabled) {
                if ("*.java" !in items) items.add("*.java")
            } else {
                items.remove("*.java")
            }
            settings.softWrapFileMasks = items.joinToString("; ")
        }
    }

    private fun getEditorSettings(): EditorSettingsExternalizable =
        ApplicationManager.getApplication().getService(EditorSettingsExternalizable::class.java)

    /**
     * 读取软换行 mask 列表。openOptions 为 null 时走默认值（与原生 UI 行为一致）。
     */
    private fun getMasksSafe(settings: EditorSettingsExternalizable): List<String> {
        return settings.softWrapFileMasks.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ═══════════════════════════════════════════════════════════
    // Formatter on/off 标记（Editor > Code Style > Formatter）
    // ═══════════════════════════════════════════════════════════

    fun isFormatterTagsEnabled(project: Project): Boolean {
        return CodeStyle.getSettings(project).FORMATTER_TAGS_ENABLED
    }

    fun setFormatterTagsEnabled(project: Project, enabled: Boolean) {
        if (isFormatterTagsEnabled(project) == enabled) return
        // 项目 Code Style 设置修改会自动触发 IDEA 持久化；写操作需在 write action 内
        // 以避免设置对话框 apply 等 Write-unsafe 上下文触发断言。
        WriteAction.run<RuntimeException> {
            CodeStyle.getSettings(project).FORMATTER_TAGS_ENABLED = enabled
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Optimize imports on the fly（Editor > General > Auto Import）
    // ═══════════════════════════════════════════════════════════

    fun isOptimizeImportsOnTheFlyEnabled(project: Project): Boolean {
        return CodeInsightWorkspaceSettings.getInstance(project).isOptimizeImportsOnTheFly
    }

    fun setOptimizeImportsOnTheFlyEnabled(project: Project, enabled: Boolean) {
        if (isOptimizeImportsOnTheFlyEnabled(project) == enabled) return
        // Write-unsafe 上下文（设置对话框 apply）下写入会触发断言，统一包 WriteAction
        WriteAction.run<RuntimeException> {
            CodeInsightWorkspaceSettings.getInstance(project).isOptimizeImportsOnTheFly = enabled
        }
    }

    // ═══════════════════════════════════════════════════════════
    // File Encodings（Editor > File Encodings）
    // 同步修改 Global Encoding、Project Encoding、Properties Files Encoding
    // ═══════════════════════════════════════════════════════════

    private val COMMON_CHARSETS = listOf(
        "", "UTF-8", "ISO-8859-1", "GBK", "GB2312", "GB18030",
        "US-ASCII", "windows-1252", "Shift_JIS", "EUC-JP", "EUC-KR"
    )

    fun getEncodingOptions(): List<String> = COMMON_CHARSETS

    fun getGlobalEncoding(): String {
        return try {
            EncodingManager.getInstance().defaultCharsetName.ifBlank { "UTF-8" }
        } catch (_: Exception) {
            "UTF-8"
        }
    }

    fun setGlobalEncoding(encoding: String) {
        try {
            if (encoding.isNotBlank() && Charset.isSupported(encoding)) {
                if (sameCharset(getGlobalEncoding(), encoding)) return
                WriteAction.run<RuntimeException> {
                    EncodingManager.getInstance().defaultCharsetName = encoding
                }
            }
        } catch (_: Exception) { /* ignore */ }
    }

    fun getProjectEncoding(project: Project): String {
        return try {
            EncodingProjectManager.getInstance(project).defaultCharsetName.ifBlank { "UTF-8" }
        } catch (_: Exception) {
            "UTF-8"
        }
    }

    fun setProjectEncoding(project: Project, encoding: String) {
        try {
            if (encoding.isNotBlank() && Charset.isSupported(encoding)) {
                if (sameCharset(getProjectEncoding(project), encoding)) return
                WriteAction.run<RuntimeException> {
                    EncodingProjectManager.getInstance(project).defaultCharsetName = encoding
                }
            }
        } catch (_: Exception) { /* ignore */ }
    }

    fun getPropertiesEncoding(): String {
        return try {
            EncodingManager.getInstance().getDefaultCharsetForPropertiesFiles(null)?.name()?.ifBlank { "UTF-8" } ?: "UTF-8"
        } catch (_: Exception) {
            "UTF-8"
        }
    }

    fun setPropertiesEncoding(encoding: String) {
        try {
            if (encoding.isNotBlank() && Charset.isSupported(encoding)) {
                if (sameCharset(getPropertiesEncoding(), encoding)) return
                WriteAction.run<RuntimeException> {
                    EncodingManager.getInstance().setDefaultCharsetForPropertiesFiles(null, Charset.forName(encoding))
                }
            }
        } catch (_: Exception) { /* ignore */ }
    }

    // ═══════════════════════════════════════════════════════════
    // Maven 配置（Build Tools > Maven）
    // 通过反射访问 MavenGeneralSettings，避免直接引用内部 API
    //（MavenGeneralSettings 属于 intellij.maven 内部实现，
    //  直接调用会导致 Marketplace 审核被拒）。
    // ═══════════════════════════════════════════════════════════

    /** 反射获取 MavenGeneralSettings 对象，避免静态引用内部 API */
    private fun getMavenGeneralSettings(project: Project): Any? {
        return try {
            val manager = MavenProjectsManager.getInstance(project)
            // 使用公开基类查找方法，避免运行时实现类不可访问导致反射失败。
            MavenProjectsManager::class.java.getMethod("getGeneralSettings").invoke(manager)
        } catch (e: Exception) {
            LOG.debug("Unable to read Maven general settings", e)
            null
        }
    }

    private fun invokeMavenGetter(settings: Any, getter: String): Any? {
        return try {
            settings.javaClass.getMethod(getter).invoke(settings)
        } catch (e: Exception) {
            LOG.debug("Unable to invoke Maven getter $getter", e)
            null
        }
    }

    private fun mavenPath(value: Any?): String = when (value) {
        is java.io.File -> value.absolutePath
        is String -> value.trim()
        else -> ""
    }

    /**
     * 读取 Maven 配置：先取显式覆盖值，空值或读取失败时回退到 IDEA 计算出的有效值。
     */
    private fun readMavenPath(project: Project, getter: String, effectiveGetter: String): String {
        val settings = getMavenGeneralSettings(project) ?: return ""
        val configured = mavenPath(invokeMavenGetter(settings, getter))
        if (configured.isNotBlank()) return configured
        return mavenPath(invokeMavenGetter(settings, effectiveGetter))
    }

    /** 通过反射调用 MavenGeneralSettings 的 getter/setter */
    private fun invokeMavenSettings(
        project: Project,
        getter: String,
        setter: String? = null,
        value: String? = null
    ): String {
        val settings = getMavenGeneralSettings(project) ?: return ""
        if (setter == null || value == null) {
            return mavenPath(invokeMavenGetter(settings, getter))
        }

        val currentValue = mavenPath(invokeMavenGetter(settings, getter))
        if (currentValue == value) return value
        return try {
            // Maven 设置写入触发导入器，必须在 WriteAction 内，
            // 否则从后台线程 apply 会 assert 或与 Maven 导入线程竞态。
            WriteAction.runAndWait<RuntimeException> {
                settings.javaClass.getMethod(setter, String::class.java).invoke(settings, value)
            }
            value
        } catch (e: Exception) {
            LOG.debug("Unable to invoke Maven setter $setter", e)
            ""
        }
    }

    fun getMavenHome(project: Project): String {
        return readMavenPath(project, "getMavenHome", "getEffectiveMavenHome")
    }

    fun setMavenHome(project: Project, path: String) {
        invokeMavenSettings(project, "getMavenHome", "setMavenHome", path)
    }

    fun getMavenUserSettings(project: Project): String {
        return readMavenPath(project, "getUserSettingsFile", "getEffectiveUserSettingsIoFile")
    }

    fun setMavenUserSettings(project: Project, path: String) {
        invokeMavenSettings(project, "getUserSettingsFile", "setUserSettingsFile", path)
    }

    fun getMavenLocalRepository(project: Project): String {
        return readMavenPath(project, "getLocalRepository", "getEffectiveLocalRepository")
    }

    fun setMavenLocalRepository(project: Project, path: String) {
        invokeMavenSettings(project, "getLocalRepository", "setLocalRepository", path)
    }

    // ═══════════════════════════════════════════════════════════
    // Project Structure — 选中 JDK 后自动同步 Project SDK、
    // Module SDK 以及 Language Level（从 JDK 版本号推导）
    // ═══════════════════════════════════════════════════════════

    fun getAvailableJdkNames(): List<String> {
        val allJdks = try {
            ProjectJdkTable.getInstance().allJdks.toList()
        } catch (_: Exception) { emptyList() }
        return listOf("") + allJdks.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
    }

    fun getProjectSdkName(project: Project): String {
        return try {
            ProjectRootManager.getInstance(project).projectSdk?.name.orEmpty()
        } catch (_: Exception) { "" }
    }

    /**
     * 一次性设置：Project SDK → 所有 Module 的 SDK → 根据 JDK 版本推导 Language Level
     */
    fun applyProjectSdk(project: Project, sdkName: String) {
        if (sdkName.isBlank()) return
        if (getProjectSdkName(project) == sdkName) return
        val sdk = findJdkByName(sdkName) ?: return
        val languageLevel = deriveLanguageLevel(sdk)
        try {
            WriteAction.run<RuntimeException> {
                // 1. 设置 Project SDK
                ProjectRootManager.getInstance(project).projectSdk = sdk
                // 2. 设置所有 Module 的 SDK
                for (module in ModuleManager.getInstance(project).modules) {
                    try {
                        ModuleRootModificationUtil.setModuleSdk(module, sdk)
                    } catch (_: Exception) { /* skip */ }
                }
                // 3. 设置 Language Level（项目级 + 所有模块）
                if (languageLevel != null) {
                    LanguageLevelProjectExtension.getInstance(project).languageLevel = languageLevel
                    for (module in ModuleManager.getInstance(project).modules) {
                        try {
                            ModuleRootModificationUtil.updateModel(module) { model ->
                                model.getModuleExtension(LanguageLevelModuleExtension::class.java)?.languageLevel = languageLevel
                            }
                        } catch (_: Exception) { /* skip */ }
                    }
                }
            }
        } catch (_: Exception) { /* ignore */ }
    }

    // --- private helpers ---

    private fun sameCharset(current: String, requested: String): Boolean =
        runCatching { Charset.forName(current) == Charset.forName(requested) }.getOrDefault(false)

    private fun findJdkByName(name: String): Sdk? {
        if (name.isBlank()) return null
        return try {
            ProjectJdkTable.getInstance().allJdks.firstOrNull { it.name == name }
        } catch (_: Exception) { null }
    }

    /** 从 JDK 版本字符串提取主版本号并映射到 LanguageLevel */
    private fun deriveLanguageLevel(sdk: Sdk): LanguageLevel? {
        val versionString = sdk.versionString ?: return null
        // 兼容 "1.8.0_202"（Java ≤8）和 "17.0.1"（Java ≥9）两种格式
        val match = JDK_VERSION_REGEX.find(versionString) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val second = match.groupValues[2].toIntOrNull() ?: return null
        // Java ≤8 版本号格式 "1.X" → 主版本号为 X
        val majorVersion = if (first == 1) second else first
        val level = when {
            majorVersion <= 8 -> "JDK_1_$majorVersion"
            majorVersion <= 21 -> "JDK_$majorVersion"
            else -> "JDK_X"
        }
        return try {
            LanguageLevel.parse(level)
        } catch (_: Exception) { null }
    }
}
