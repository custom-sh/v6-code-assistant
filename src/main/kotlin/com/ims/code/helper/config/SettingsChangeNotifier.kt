package com.ims.code.helper.config

import com.intellij.util.messages.Topic

/**
 * 设置变更通知器
 * @author shenwl
 * @date 2026/07/12
 */
interface SettingsChangeNotifier {
    companion object {
        @JvmField
        val TOPIC = Topic.create("V6SettingsChange", SettingsChangeNotifier::class.java)
    }

    fun onLanguageChanged()
}
