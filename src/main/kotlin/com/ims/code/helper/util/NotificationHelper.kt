package com.ims.code.helper.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * 通知工具
 * 统一管理插件的通知提示
 */
object NotificationHelper {

    private const val NOTIFICATION_GROUP_ID = "V6 Code Assistant"

    fun info(project: Project?, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }

    fun warning(project: Project?, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, NotificationType.WARNING)
            .notify(project)
    }

    fun error(project: Project?, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, NotificationType.ERROR)
            .notify(project)
    }
}
