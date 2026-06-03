package com.ims.code.helper.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.ims.code.helper.util.ImsBundle

/**
 * 代码生成 Action
 * 右键菜单入口：V6 Code Helper -> Generate Code
 * 当前阶段为骨架实现，Phase 2 完善具体生成逻辑
 */
class GenerateCodeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        Messages.showMessageDialog(
            project,
            ImsBundle.message("dialog.generate.message"),
            ImsBundle.message("dialog.generate.title"),
            Messages.getInformationIcon()
        )
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}