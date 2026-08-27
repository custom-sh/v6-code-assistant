package com.ims.code.helper.config

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.ims.code.helper.util.ImsBundle

/**
 * 欢迎配置引导气球通知：项目打开时若配置未完成或开发者姓名为空，弹气球引导用户去配置。
 * @author shenwl
 * @date 2026/07/12
 */
class WelcomeNotifier : StartupActivity.DumbAware {

    override fun runActivity(project: Project) {
        val settings = ImsProjectSettings.getInstance(project)
        val devNameBlank = ImsGlobalSettings.getInstance().developerName.isBlank()
        // 每项目仅弹一次；配置已完整且姓名非空则无需引导
        if (settings.welcomeShown) return
        if (settings.isConfigured() && !devNameBlank) return

        ApplicationManager.getApplication().invokeLater {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("IMS V6 Code Assistant")
                .createNotification(
                    ImsBundle.message("welcome.title"),
                    ImsBundle.message("welcome.message"),
                    NotificationType.INFORMATION
                )
            // 「使用说明」改为打开在线插件手册
            notification.addAction(object : NotificationAction(ImsBundle.message("welcome.action.doc")) {
                override fun actionPerformed(e: AnActionEvent, n: Notification) {
                    BrowserUtil.browse(MANUAL_URL)
                    n.expire()
                }
            })
            notification.notify(project)
            // 通知成功抛出后再置位，避免 invokeLater 回调异常时状态被永久锁死、引导永远不再弹
            settings.welcomeShown = true
        }
    }

    private companion object {
        const val MANUAL_URL = "https://custom-sh.github.io/v6-code-assistant-manual/manual.html"
    }
}
