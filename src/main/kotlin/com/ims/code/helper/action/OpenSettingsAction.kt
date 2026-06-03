package com.ims.code.helper.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.ims.code.helper.config.ImsProjectConfigurable

/**
 * 打开设置 Action
 * 右键菜单入口：V6 Code Helper -> Open V6 Settings
 */
class OpenSettingsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ImsProjectConfigurable::class.java)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}
