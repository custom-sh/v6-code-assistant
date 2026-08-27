package com.ims.code.helper.util

import com.intellij.openapi.application.ModalityState

/**
 * IntelliJ 跨版本非模态状态兼容工具
 * @author shenwl
 * @date 2026/07/19
 */
object ModalityStateCompat {
    private val nonModalState: ModalityState by lazy {
        try {
            ModalityState::class.java.getMethod("nonModal").invoke(null) as ModalityState
        } catch (_: ReflectiveOperationException) {
            ModalityState::class.java.getField("NON_MODAL").get(null) as ModalityState
        }
    }

    fun nonModal(): ModalityState = nonModalState
}
