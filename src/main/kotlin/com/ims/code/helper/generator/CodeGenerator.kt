package com.ims.code.helper.generator

/**
 * 代码生成器接口
 * 所有代码生成器都需要实现此接口
 */
interface CodeGenerator {

    /**
     * 生成代码
     * @param context 生成上下文
     * @return 生成结果列表
     */
    fun generate(context: GenerationContext): List<GeneratedFile>

    /**
     * 获取生成器名称
     */
    fun getName(): String
}

/**
 * 生成的文件
 */
data class GeneratedFile(
    // 相对路径
    val relativePath: String,
    // 文件内容
    val content: String,
    // 是否覆盖已有文件
    val overwrite: Boolean = false
)
