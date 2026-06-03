package com.ims.code.helper

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * 插件入口类
 * 负责插件初始化和生命周期管理
 */
class ImsCodeHelperPlugin : StartupActivity {

    override fun runActivity(project: Project) {
        // 插件启动时的初始化逻辑
        // 当前阶段为空实现，后续可添加：
        // - 项目规范自动分析
        // - 通知提示
        // - 后台任务初始化
    }
}
