package com.ims.code.helper.util

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * 代码格式化工具
 * 使用 IDEA 内置的代码格式化功能
 */
object CodeFormatter {

    /**
     * 格式化代码
     * @param project 项目
     * @param psiFile PSI 文件
     */
    fun format(project: Project, psiFile: PsiFile) {
        val codeStyleManager = CodeStyleManager.getInstance(project)
        codeStyleManager.reformat(psiFile)
    }

    /**
     * 格式化代码片段
     * @param project 项目
     * @param code 代码内容
     * @param fileType 文件类型（如 JavaFileType.INSTANCE）
     * @return 格式化后的代码
     */
    fun formatCode(project: Project, code: String, fileType: com.intellij.openapi.fileTypes.FileType): String {
        // 创建临时 PSI 文件进行格式化
        val psiFileFactory = com.intellij.psi.PsiFileFactory.getInstance(project)
        val tempFile = psiFileFactory.createFileFromText("temp", fileType, code)
        format(project, tempFile)
        return tempFile.text
    }
}
