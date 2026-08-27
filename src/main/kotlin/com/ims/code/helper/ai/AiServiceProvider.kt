package com.ims.code.helper.ai

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.ims.code.helper.config.AiModelConfiguration
import com.ims.code.helper.config.ImsGlobalSettings
import com.ims.code.helper.config.SecretStore
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI

/**
 * AI 服务提供者
 * @author shenwl
 * @date 2026/07/12
 */
object AiServiceProvider {

    const val OPENAI_COMPATIBLE = "OpenAI-compatible"
    const val ANTHROPIC_COMPATIBLE = "Anthropic-compatible"

    private val log = Logger.getInstance(AiServiceProvider::class.java)
    private val gson = Gson()
    private const val DEFAULT_ANTHROPIC_MAX_TOKENS = 4096
    internal const val MAX_AI_RESPONSE_CHARS = 2_000_000
    private const val MAX_ERROR_RESPONSE_CHARS = 2_000
    private const val MAX_SSE_LINE_CHARS = 1_000_000
    private val modelApiVersionSegment = Regex("v\\d+")
    private val modelApiCompatSuffixes = listOf(
        "/api/claudecode",
        "/api/anthropic",
        "/apps/anthropic",
        "/api/compatible",
        "/api/coding",
        "/anthropic",
        "/claudecode",
        "/step_plan",
        "/coding",
        "/claude"
    )

    data class ChatConfig(
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val protocol: String = OPENAI_COMPATIBLE,
        val timeoutMs: Int = 120_000,
        val extraConfig: Map<String, String> = emptyMap()
    )

    /**
     * 从项目设置读取 AI 配置。
     *
     * Endpoint、模型、协议和超时均以标记为默认的模型配置为准。
     * API Key 只从 PasswordSafe 中读取，不提供内置凭据。
     */
    fun getConfig(@Suppress("UNUSED_PARAMETER") project: Project): ChatConfig {
        val configuration = ImsGlobalSettings.getInstance().aiConfigurations
            .let { configurations ->
                configurations.firstOrNull { it.isDefault } ?: configurations.firstOrNull()
            }
        val storedApiKey = configuration?.id?.let(SecretStore::getAiApiKey).orEmpty()
        return createChatConfig(configuration, storedApiKey)
    }

    internal fun createChatConfig(
        configuration: AiModelConfiguration?,
        storedApiKey: String
    ): ChatConfig {
        if (configuration == null) return ChatConfig("", "", "")
        return ChatConfig(
            endpoint = configuration.endpoint.trim().trimEnd('/'),
            apiKey = storedApiKey.trim(),
            model = configuration.model.trim(),
            protocol = configuration.protocol.ifBlank { OPENAI_COMPATIBLE },
            timeoutMs = configuration.timeoutSeconds.coerceIn(5, 600) * 1000,
            extraConfig = emptyMap()
        )
    }

    /**
     * 流式取消句柄。conn/inputStream 由后台线程在建立后写入 volatile 字段，
     * 保证调用方 cancel() 拿到的是真实引用而非 null（否则关不掉、readLine 会一直阻塞到超时）。
     */
    class ChatCancel internal constructor(private val thread: Thread) {
        @Volatile private var conn: HttpURLConnection? = null
        @Volatile private var inputStream: java.io.InputStream? = null

        @Volatile var isCancelled: Boolean = false
            private set

        internal fun bindConnection(c: HttpURLConnection) {
            conn = c
            if (isCancelled) c.disconnect()
        }

        internal fun bindInputStream(s: InputStream) {
            inputStream = s
            if (isCancelled) s.close()
        }

        fun cancel() {
            isCancelled = true
            // 先 disconnect + close 底层流，触发 BufferedReader.readLine() 抛异常退出；
            // 单独 interrupt 对阻塞在 readLine 的线程无效。
            try { conn?.disconnect() } catch (e: Exception) { log.warn("Failed to disconnect on cancel", e) }
            try { inputStream?.close() } catch (e: Exception) { log.warn("Failed to close stream on cancel", e) }
            try { thread.interrupt() } catch (e: Exception) { log.warn("Failed to interrupt thread on cancel", e) }
        }
    }

    /**
     * 流式 RAG 问答。
     *
     * 检索到的文档块由调用方拼接进 systemPrompt 传入。
     *
     * @param query 用户问题（本轮 user 内容）
     * @param config AI API 配置
     * @param systemPrompt 系统提示词（含已拼接的检索上下文）
     * @param history 之前的对话轮次（role, content），按时间序；为空即单轮
     * @param onToken 每收到一个 token 的回调
     * @param onComplete 完成回调（完整响应文本）
     * @param onError 错误回调
     */
    fun chatStream(
        query: String,
        config: ChatConfig,
        systemPrompt: String? = null,
        history: List<Pair<String, String>> = emptyList(),
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ): ChatCancel? {
        if (config.endpoint.isBlank() || config.apiKey.isBlank()) {
            onError("AI API 未配置，请在设置中填写 Endpoint 和 API Key")
            return null
        }

        val prompt = systemPrompt ?: "You are a helpful assistant. Answer the user's question concisely and accurately."
        val turns = history + ("user" to query)
        val requestBody = buildChatRequestJson(
            config = config,
            systemContent = prompt,
            turns = turns,
            stream = true,
            temperature = 0.1,
            maxTokens = null
        )

        // 用外层可变引用 + 线程内 bind：ChatCancel 的 conn/inputStream 由后台线程建立后写入 volatile，
        // 避免调用方在 thread.start() 之后立即 cancel() 时拿到 null 引用。
        lateinit var cancel: ChatCancel
        val thread = Thread {
            var conn: HttpURLConnection? = null
            try {
                if (cancel.isCancelled) return@Thread
                val url = completionUrl(config)
                conn = openAiConnection(url)
                cancel.bindConnection(conn)
                if (cancel.isCancelled) return@Thread
                conn.requestMethod = "POST"
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                applyAuthenticationHeaders(conn, config)
                conn.doOutput = true
                conn.connectTimeout = config.timeoutMs
                conn.readTimeout = config.timeoutMs

                if (cancel.isCancelled) return@Thread
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(requestBody); it.flush() }

                val code = conn.responseCode
                if (code != 200) {
                    // errorStream 需关闭且限长：错误页可能很大，全量读入会撑爆内存
                    val errorBody = conn.errorStream?.let {
                        readLimitedUtf8(it, MAX_ERROR_RESPONSE_CHARS).text
                    } ?: ""
                    log.warn("AI API error $code: $errorBody")
                    onError("API 请求失败 (HTTP $code): ${extractError(errorBody)}")
                    return@Thread
                }

                val inputStream = conn.inputStream
                cancel.bindInputStream(inputStream)
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                val fullResponse = StringBuilder()
                val nonStreamBody = StringBuilder()
                var completedContent: String? = null
                var streamError: String? = null
                var line: String?
                while (readLimitedLine(reader, MAX_SSE_LINE_CHARS).also { line = it } != null) {
                    if (Thread.currentThread().isInterrupted) {
                        onComplete(fullResponse.toString())
                        return@Thread
                    }
                    val sseLine = line!!
                    // 兼容 "data: " 与紧贴的 "data:"（某些网关不带空格），trim 去空白
                    if (sseLine.startsWith("data:")) {
                        val data = sseLine.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val token = parseDeltaContent(data, config.protocol)
                        if (token != null) {
                            checkResponseSize(fullResponse.length, token.length)
                            fullResponse.append(token)
                            onToken(token)
                        } else {
                            completedContent = parseCompletionContent(data, config.protocol) ?: completedContent
                            streamError = parseStreamError(data) ?: streamError
                        }
                    } else if (sseLine.isNotBlank()) {
                        checkResponseSize(nonStreamBody.length, sseLine.length + 1)
                        nonStreamBody.appendLine(sseLine)
                    }
                }
                val response = fullResponse.toString().ifBlank {
                    completedContent
                        ?: parseCompletionContent(nonStreamBody.toString(), config.protocol)
                        ?: ""
                }
                when {
                    response.isNotBlank() -> {
                        if (fullResponse.isEmpty()) onToken(response)
                        onComplete(response)
                    }
                    streamError != null -> onError("API 请求失败: $streamError")
                    else -> {
                        if (cancel.isCancelled) return@Thread
                        val retry = callSyncCompletionWithTurns(
                            config = config,
                            turns = turns,
                            systemContent = prompt,
                            temperature = 0.1,
                            maxTokens = null,
                            timeoutMs = config.timeoutMs
                        )
                        if (retry.content.isNullOrBlank()) {
                            onError("模型未返回可识别的文本内容；非流式重试失败: ${retry.error.orEmpty()}")
                        } else {
                            onToken(retry.content)
                            onComplete(retry.content)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted && !cancel.isCancelled) {
                    log.warn("AI API call failed", e)
                    onError("请求失败: ${e.message}")
                } else {
                    onComplete("")
                }
            } finally {
                conn?.disconnect()
            }
        }
        cancel = ChatCancel(thread)
        thread.start()
        return cancel
    }

    /** 从 OpenAI/Anthropic SSE data 行中提取文本增量。 */
    internal fun parseDeltaContent(data: String, protocol: String): String? {
        return try {
            val root = JsonParser.parseString(data).asJsonObject
            if (protocol == ANTHROPIC_COMPATIBLE) {
                when (root.get("type")?.asString) {
                    "content_block_delta" -> extractTextContent(root.getAsJsonObject("delta")?.get("text"))
                    "content_block_start" -> extractTextContent(root.getAsJsonObject("content_block")?.get("text"))
                    else -> null
                }
            } else {
                val delta = root.getAsJsonArray("choices")
                    ?.firstOrNull()?.takeIf { it.isJsonObject }
                    ?.asJsonObject?.getAsJsonObject("delta")
                extractTextContent(delta?.get("content"))
                    ?: delta?.takeIf { it.get("type")?.asString == "output_text" }
                        ?.get("text")?.let(::extractTextContent)
            }
        } catch (e: Exception) {
            log.debug("Failed to parse SSE delta content for protocol=$protocol, ${safePayloadSummary("payloadLength", data)}", e)
            null
        }
    }

    /**
     * 根据配置协议构造请求 JSON。
     * 用 Gson 序列化避免手拼字符串导致的转义错误。
     *
     * @param systemContent system 角色内容；为 null 时不加 system 消息
     * @param userContent   user 角色内容
     * @param stream        是否流式
     * @param temperature   采样温度
     * @param maxTokens     输出 token 上限；Anthropic 为 null 时使用 extraConfig 或默认值
     */
    fun buildChatRequestJson(
        config: ChatConfig,
        systemContent: String?,
        userContent: String,
        stream: Boolean,
        temperature: Double,
        maxTokens: Int?
    ): String = buildChatRequestJson(config, systemContent, listOf("user" to userContent), stream, temperature, maxTokens)

    /**
     * 多轮消息版本的请求构造。
     *
     * @param systemContent system 角色内容；为 null 时不加 system 消息
     * @param turns         按时间序的对话轮次，每项为 (role, content)；role 应为 "user" / "assistant"
     * @param stream        是否流式
     * @param temperature   采样温度
     * @param maxTokens     输出 token 上限；Anthropic 为 null 时使用 extraConfig 或默认值
     */
    fun buildChatRequestJson(
        config: ChatConfig,
        systemContent: String?,
        turns: List<Pair<String, String>>,
        stream: Boolean,
        temperature: Double,
        maxTokens: Int?
    ): String {
        val anthropicCompatible = config.protocol == ANTHROPIC_COMPATIBLE
        val messages = mutableListOf<Map<String, String>>()
        if (!anthropicCompatible && systemContent != null) {
            messages.add(mapOf("role" to "system", "content" to systemContent))
        }
        for ((role, content) in turns) messages.add(mapOf("role" to role, "content" to content))

        val body = linkedMapOf<String, Any>(
            "model" to config.model,
            "messages" to messages,
            "stream" to stream
        )
        if (anthropicCompatible && systemContent != null) body["system"] = systemContent
        body["temperature"] = temperature
        if (anthropicCompatible) {
            body["max_tokens"] = maxTokens ?: config.extraConfig["max_tokens"]?.toIntOrNull()
                ?: DEFAULT_ANTHROPIC_MAX_TOKENS
        } else if (maxTokens != null) {
            body["max_tokens"] = maxTokens
        }

        // extraConfig 合并；用户提供的 max_tokens 不要覆盖显式参数
        for ((k, v) in config.extraConfig) {
            if (k == "max_tokens" && (maxTokens != null || anthropicCompatible)) continue
            body[k] = parseExtraValue(v)
        }
        return gson.toJson(body)
    }

    internal fun completionUrl(config: ChatConfig): String {
        val base = config.endpoint.trim().trimEnd('/')
        return if (config.protocol == ANTHROPIC_COMPATIBLE) {
            if (modelApiVersionSegment.matches(base.substringAfterLast('/'))) "$base/messages" else "$base/v1/messages"
        } else {
            "$base/chat/completions"
        }
    }

    private fun applyAuthenticationHeaders(conn: HttpURLConnection, config: ChatConfig) {
        if (config.protocol == ANTHROPIC_COMPATIBLE) {
            conn.setRequestProperty("x-api-key", config.apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
        } else {
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }
    }

    internal fun validateAiEndpoint(url: String): String? {
        val uri = try {
            URI.create(url)
        } catch (e: Exception) {
            return "Endpoint 格式无效: ${e.message}"
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.trim('[', ']')?.lowercase().orEmpty()
        if (uri.userInfo != null || host.isEmpty()) return "Endpoint 必须包含有效主机，且不能包含用户信息"
        if (scheme == "https") return null
        if (scheme == "http" && isLoopbackHost(host)) return null
        return "Endpoint 必须使用 HTTPS；仅 localhost、127.0.0.0/8 或 ::1 允许使用 HTTP"
    }

    private fun isLoopbackHost(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        return octets.size == 4 && octets.first() == 127 && octets.all { it in 0..255 }
    }

    private fun openAiConnection(url: String): HttpURLConnection {
        validateAiEndpoint(url)?.let { throw IllegalArgumentException(it) }
        return URI.create(url).toURL().openConnection() as HttpURLConnection
    }

    private data class LimitedText(val text: String, val truncated: Boolean)

    private fun readLimitedUtf8(stream: InputStream, maxChars: Int): LimitedText = stream.use { input ->
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val buffer = CharArray(4096)
        val text = StringBuilder(minOf(maxChars, buffer.size))
        while (text.length <= maxChars) {
            val remaining = maxChars + 1 - text.length
            val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) return@use LimitedText(text.toString(), false)
            text.append(buffer, 0, count)
        }
        LimitedText(text.substring(0, maxChars), true)
    }

    private fun readLimitedLine(reader: BufferedReader, maxChars: Int): String? {
        val line = StringBuilder()
        while (true) {
            when (val char = reader.read()) {
                -1 -> return line.takeIf { it.isNotEmpty() }?.toString()
                '\n'.code -> return line.toString()
                '\r'.code -> Unit
                else -> {
                    if (line.length >= maxChars) throw IOException("AI 响应单行超过 ${maxChars} 字符")
                    line.append(char.toChar())
                }
            }
        }
    }

    private fun checkResponseSize(currentChars: Int, additionalChars: Int) {
        if (currentChars > MAX_AI_RESPONSE_CHARS - additionalChars) {
            throw IOException("AI 响应超过 ${MAX_AI_RESPONSE_CHARS} 字符")
        }
    }

    /** 解析 extraConfig 中的字符串值：尝试 number/boolean/null/JSON，否则保留字符串 */
    private fun parseExtraValue(v: String): Any {
        val t = v.trim()
        return when {
            t == "true" -> true
            t == "false" -> false
            // JSON null：返回 JsonNull 而非字符串 "null"，否则 Gson 序列化成 "null"（带引号）会被 API 当非法值报 400
            t == "null" -> JsonNull.INSTANCE
            t.startsWith("{") || t.startsWith("[") -> try {
                JsonParser.parseString(t)
            } catch (_: Exception) { t }
            else -> t.toDoubleOrNull() ?: t
        }
    }

    /**
     * 根据配置协议同步调用非流式补全接口并返回文本内容。
     * 供 HyDE / Reranker / STAT 翻译等不需要流式的场景共享使用。
     *
     * @return content 字符串；非 200 / 解析失败 / 异常 时返回 null
     */
    fun callSyncCompletion(
        config: ChatConfig,
        userContent: String,
        systemContent: String? = null,
        temperature: Double = 0.1,
        maxTokens: Int? = null,
        timeoutMs: Int = 30_000
    ): String? = callSyncCompletionWithDetail(config, userContent, systemContent, temperature, maxTokens, timeoutMs).content

    /** [callSyncCompletion] 的结果（含失败原因，诊断用）。content 为 null 时 error 说明失败原因。 */
    data class SyncResult(val content: String?, val error: String?)

    /** 使用当前 API Key 和模型发送最小请求，验证配置是否可实际调用。 */
    fun testModelConnection(
        endpoint: String,
        apiKey: String,
        model: String,
        protocol: String,
        timeoutMs: Int
    ): SyncResult {
        if (endpoint.isBlank()) return SyncResult(null, "endpoint 为空")
        if (apiKey.isBlank()) return SyncResult(null, "apiKey 为空")
        if (model.isBlank()) return SyncResult(null, "model 为空")

        val config = ChatConfig(
            endpoint = endpoint.trim().trimEnd('/'),
            apiKey = apiKey,
            model = model,
            protocol = protocol,
            timeoutMs = timeoutMs
        )
        val url = completionUrl(config)
        val requestBody = buildChatRequestJson(config, null, "ping", false, 0.0, 1)

        var conn: HttpURLConnection? = null
        return try {
            conn = openAiConnection(url)
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            applyAuthenticationHeaders(conn, config)
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(requestBody) }

            val status = conn.responseCode
            if (status in 200..299) {
                SyncResult("HTTP $status", null)
            } else {
                val body = conn.errorStream?.let { readLimitedUtf8(it, 300).text }.orEmpty()
                SyncResult(null, "HTTP $status${if (body.isBlank()) "" else ": $body"}")
            }
        } catch (e: Exception) {
            log.warn("AI model connection test failed", e)
            SyncResult(null, "异常: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 从 OpenAI/Anthropic 兼容的模型列表接口获取模型 ID。
     */
    fun fetchAvailableModels(
        endpoint: String,
        apiKey: String,
        protocol: String,
        timeoutMs: Int
    ): Result<List<String>> {
        if (endpoint.isBlank()) return Result.failure(IllegalArgumentException("endpoint 为空"))
        if (apiKey.isBlank()) return Result.failure(IllegalArgumentException("apiKey 为空"))

        val candidates = buildModelListUrlCandidates(endpoint)
        var lastError = "未找到模型列表接口"
        for (url in candidates) {
            var conn: HttpURLConnection? = null
            try {
                conn = openAiConnection(url)
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                if (protocol == "Anthropic-compatible") {
                    conn.setRequestProperty("x-api-key", apiKey)
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                }
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs

                val status = conn.responseCode
                if (status in 200..299) {
                    val response = readLimitedUtf8(conn.inputStream, MAX_AI_RESPONSE_CHARS)
                    if (response.truncated) {
                        return Result.failure(IllegalStateException("模型列表响应超过 ${MAX_AI_RESPONSE_CHARS} 字符"))
                    }
                    val body = response.text
                    val data = JsonParser.parseString(body).asJsonObject.getAsJsonArray("data")
                        ?: return Result.failure(IllegalStateException("响应中缺少 data 数组"))
                    val models = data.mapNotNull { item ->
                        item.takeIf { it.isJsonObject }
                            ?.asJsonObject
                            ?.get("id")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                    }.filter(String::isNotBlank).distinct().sorted()
                    return Result.success(models)
                }

                val body = conn.errorStream?.let { readLimitedUtf8(it, 512).text }.orEmpty()
                lastError = "HTTP $status${if (body.isBlank()) "" else ": $body"}"
                if (status != HttpURLConnection.HTTP_NOT_FOUND &&
                    status != HttpURLConnection.HTTP_BAD_METHOD
                ) {
                    return Result.failure(IllegalStateException(lastError))
                }
            } catch (e: Exception) {
                log.warn("Failed to fetch AI models from $url", e)
                return Result.failure(IllegalStateException("${e.javaClass.simpleName}: ${e.message}", e))
            } finally {
                conn?.disconnect()
            }
        }
        return Result.failure(IllegalStateException("所有模型列表地址均不可用：$lastError"))
    }

    internal fun buildModelListUrlCandidates(endpoint: String): List<String> {
        val base = endpoint.trim().trimEnd('/')
        if (base.isEmpty()) return emptyList()

        val candidates = mutableListOf<String>()
        val versionSegment = base.substringAfterLast('/')
        if (modelApiVersionSegment.matches(versionSegment)) {
            candidates += "$base/models"
            if (versionSegment != "v1") candidates += "$base/v1/models"
        } else {
            candidates += "$base/v1/models"
        }

        val suffix = modelApiCompatSuffixes.firstOrNull(base::endsWith)
        if (suffix != null) {
            val root = base.removeSuffix(suffix).trimEnd('/')
            candidates += "$root/v1/models"
            candidates += "$root/models"
        }
        return candidates.distinct()
    }

    /**
     * 同 [callSyncCompletion]，但失败时在 [SyncResult.error] 中带回具体原因
     * （HTTP 状态码 + errorStream 摘要，或异常信息），便于调用方定位。
     */
    fun callSyncCompletionWithDetail(
        config: ChatConfig,
        userContent: String,
        systemContent: String? = null,
        temperature: Double = 0.1,
        maxTokens: Int? = null,
        timeoutMs: Int = 30_000
    ): SyncResult = callSyncCompletionWithTurns(
        config = config,
        turns = listOf("user" to userContent),
        systemContent = systemContent,
        temperature = temperature,
        maxTokens = maxTokens,
        timeoutMs = timeoutMs
    )

    private fun callSyncCompletionWithTurns(
        config: ChatConfig,
        turns: List<Pair<String, String>>,
        systemContent: String?,
        temperature: Double,
        maxTokens: Int?,
        timeoutMs: Int
    ): SyncResult {
        if (config.endpoint.isBlank() || config.apiKey.isBlank()) {
            return SyncResult(null, "endpoint 或 apiKey 为空")
        }
        var conn: HttpURLConnection? = null
        try {
            val requestBody = buildChatRequestJson(config, systemContent, turns, false, temperature, maxTokens)
            val url = completionUrl(config)
            conn = openAiConnection(url)
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            applyAuthenticationHeaders(conn, config)
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(requestBody); it.flush() }
            if (conn.responseCode != 200) {
                // errorStream 需关闭且限长：错误页可能很大，全量读入会撑爆内存
                val err = conn.errorStream?.let {
                    readLimitedUtf8(it, MAX_ERROR_RESPONSE_CHARS).text
                } ?: ""
                val reason = "HTTP ${conn.responseCode}: ${err.take(300)}"
                log.warn("Sync completion failed: $reason")
                return SyncResult(null, reason)
            }
            val response = readLimitedUtf8(conn.inputStream, MAX_AI_RESPONSE_CHARS)
            if (response.truncated) {
                return SyncResult(null, "响应超过 ${MAX_AI_RESPONSE_CHARS} 字符")
            }
            val body = response.text
            val content = parseCompletionContent(body, config.protocol)
            if (content == null) {
                return SyncResult(null, "响应解析失败（无文本内容）: ${body.take(300)}")
            }
            return SyncResult(content, null)
        } catch (e: Exception) {
            log.warn("Sync completion failed", e)
            return SyncResult(null, "异常: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    internal fun parseCompletionContent(body: String, protocol: String): String? {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            if (protocol == ANTHROPIC_COMPATIBLE) {
                extractTextContent(root.get("content"))
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            } else {
                val choice = root.getAsJsonArray("choices")
                    ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                (extractTextContent(choice?.getAsJsonObject("message")?.get("content"))
                    ?: extractTextContent(choice?.get("text"))
                    ?: extractTextContent(root.get("output_text")))
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            }
        } catch (e: Exception) {
            log.debug("Failed to parse completion content for protocol=$protocol, ${safePayloadSummary("bodyLength", body)}", e)
            null
        }
    }

    private fun extractTextContent(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) return element.asString.takeIf(String::isNotEmpty)
        if (element.isJsonArray) {
            return element.asJsonArray.mapNotNull(::extractTextContent)
                .joinToString("")
                .takeIf(String::isNotEmpty)
        }
        if (!element.isJsonObject) return null
        val value = element.asJsonObject
        return extractTextContent(value.get("text")) ?: extractTextContent(value.get("content"))
    }

    private fun parseStreamError(data: String): String? {
        return try {
            val root = JsonParser.parseString(data).asJsonObject
            val error = root.get("error")
            when {
                error == null || error.isJsonNull -> null
                error.isJsonPrimitive -> error.asString
                error.isJsonObject -> extractTextContent(error.asJsonObject.get("message"))
                    ?: extractTextContent(error.asJsonObject.get("error"))
                else -> null
            }
        } catch (e: Exception) {
            log.debug("Failed to parse stream error, ${safePayloadSummary("payloadLength", data)}", e)
            null
        }
    }

    /** 从错误响应体中提取 message（使用 Gson 解析） */
    private fun extractError(body: String): String {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            root.getAsJsonObject("error")?.get("message")?.asString ?: body.take(200)
        } catch (e: Exception) {
            log.debug("Failed to extract error message, ${safePayloadSummary("bodyLength", body)}", e)
            body.take(200)
        }
    }

    internal fun safePayloadSummary(label: String, payload: String): String = "$label=${payload.length}"
}
