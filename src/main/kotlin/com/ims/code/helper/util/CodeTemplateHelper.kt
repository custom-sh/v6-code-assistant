package com.ims.code.helper.util

import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.project.Project
import com.ims.code.helper.config.ImsGlobalSettings
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 代码注释模板工具
 * @author shenwl
 * @date 2026/07/12
 */
object CodeTemplateHelper {

    /** IDEA 内置 File Header include 的名字。 */
    private const val FILE_HEADER = "File Header"

    /** 本插件写入内容的版本标记，用于识别"是否由本插件写入"，取消勾选时据此还原。 */
    private const val IMS_MARKER = "IMS-V6"

    /** 需要注入版权块的 Java 内置类型模板名（与 ideaIC 内置 .ft 一一对应）。 */
    private val CLASS_TYPES = listOf("Class", "Interface", "Enum", "Record", "AnnotationType")

    /** 各类型模板的类型声明行（与内置 .ft 完全一致，仅前面插了版权块 + 空行）。 */
    private fun typeDeclaration(name: String): String = when (name) {
        "Class" -> "public class \${NAME} {"
        "Interface" -> "public interface \${NAME} {"
        "Enum" -> "public enum \${NAME} {"
        "Record" -> "public record \${NAME}() {"
        "AnnotationType" -> "public @interface \${NAME} {"
        else -> "public class \${NAME} {"
    }

    /** 版权块（package 之上）。含 IMS_MARKER 以便取消勾选时识别。 */
    internal fun buildCopyrightBlock(year: String, dateStr: String, developerName: String): String = buildString {
        append("/*\n")
        append(" * $IMS_MARKER\n")
        append(" *\n")
        append(" * 版权所有 (c) $year-$year 广东盘古信息科技股份有限公司\n")
        append(" *\n")
        append(" * $dateStr 创建 - $developerName\n")
        append(" */")
    }

    /** 类 Javadoc（package 之下，写进 File Header，由 #parse 注入）。 */
    internal fun buildClassJavadoc(developerName: String, since: String): String = buildString {
        append("/**\n")
        append(" * \${DESCRIPTION}\n")
        append(" *\n")
        append(" * @author $developerName\n")
        append(" * @since V$since\n")
        append(" */")
    }

    /**
     * 应用模板：勾选时调用。
     * @param project 当前项目（File Template 为 project 级配置）
     * @param platformVersion 平台版本号（如 6.0.8），写入 @since Vxxx；为空则留 ${VERSION} 变量提示
     *
     * 两步：
     * 1. File Header（Includes，[FileTemplateManager.getPattern] 取）写入类 Javadoc——package 之后注入。
     * 2. Class/Interface/Enum/Record/AnnotationType（Internal，[FileTemplateManager.getInternalTemplate] 取）
     *    改为"版权块 + 空行 + package + #parse(File Header) + 类型声明"——版权块顶到 package 之上。
     * 末尾 [FileTemplateManager.saveAllTemplates] 立即落盘。
     */
    fun applyTemplate(project: Project, platformVersion: String) {
        val manager = FileTemplateManager.getInstance(project)
        val developerName = ImsGlobalSettings.getInstance().developerName.ifBlank { "\${USER}" }
        val today = LocalDate.now()
        val year = today.year.toString()
        val dateStr = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val since = if (platformVersion.isNotBlank()) platformVersion else "\${VERSION}"

        val copyright = buildCopyrightBlock(year, dateStr, developerName)
        val javadoc = buildClassJavadoc(developerName, since)

        // 1. File Header 写类 Javadoc（package 之后注入）
        manager.getPattern(FILE_HEADER)?.let { it.text = javadoc }

        // 2. 各内部模板：版权块在 package 之上，#parse 在 package 之后注入类 Javadoc
        for (name in CLASS_TYPES) {
            val template = manager.getInternalTemplate(name) ?: continue
            template.text = buildString {
                append(copyright)
                append("\n\n")
                append("#if (\${PACKAGE_NAME} && \${PACKAGE_NAME} != \"\")package \${PACKAGE_NAME};#end\n")
                append("#parse(\"File Header.java\")\n")
                append(typeDeclaration(name))
                append("\n}\n")
            }
        }

        manager.saveAllTemplates()
    }

    /**
     * 还原模板：取消勾选时调用。
     *
     * 仅当模板当前内容含本插件标记（IMS-V6）时才还原，避免抹掉用户手改。
     * File Header 与各内部模板均为内置（BundledFileTemplate），setText(null) 等价 revertToDefaults，
     * 回退到内置默认文本。注意：setText 在 Java 侧声明参数为可空（@Nullable String），但 Kotlin 把它当
     * non-null，故传 null 需强转。末尾 saveAllTemplates 立即落盘。
     */
    fun revertTemplate(project: Project) {
        val manager = FileTemplateManager.getInstance(project)
        var changed = false

        manager.getPattern(FILE_HEADER)?.let { template ->
            if (template.text?.contains(IMS_MARKER) == true) {
                @Suppress("UNCHECKED_CAST")
                (template as FileTemplate).setText(null as String?)
                changed = true
            }
        }
        for (name in CLASS_TYPES) {
            val template = manager.getInternalTemplate(name) ?: continue
            if (template.text?.contains(IMS_MARKER) == true) {
                @Suppress("UNCHECKED_CAST")
                (template as FileTemplate).setText(null as String?)
                changed = true
            }
        }

        if (changed) manager.saveAllTemplates()
    }

    /**
     * 判断模板是否当前由本插件写入（File Header 或任一内部模板含 IMS-V6 标记）。
     * 供设置面板 reset() 回显勾选状态兜底用：避免把"用户手改过"误判为已勾选。
     */
    fun isTemplateApplied(project: Project): Boolean {
        val manager = FileTemplateManager.getInstance(project)
        if (manager.getPattern(FILE_HEADER)?.text?.contains(IMS_MARKER) == true) return true
        return CLASS_TYPES.any { name ->
            manager.getInternalTemplate(name)?.text?.contains(IMS_MARKER) == true
        }
    }
}
