package com.ims.code.helper.util

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.ims.code.helper.config.ImsProjectConfigurable
import com.ims.code.helper.config.ImsProjectSettings

/**
 * 插件配置门禁：项目级配置未完成时统一拦截功能 Action，引导用户先配置。
 *
 * @author shenwl
 * @date 2026/07/12
 */
object ConfigGate {

    /**
     * 校验项目是否已完成最小配置；未配置则弹统一提示并返回 false。
     *
     * 各功能 Action 在 [actionPerformed] 首行调用：
     *
     *   if (!ConfigGate.requireConfigured(project)) return
     *
     * @return true 已配置可继续；false 未配置已弹提示，调用方应直接 return
     */
    fun requireConfigured(project: Project?): Boolean {
        if (project == null) return false
        val settings = ImsProjectSettings.getInstance(project)
        if (settings.isConfigured()) return true

        // 历史项目持久化项目编码可能为空（曾锁只读导致保存留空），先从项目文件补一次再判：
        // 补到且三端路径齐全 → 放行，避免门禁死循环；仍不齐 → 弹提示引导配置。
        if (settings.ensureProjectCode(project) && settings.isConfigured()) return true

        val goConfig = Messages.showDialog(
            project,
            ImsBundle.message("config.gate.message"),
            ImsBundle.message("config.gate.title"),
            arrayOf(
                ImsBundle.message("config.gate.button.config"),
                ImsBundle.message("config.gate.button.cancel")
            ),
            0,
            Messages.getWarningIcon()
        )
        if (goConfig == 0) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, ImsProjectConfigurable::class.java)
        }
        return false
    }
}
