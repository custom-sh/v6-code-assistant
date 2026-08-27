package com.ims.code.helper.config

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer
import com.ims.code.helper.db.DatabaseConfig
import com.ims.code.helper.db.DatabaseConnection
import com.ims.code.helper.db.DatabasePropertiesHelper
import com.ims.code.helper.generator.StatDictionaryService
import com.ims.code.helper.util.IdeaSettingsHelper
import com.ims.code.helper.util.ImsCodeHelper

/**
 * 全局设置应用器
 * @author shenwl
 * @date 2026/07/12
 */
class GlobalSettingsApplier : StartupActivity.DumbAware {

    override fun runActivity(project: Project) {
        val g = ImsGlobalSettings.getInstance()
        Disposer.register(project) { StatDictionaryService.release(project) }

        // 预热 STAT 本地字典：server 目录有效时异步解析平台 sources jar，
        // 避免首次生成代码时卡顿。jar 不存在则静默跳过。
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            StatDictionaryService.warmup(project)
        }

        // 其它初始化（DB properties 同步 + 全局设置应用）整体走后台线程：
        // syncDatabaseFromProperties 有文件 IO，applyProjectSdk 遍历所有 module 也不轻。
        // IdeaSettingsHelper 内部的写操作会自己包 WriteAction，从后台调用不会破坏线程契约。
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            syncDatabaseFromProperties(project)
            applyGlobalSettings(project, g)
        }
    }

    private fun applyGlobalSettings(project: Project, g: ImsGlobalSettings) {
        // 仅当全局值非空且与项目当前值不同时才执行写入
        g.formatterTagsEnabled?.let { enabled ->
            applyIfDifferent(project, enabled, IdeaSettingsHelper::isFormatterTagsEnabled,
                IdeaSettingsHelper::setFormatterTagsEnabled)
        }

        g.encoding?.let { encoding ->
            if (encoding.isNotBlank()) {
                var needApply = encoding != IdeaSettingsHelper.getGlobalEncoding()
                if (!needApply) {
                    needApply = encoding != IdeaSettingsHelper.getProjectEncoding(project)
                }
                if (needApply) {
                    IdeaSettingsHelper.setGlobalEncoding(encoding)
                    IdeaSettingsHelper.setProjectEncoding(project, encoding)
                    IdeaSettingsHelper.setPropertiesEncoding(encoding)
                }
            }
        }

        g.projectSdk?.let { sdkName ->
            applyIfDifferent(project, sdkName, IdeaSettingsHelper::getProjectSdkName,
                IdeaSettingsHelper::applyProjectSdk)
        }

        g.mavenHome?.let { path ->
            applyIfDifferent(project, path, IdeaSettingsHelper::getMavenHome,
                IdeaSettingsHelper::setMavenHome)
        }

        // Maven user settings / local repo 仅在勾选 Override 且非空时写回 IDEA，
        // 否则保持 IDEA 默认（与面板 Override 复选框语义一致）
        if (g.mavenUserSettingsOverride == true) {
            g.mavenUserSettings?.let { path ->
                applyIfDifferent(project, path, IdeaSettingsHelper::getMavenUserSettings,
                    IdeaSettingsHelper::setMavenUserSettings)
            }
        }

        if (g.mavenLocalRepoOverride == true) {
            g.mavenLocalRepo?.let { path ->
                applyIfDifferent(project, path, IdeaSettingsHelper::getMavenLocalRepository,
                    IdeaSettingsHelper::setMavenLocalRepository)
            }
        }
    }

    /** 通用的"有值且与当前不同才写入"模式 */
    private fun applyIfDifferent(
        project: Project, value: String,
        getter: (Project) -> String,
        setter: (Project, String) -> Unit
    ) {
        if (value.isNotBlank() && value != getter(project)) {
            setter(project, value)
        }
    }

    /** 布尔版本 */
    private fun applyIfDifferent(
        project: Project, value: Boolean,
        getter: (Project) -> Boolean,
        setter: (Project, Boolean) -> Unit
    ) {
        if (value != getter(project)) {
            setter(project, value)
        }
    }

    /**
     * 启动时从 server/application.properties 反同步数据库配置到 [DatabaseConfig]。
     *
     * 场景：首次打开项目时面板连接列表为空、「切换数据库」右键菜单灰色禁用。
     * 此处读取项目实际在用的数据源，若面板已有匹配连接则置顶并刷新密码，
     * 否则按 properties 内容新建一条连接，使菜单立即可用。逻辑与
     * [com.ims.code.helper.ui.ImsDatabaseSettingsPanel.autoReadFromProjectAndPlaceFirst] 一致。
     *
     * serverPath 为空时先调 [ImsCodeHelper.autoDetectModulePaths] 探测并回填。
     */
    private fun syncDatabaseFromProperties(project: Project) {
        val settings = ImsProjectSettings.getInstance(project)
        var serverPath = settings.serverPath
        if (serverPath.isBlank()) {
            val (autoServer, _, _) = ImsCodeHelper.autoDetectModulePaths(project)
            if (autoServer.isBlank()) return
            serverPath = autoServer
            settings.serverPath = autoServer
        }

        val raw = DatabasePropertiesHelper.readFromServerPath(serverPath) ?: return
        val (host, port, database) = DatabasePropertiesHelper.parseJdbcUrl(raw.jdbcUrl, raw.databaseType)
        // host 解析不到（不规范 JDBC URL）则不取，不兜底默认值
        if (host.isBlank()) return
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            applyDatabaseConfig(project, raw, host, port, database)
        }
    }

    private fun applyDatabaseConfig(
        project: Project,
        raw: DatabasePropertiesHelper.RawDbConfig,
        host: String,
        port: String,
        database: String
    ) {
        val config = DatabaseConfig.getInstance(project)

        val matchIdx = config.connections.indexOfFirst { c ->
            c.databaseType == raw.databaseType &&
                c.host == host &&
                c.port == port &&
                c.database == database &&
                c.username == raw.username
        }
        if (matchIdx >= 0) {
            if (matchIdx != 0) {
                val conn = config.connections.removeAt(matchIdx)
                config.connections.add(0, conn)
            }
            for (c in config.connections) c.isActive = (c.name == config.connections[0].name)
            config.lastSyncedConnection = config.connections[0].name
            SecretStore.setDbPassword(project, config.connections[0].name, raw.password)
        } else {
            val baseName = raw.databaseType.lowercase() + "_" + database.ifBlank { "default" }
            var newName = baseName
            var n = 1
            while (config.connections.any { it.name == newName }) {
                n++
                newName = "${baseName}_$n"
            }
            val newConn = DatabaseConnection().apply {
                name = newName
                databaseType = raw.databaseType
                this.host = host
                this.port = port
                this.database = database
                username = raw.username
                maximumPoolSize = raw.maximumPoolSize
                isActive = true
            }
            config.connections.add(0, newConn)
            config.lastSyncedConnection = newName
            SecretStore.setDbPassword(project, newName, raw.password)
        }
    }
}
