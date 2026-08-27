package com.ims.code.helper.ai

import com.intellij.openapi.project.Project

/**
 * AI 能力后端抽象，隔离插件界面与本地模型服务
 * @author shenwl
 * @date 2026/08/22
 */
interface AiBackend {

    fun isConfigured(project: Project): Boolean

    fun chatStream(
        project: Project,
        query: String,
        systemPrompt: String?,
        history: List<Pair<String, String>>,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        requestTimeoutMs: Int? = null
    ): CancelHandle?

    interface CancelHandle {
        fun cancel()
    }
}
