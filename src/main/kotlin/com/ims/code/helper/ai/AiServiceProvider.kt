package com.ims.code.helper.ai

import com.intellij.openapi.project.Project
import com.ims.code.helper.config.ImsProjectSettings
import com.ims.code.helper.generator.GenerationContext

/**
 * AI 服务提供者
 * 负责创建和管理 AiService 实例
 * 当前阶段为空实现预留，后期根据配置创建具体的 AI 服务
 */
object AiServiceProvider {

    /**
     * 获取 AI 服务实例
     * 根据项目配置返回对应的 AiService 实现
     * 当前阶段返回空实现
     */
    fun getService(project: Project): AiService {
        val settings = ImsProjectSettings.getInstance(project)
        return when (settings.aiProvider) {
            "OpenAI" -> StubAiService()   // 后期替换为 OpenAiService()
            "Claude" -> StubAiService()   // 后期替换为 ClaudeAiService()
            "DeepSeek" -> StubAiService() // 后期替换为 DeepSeekAiService()
            else -> StubAiService()
        }
    }
}

/**
 * 空实现的 AI 服务
 * 所有方法返回不可用或空结果
 */
private class StubAiService : AiService {
    override fun isAvailable(): Boolean = false
    override fun generateCode(prompt: String, context: GenerationContext): String? = null
    override fun reviewCode(code: String, context: GenerationContext): ReviewResult? = null
}
