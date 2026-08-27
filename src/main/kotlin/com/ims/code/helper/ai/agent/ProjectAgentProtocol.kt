package com.ims.code.helper.ai.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ims.code.helper.ai.AiReplacementResultParser

/**
 * 项目分析 Agent 的结构化决策协议
 * @author shenwl
 * @date 2026/08/25
 */
internal sealed interface ProjectAgentDecision {
    data class ToolCall(val tool: String, val arguments: JsonObject) : ProjectAgentDecision
    data class FinalAnswer(val answer: String, val scope: String? = null) : ProjectAgentDecision
    data class PlainAnswer(val answer: String) : ProjectAgentDecision
    data class Invalid(val reason: String) : ProjectAgentDecision
}

/** 解析模型返回的项目分析决策。 */
internal object ProjectAgentProtocol {
    internal const val DIRECT_ANSWER_PREFIX = "DIRECT_ANSWER:"

    fun parse(response: String): ProjectAgentDecision {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) return ProjectAgentDecision.Invalid("empty response")
        if (trimmed.startsWith(DIRECT_ANSWER_PREFIX)) {
            val answer = trimmed.removePrefix(DIRECT_ANSWER_PREFIX).trimStart()
            return if (answer.isBlank()) {
                ProjectAgentDecision.Invalid("empty direct answer")
            } else {
                ProjectAgentDecision.FinalAnswer(answer, "general")
            }
        }
        val json = AiReplacementResultParser.firstJsonObject(response)
            ?: return ProjectAgentDecision.PlainAnswer(trimmed)
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return ProjectAgentDecision.PlainAnswer(trimmed)
        val type = root.string("type")?.lowercase()
        val tool = root.string("tool")?.trim()
            ?: root.string("name")?.trim()?.takeIf { root.has("arguments") }
        if (type in TOOL_TYPES || (type == null && !tool.isNullOrBlank())) {
            return if (tool.isNullOrBlank()) {
                ProjectAgentDecision.Invalid("missing tool name")
            } else {
                ProjectAgentDecision.ToolCall(tool, root.arguments())
            }
        }
        val answer = root.string("answer") ?: root.string("content")
        if ((type == "final" || type == null) && !answer.isNullOrBlank()) {
            return ProjectAgentDecision.FinalAnswer(answer, root.string("scope")?.lowercase())
        }
        return if (json != trimmed) {
            ProjectAgentDecision.PlainAnswer(trimmed)
        } else {
            ProjectAgentDecision.Invalid("unknown decision type")
        }
    }

    private fun JsonObject.arguments(): JsonObject {
        val value = get("arguments") ?: return JsonObject()
        if (value.isJsonObject) return value.asJsonObject
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return JsonObject()
        return runCatching { JsonParser.parseString(value.asString).asJsonObject }.getOrDefault(JsonObject())
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private val TOOL_TYPES = setOf("tool", "tool_call", "function", "function_call")
}
