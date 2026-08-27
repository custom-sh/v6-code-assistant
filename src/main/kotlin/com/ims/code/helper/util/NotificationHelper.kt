package com.ims.code.helper.util

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * 通知消息工具
 * @author shenwl
 * @date 2026/07/12
 */
object NotificationHelper {

    private const val NOTIFICATION_GROUP_ID = "IMS V6 Code Assistant"

    fun info(
        project: Project?, title: String, content: String,
        actionText: String? = null, action: (() -> Unit)? = null
    ) = show(project, title, content, NotificationType.INFORMATION, actionText, action)

    fun warning(
        project: Project?, title: String, content: String,
        actionText: String? = null, action: (() -> Unit)? = null
    ) = show(project, title, content, NotificationType.WARNING, actionText, action)

    fun error(
        project: Project?, title: String, content: String,
        actionText: String? = null, action: (() -> Unit)? = null
    ) = show(project, title, content, NotificationType.ERROR, actionText, action)

    private fun show(
        project: Project?, title: String, content: String, type: NotificationType,
        actionText: String?, action: (() -> Unit)?
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
        if (actionText != null && action != null) {
            notification.addAction(object : NotificationAction(actionText) {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    action()
                }
            })
        }
        notification.notify(project)
    }
}
