package com.ims.code.helper.ai

import com.ims.code.helper.generator.GenerationContext

/**
 * AI 服务接口
 * 预留接口，后期实现 AI 代码生成和代码审查功能
 */
interface AiService {

    /**
     * 检查 AI 服务是否可用
     */
    fun isAvailable(): Boolean

    /**
     * AI 代码生成
     * @param prompt 生成提示
     * @param context 生成上下文
     * @return 生成的代码，失败返回 null
     */
    fun generateCode(prompt: String, context: GenerationContext): String?

    /**
     * AI 代码审查
     * @param code 待审查的代码
     * @param context 上下文
     * @return 审查结果
     */
    fun reviewCode(code: String, context: GenerationContext): ReviewResult?
}

/**
 * 代码审查结果
 */
data class ReviewResult(
    // 是否通过
    val passed: Boolean,
    // 问题列表
    val issues: List<ReviewIssue>,
    // 改进建议
    val suggestions: List<String>
)

/**
 * 审查问题
 */
data class ReviewIssue(
    // 问题级别：ERROR, WARNING, INFO
    val level: IssueLevel,
    // 问题描述
    val message: String,
    // 问题位置（行号）
    val line: Int?,
    // 修复建议
    val fixSuggestion: String?
)

enum class IssueLevel {
    ERROR,
    WARNING,
    INFO
}
