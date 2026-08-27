package com.ims.code.helper.ai

import com.intellij.openapi.project.Project

/**
 * 基于插件内置 AI 服务提供器的本地后端适配器
 * @author shenwl
 * @date 2026/08/22
 */
object LocalAiBackend : AiBackend {

    override fun isConfigured(project: Project): Boolean {
        val config = AiServiceProvider.getConfig(project)
        return config.endpoint.isNotBlank() &&
            config.apiKey.isNotBlank() &&
            config.model.isNotBlank()
    }

    override fun chatStream(
        project: Project,
        query: String,
        systemPrompt: String?,
        history: List<Pair<String, String>>,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        requestTimeoutMs: Int?
    ): AiBackend.CancelHandle? {
        val config = AiServiceProvider.getConfig(project)
        val effectiveTimeoutMs = maxOf(config.timeoutMs, requestTimeoutMs ?: config.timeoutMs)
            .coerceAtMost(MAX_REQUEST_TIMEOUT_MS)
        val cancel = AiServiceProvider.chatStream(
            query = query,
            config = config.copy(timeoutMs = effectiveTimeoutMs),
            systemPrompt = systemPrompt,
            history = history,
            onToken = onToken,
            onComplete = onComplete,
            onError = onError
        ) ?: return null
        return object : AiBackend.CancelHandle {
            override fun cancel() = cancel.cancel()
        }
    }

    private const val MAX_REQUEST_TIMEOUT_MS = 600_000
}
