package com.ims.code.helper.ai.agent

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 项目上下文缓存
 * @author shenwl
 * @date 2026/08/26
 */
internal object ProjectAgentContextCache {
    private data class Entry(val fingerprint: String, val result: ProjectAgentTools.Result)

    private val entries = ConcurrentHashMap<Path, Entry>()

    fun get(
        projectRoot: Path,
        fingerprint: String,
        loader: () -> ProjectAgentTools.Result
    ): ProjectAgentTools.Result {
        val root = projectRoot.toAbsolutePath().normalize()
        val existing = entries[root]
        if (existing != null && existing.fingerprint == fingerprint) return existing.result
        val result = loader()
        entries[root] = Entry(fingerprint, result)
        return result
    }
}
