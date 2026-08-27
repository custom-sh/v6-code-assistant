package com.ims.code.helper.ai

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.util.messages.Topic

/**
 * AI 对话代码附件
 * @author shenwl
 * @date 2026/08/14
 */
data class AiChatAttachment(
    val context: AiEditorContext,
    val fileName: String,
    val startLine: Int,
    val endLine: Int,
    val task: AiEditorTask?,
    val document: Document,
    val editor: Editor,
    val file: PsiFile,
    val marker: RangeMarker,
    val originalText: String
) {
    fun dispose() {
        marker.dispose()
    }
}

/**
 * AI 对话上下文投递请求
 * @author shenwl
 * @date 2026/08/14
 */
data class AiChatContextRequest(
    val attachment: AiChatAttachment? = null,
    val initialPrompt: String = ""
)

/**
 * AI 对话上下文通知器
 * @author shenwl
 * @date 2026/08/14
 */
interface AiChatContextListener {
    fun onContextAvailable()

    companion object {
        val TOPIC: Topic<AiChatContextListener> = Topic.create(
            "V6 AI chat context",
            AiChatContextListener::class.java
        )
    }
}

/**
 * AI 对话上下文桥接器
 * @author shenwl
 * @date 2026/08/14
 */
object AiChatContextBridge {
    private val PENDING_CONTEXT_KEY = Key.create<AiChatContextRequest>("ims.ai.chat.pending.context")

    fun submit(project: Project, request: AiChatContextRequest) {
        project.getUserData(PENDING_CONTEXT_KEY)?.attachment?.dispose()
        project.putUserData(PENDING_CONTEXT_KEY, request)
        project.messageBus.syncPublisher(AiChatContextListener.TOPIC).onContextAvailable()
    }

    fun consume(project: Project): AiChatContextRequest? {
        val request = project.getUserData(PENDING_CONTEXT_KEY) ?: return null
        project.putUserData(PENDING_CONTEXT_KEY, null)
        return request
    }
}
