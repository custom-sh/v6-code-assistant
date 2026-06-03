package com.ims.code.helper.generator

import com.intellij.openapi.project.Project
import com.ims.code.helper.config.ImsProjectSettings
import com.ims.code.helper.config.NamingConventions
import com.intellij.psi.PsiElement

/**
 * 代码生成上下文
 * 包含生成代码所需的所有信息
 */
data class GenerationContext(
    // 当前项目
    val project: Project,
    // 目标模块类型
    val targetModule: ModuleType,
    // 类名
    val className: String,
    // 包名
    val packageName: String,
    // 当前选中的代码
    val existingCode: String?,
    // PSI 上下文
    val psiContext: PsiElement?,
    // 项目配置
    val projectSettings: ImsProjectSettings
) {
    // 命名规范直接使用写死的常量
    val naming get() = NamingConventions
}

/**
 * 模块类型
 */
enum class ModuleType(val displayName: String) {
    SERVER("服务端"),
    WEB("Web端"),
    PDA("PDA端")
}
