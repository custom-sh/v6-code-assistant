package com.ims.code.helper.config

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.ims.code.helper.ui.ImsProjectSettingsPanel
import com.ims.code.helper.db.DatabaseConfig
import com.ims.code.helper.scan.config.ScanConfig
import com.ims.code.helper.util.IdeaSettingsHelper
import com.ims.code.helper.util.ImsBundle
import com.ims.code.helper.util.ImsCodeHelper
import javax.swing.JComponent

internal fun resolveMavenSetting(configuredValue: String?, ideaValue: () -> String): String =
    configuredValue?.takeIf { it.isNotBlank() } ?: ideaValue()

internal fun modulePortChanged(currentPath: String, currentPort: Int, newPath: String, newPort: Int): Boolean =
    currentPath != newPath || currentPort != newPort

/**
 * 项目配置入口
 * @author shenwl
 * @date 2026/07/12
 */
class ImsProjectConfigurable(private val project: Project) : Configurable {

    private var panel: ImsProjectSettingsPanel? = null

    override fun getDisplayName(): String {
        return ImsBundle.message("configurable.display.name")
    }

    override fun createComponent(): JComponent? {
        // 索引中：返回纯提示面板（不弹窗、不阻塞索引、不做任何 PSI/文件 I/O）
        if (com.intellij.openapi.project.DumbService.isDumb(project)) {
            return createIndexingPlaceholder()
        }
        panel = ImsProjectSettingsPanel(project)
        // 普通 Apply 与一键初始化分别保存各自需要的配置，避免初始化触发无关的重型设置写入。
        panel!!.onApplyRequest = { applyAndReport() }
        panel!!.onInitializationApplyRequest = { applyInitializationAndReport() }
        return panel!!.createPanel()
    }

    /** 仅保存一键初始化所需的项目字段，避免触发数据库、扫描和 IDEA 全局设置写入。 */
    private fun applyInitializationAndReport(): Boolean {
        val p = panel ?: return false
        val developerName = p.getDeveloperName()
        if (developerName.isBlank()) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                ImsBundle.message("settings.developer.name.empty"),
                ImsBundle.message("settings.developer.name.empty.title")
            )
            return false
        }
        val projectSettings = ImsProjectSettings.getInstance(project)
        val globalSettings = ImsGlobalSettings.getInstance()
        val templateChanged = globalSettings.codeTemplateEnabled != p.isCodeTemplateEnabled()
        globalSettings.developerName = developerName
        globalSettings.codeTemplateEnabled = p.isCodeTemplateEnabled()
        if (templateChanged) applyCodeTemplate(p.isCodeTemplateEnabled(), p.getServerPath())
        projectSettings.projectCode = p.getProjectCode()
        projectSettings.projectName = p.getProjectName()
        projectSettings.serverPath = p.getServerPath()
        projectSettings.webPath = p.getWebPath()
        projectSettings.pdaPath = p.getPdaPath()
        projectSettings.clientPath = p.getClientPath()
        return true
    }

    /**
     * 索引期间显示的占位面板：纯 Swing 组件，零 I/O 操作，绝不阻塞索引。
     * 索引完成后用户关闭设置重新打开即可正常使用。
     *
     * 不使用 HTML 渲染（Swing HTML 不支持 text-align、CSS3 属性易 NPE），
     * 改用纯 Swing 组件 + JBColor 主题自适应颜色。
     */
    private fun createIndexingPlaceholder(): JComponent {
        // 主题自适应颜色
        val titleColor = com.intellij.ui.JBColor(java.awt.Color(0xE68A00), java.awt.Color(0xFFB84D))
        val hintColor = com.intellij.ui.JBColor(java.awt.Color.GRAY, java.awt.Color(0x999999))
        val subColor = com.intellij.ui.JBColor(java.awt.Color(0xAAAAAA), java.awt.Color(0x777777))

        // 图标
        val iconLabel = javax.swing.JLabel("⏳").apply {
            font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 48)
            horizontalAlignment = javax.swing.SwingConstants.CENTER
            alignmentX = 0.5f
        }
        // 标题
        val titleLabel = javax.swing.JLabel("IDEA 正在构建索引 / IDEA is building indexes").apply {
            font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 16)
            foreground = titleColor
            horizontalAlignment = javax.swing.SwingConstants.CENTER
            alignmentX = 0.5f
        }
        // 提示
        val hintLabel = javax.swing.JLabel("索引完成后请关闭设置窗口，重新打开插件配置即可正常使用").apply {
            font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13)
            foreground = hintColor
            horizontalAlignment = javax.swing.SwingConstants.CENTER
            alignmentX = 0.5f
        }
        val hintLabelEn = javax.swing.JLabel("Please close the settings window and reopen after indexing completes").apply {
            font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13)
            foreground = hintColor
            horizontalAlignment = javax.swing.SwingConstants.CENTER
            alignmentX = 0.5f
        }
        // 副标题
        val subLabel = javax.swing.JLabel("索引进行中 — 设置暂时不可用 / Indexing in progress — settings temporarily unavailable").apply {
            font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11)
            foreground = subColor
            horizontalAlignment = javax.swing.SwingConstants.CENTER
            alignmentX = 0.5f
        }

        // 垂直居中排列
        val content = javax.swing.JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            isOpaque = false
            add(javax.swing.Box.createVerticalGlue())
            add(iconLabel)
            add(javax.swing.Box.createVerticalStrut(16))
            add(titleLabel)
            add(javax.swing.Box.createVerticalStrut(12))
            add(hintLabel)
            add(javax.swing.Box.createVerticalStrut(4))
            add(hintLabelEn)
            add(javax.swing.Box.createVerticalStrut(8))
            add(subLabel)
            add(javax.swing.Box.createVerticalGlue())
        }
        return javax.swing.JPanel(java.awt.BorderLayout()).apply {
            border = com.intellij.util.ui.JBUI.Borders.empty(60, 40)
            add(content, java.awt.BorderLayout.CENTER)
        }
    }

    override fun isModified(): Boolean {
        val projectSettings = ImsProjectSettings.getInstance(project)
        val globalSettings = ImsGlobalSettings.getInstance()
        val p = panel ?: return false

        return p.getLanguage() != globalSettings.language ||
                p.getDeveloperName() != globalSettings.developerName ||
                p.isJavaSoftWrapEnabled() != IdeaSettingsHelper.isJavaSoftWrapEnabled() ||
                p.isFormatterOnOffEnabled() != (globalSettings.formatterTagsEnabled ?: true) ||
                p.isOptimizeImportsOnTheFlyEnabled() != (globalSettings.optimizeImportsOnTheFly ?: true) ||
                p.getEncoding() != (globalSettings.encoding ?: IdeaSettingsHelper.getGlobalEncoding()) ||
                p.getProjectSdk() != (globalSettings.projectSdk ?: IdeaSettingsHelper.getProjectSdkName(project)) ||
                p.getMavenHome() != resolveMavenSetting(globalSettings.mavenHome) {
                    IdeaSettingsHelper.getMavenHome(project)
                } ||
                p.getMavenUserSettings() != resolveMavenSetting(globalSettings.mavenUserSettings) {
                    IdeaSettingsHelper.getMavenUserSettings(project)
                } ||
                p.getMavenLocalRepo() != resolveMavenSetting(globalSettings.mavenLocalRepo) {
                    IdeaSettingsHelper.getMavenLocalRepository(project)
                } ||
                p.isMavenUserSettingsOverride() != (globalSettings.mavenUserSettingsOverride ?: false) ||
                p.isMavenLocalRepoOverride() != (globalSettings.mavenLocalRepoOverride ?: false) ||
                p.isCodeTemplateEnabled() != globalSettings.codeTemplateEnabled ||
                p.isProjectModeSelected() != ScanConfig.getInstance().projectMode ||
                p.getProjectCode() != projectSettings.projectCode ||
                p.getProjectName() != projectSettings.projectName ||
                p.getServerPath() != projectSettings.serverPath ||
                p.getWebPath() != projectSettings.webPath ||
                p.getPdaPath() != projectSettings.pdaPath ||
                p.getClientPath() != projectSettings.clientPath ||
                p.getPlatformSourcePath() != projectSettings.platformSourcePath ||
                p.getServerPort() != projectSettings.serverPort ||
                p.getWebPort() != projectSettings.webPort ||
                p.getPdaPort() != projectSettings.pdaPort ||
                p.getClientPort() != projectSettings.clientPort ||
                p.getAiConfigurations() != globalSettings.aiConfigurations ||
                p.getAiApiKeys() != globalSettings.aiConfigurations.associate {
                    it.id to SecretStore.getAiApiKey(it.id)
                } ||
                p.getScanPanel().isModified() ||
                p.getDatabasePanel().isModified(DatabaseConfig.getInstance(project))
    }

    override fun apply() {
        applyAndReport()
    }

    /**
     * 执行保存并返回是否成功。
     * 失败（开发者姓名空、路径非法等）时已弹错误提示并 [return]，返回 false。
     * 供 [apply] 与「一键初始模板」前置保存（[ImsProjectSettingsPanel.onApplyRequest]）共用。
     */
    private fun applyAndReport(): Boolean {
        val projectSettings = ImsProjectSettings.getInstance(project)
        val globalSettings = ImsGlobalSettings.getInstance()
        val p = panel ?: return false

        // 开发者姓名：保存前校验必填，空则弹错误对话框并中止保存
        val developerName = p.getDeveloperName()
        if (developerName.isBlank()) {
            // 空则弹错误对话框并中止保存（不再抛 ConfigurationException，避免底部重复显示“保存失败”）
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                ImsBundle.message("settings.developer.name.empty"),
                ImsBundle.message("settings.developer.name.empty.title")
            )
            return false
        }

        // 路径合法性校验：三端路径必须非空且为有效目录；Maven 路径非空时校验存在性/类型。
        // 不合法则汇总弹窗、中止保存（与开发者姓名校验同风格）。
        val invalidPaths = p.collectInvalidPaths(p.isProjectModeSelected())
        if (invalidPaths.isNotEmpty()) {
            val lang = p.getLanguage()
            val detail = invalidPaths.joinToString("\n") { (label, err) -> "- $label：$err" }
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                ImsBundle.messageWith(lang, "settings.path.apply.invalid") + "\n" + detail,
                ImsBundle.messageWith(lang, "settings.path.apply.invalid.title")
            )
            return false
        }

        val language = p.getLanguage()
        val encoding = p.getEncoding()
        val formatterTagsEnabled = p.isFormatterOnOffEnabled()
        val optimizeImportsOnTheFly = p.isOptimizeImportsOnTheFlyEnabled()
        val projectSdk = p.getProjectSdk()
        val mavenHome = p.getMavenHome()
        val mavenUserSettings = p.getMavenUserSettings()
        val mavenLocalRepo = p.getMavenLocalRepo()
        val mavenUserSettingsOverride = p.isMavenUserSettingsOverride()
        val mavenLocalRepoOverride = p.isMavenLocalRepoOverride()
        val javaSoftWrapEnabled = p.isJavaSoftWrapEnabled()
        val codeTemplateEnabled = p.isCodeTemplateEnabled()
        val projectMode = p.isProjectModeSelected()
        val projectCode = p.getProjectCode()
        val projectName = p.getProjectName()
        val serverPath = p.getServerPath()
        val webPath = p.getWebPath()
        val pdaPath = p.getPdaPath()
        val clientPath = p.getClientPath()
        val platformSourcePath = p.getPlatformSourcePath()
        val serverPort = p.getServerPort()
        val webPort = p.getWebPort()
        val pdaPort = p.getPdaPort()
        val clientPort = p.getClientPort()
        val aiConfigurations = p.getAiConfigurations()
        val aiApiKeys = p.getAiApiKeys()
        val incompleteAiConfiguration = findIncompleteAiConfiguration(aiConfigurations, aiApiKeys)
        if (incompleteAiConfiguration != null) {
            val configurationName = incompleteAiConfiguration.name.ifBlank { incompleteAiConfiguration.provider }
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                ImsBundle.messageWith(language, "settings.ai.validation.incomplete", configurationName),
                ImsBundle.messageWith(language, "settings.ai.validation.incomplete.title")
            )
            return false
        }

        val encodingChanged = encoding != (globalSettings.encoding ?: IdeaSettingsHelper.getGlobalEncoding())
        val formatterChanged = formatterTagsEnabled != (globalSettings.formatterTagsEnabled ?: true)
        val optimizeImportsChanged = optimizeImportsOnTheFly != (globalSettings.optimizeImportsOnTheFly ?: true)
        val projectSdkChanged = projectSdk != (globalSettings.projectSdk ?: IdeaSettingsHelper.getProjectSdkName(project))
        val mavenHomeChanged = mavenHome != resolveMavenSetting(globalSettings.mavenHome) {
            IdeaSettingsHelper.getMavenHome(project)
        }
        val mavenUserSettingsChanged = mavenUserSettings != resolveMavenSetting(globalSettings.mavenUserSettings) {
            IdeaSettingsHelper.getMavenUserSettings(project)
        }
        val mavenLocalRepoChanged = mavenLocalRepo != resolveMavenSetting(globalSettings.mavenLocalRepo) {
            IdeaSettingsHelper.getMavenLocalRepository(project)
        }
        val mavenUserSettingsOverrideChanged =
            mavenUserSettingsOverride != (globalSettings.mavenUserSettingsOverride ?: false)
        val mavenLocalRepoOverrideChanged =
            mavenLocalRepoOverride != (globalSettings.mavenLocalRepoOverride ?: false)
        val javaSoftWrapChanged = javaSoftWrapEnabled != IdeaSettingsHelper.isJavaSoftWrapEnabled()
        val codeTemplateChanged = codeTemplateEnabled != globalSettings.codeTemplateEnabled
        val projectModeChanged = projectMode != ScanConfig.getInstance().projectMode
        val languageChanged = language != globalSettings.language
        val developerNameChanged = developerName != globalSettings.developerName
        val aiConfigurationsChanged = aiConfigurations != globalSettings.aiConfigurations
        val storedAiApiKeys = globalSettings.aiConfigurations.associate { it.id to SecretStore.getAiApiKey(it.id) }
        val removedConfigurationIds = globalSettings.aiConfigurations.map { it.id }.toSet() -
            aiConfigurations.map { it.id }.toSet()
        val projectCodeChanged = projectCode != projectSettings.projectCode
        val projectNameChanged = projectName != projectSettings.projectName
        val serverPathChanged = serverPath != projectSettings.serverPath
        val webPathChanged = webPath != projectSettings.webPath
        val pdaPathChanged = pdaPath != projectSettings.pdaPath
        val clientPathChanged = clientPath != projectSettings.clientPath
        val platformSourcePathChanged = platformSourcePath != projectSettings.platformSourcePath
        val serverPortChanged = modulePortChanged(
            projectSettings.serverPath, projectSettings.serverPort, serverPath, serverPort
        )
        val webPortChanged = modulePortChanged(
            projectSettings.webPath, projectSettings.webPort, webPath, webPort
        )
        val pdaPortChanged = modulePortChanged(
            projectSettings.pdaPath, projectSettings.pdaPort, pdaPath, pdaPort
        )
        val clientPortChanged = modulePortChanged(
            projectSettings.clientPath, projectSettings.clientPort, clientPath, clientPort
        )
        val dependencyPathsChanged = serverPathChanged || clientPathChanged
        val projectConfigurationChanged = projectCodeChanged || projectNameChanged || serverPathChanged ||
            webPathChanged || pdaPathChanged || clientPathChanged || serverPortChanged || webPortChanged ||
            pdaPortChanged || clientPortChanged
        val scanChanged = p.getScanPanel().isModified()
        val databaseConfig = DatabaseConfig.getInstance(project)
        val databaseChanged = p.getDatabasePanel().isModified(databaseConfig)

        // === 全局生效设置：仅应用实际发生变化的项目 ===
        if (encodingChanged) {
            globalSettings.encoding = encoding.ifBlank { null }
            if (encoding.isNotBlank()) {
                IdeaSettingsHelper.setGlobalEncoding(encoding)
                IdeaSettingsHelper.setProjectEncoding(project, encoding)
                IdeaSettingsHelper.setPropertiesEncoding(encoding)
            }
        }
        if (formatterChanged) {
            globalSettings.formatterTagsEnabled = formatterTagsEnabled
            IdeaSettingsHelper.setFormatterTagsEnabled(project, formatterTagsEnabled)
        }
        if (optimizeImportsChanged) {
            globalSettings.optimizeImportsOnTheFly = optimizeImportsOnTheFly
            IdeaSettingsHelper.setOptimizeImportsOnTheFlyEnabled(project, optimizeImportsOnTheFly)
        }
        if (projectSdkChanged) {
            globalSettings.projectSdk = projectSdk.ifBlank { null }
            if (projectSdk.isNotBlank()) IdeaSettingsHelper.applyProjectSdk(project, projectSdk)
        }
        if (mavenHomeChanged) {
            globalSettings.mavenHome = mavenHome.ifBlank { null }
            if (mavenHome.isNotBlank()) IdeaSettingsHelper.setMavenHome(project, mavenHome)
        }
        if (mavenUserSettingsChanged || mavenUserSettingsOverrideChanged) {
            globalSettings.mavenUserSettings = mavenUserSettings.ifBlank { null }
            globalSettings.mavenUserSettingsOverride = mavenUserSettingsOverride
            if (mavenUserSettingsOverride && mavenUserSettings.isNotBlank()) {
                IdeaSettingsHelper.setMavenUserSettings(project, mavenUserSettings)
            }
        }
        if (mavenLocalRepoChanged || mavenLocalRepoOverrideChanged) {
            globalSettings.mavenLocalRepo = mavenLocalRepo.ifBlank { null }
            globalSettings.mavenLocalRepoOverride = mavenLocalRepoOverride
            if (mavenLocalRepoOverride && mavenLocalRepo.isNotBlank()) {
                IdeaSettingsHelper.setMavenLocalRepository(project, mavenLocalRepo)
            }
        }
        if (javaSoftWrapChanged) IdeaSettingsHelper.setJavaSoftWrapEnabled(javaSoftWrapEnabled)
        if (codeTemplateChanged) globalSettings.codeTemplateEnabled = codeTemplateEnabled
        if (projectModeChanged) ScanConfig.getInstance().projectMode = projectMode
        if (codeTemplateChanged || codeTemplateEnabled && (developerNameChanged || serverPathChanged)) {
            applyCodeTemplate(codeTemplateEnabled, serverPath)
        }

        if (languageChanged) {
            globalSettings.language = language
            ImsBundle.clearCache()
            project.messageBus.syncPublisher(SettingsChangeNotifier.TOPIC).onLanguageChanged()
        }
        if (developerNameChanged) globalSettings.developerName = developerName

        aiConfigurations.forEach { configuration ->
            val newApiKey = aiApiKeys[configuration.id].orEmpty()
            if (newApiKey != storedAiApiKeys[configuration.id].orEmpty()) {
                SecretStore.setAiApiKey(configuration.id, newApiKey)
            }
        }
        removedConfigurationIds.forEach(SecretStore::removeAiApiKey)
        if (aiConfigurationsChanged) {
            globalSettings.aiConfigurations = aiConfigurations.map { it.copy() }.toMutableList()
        }
        // === 项目级配置：端口文件、依赖、扫描和数据库均按变更提交 ===
        if (projectCodeChanged) projectSettings.projectCode = projectCode
        if (projectNameChanged) projectSettings.projectName = projectName
        if (serverPathChanged) projectSettings.serverPath = serverPath
        if (webPathChanged) projectSettings.webPath = webPath
        if (pdaPathChanged) projectSettings.pdaPath = pdaPath
        if (clientPathChanged) projectSettings.clientPath = clientPath
        if (platformSourcePathChanged) {
            projectSettings.platformSourcePath = platformSourcePath
            com.ims.code.helper.importer.PlatformFeatureSourceIndex.getInstance(project).invalidate()
        }
        if (serverPortChanged) projectSettings.serverPort = serverPort
        if (webPortChanged) projectSettings.webPort = webPort
        if (pdaPortChanged) projectSettings.pdaPort = pdaPort
        if (clientPortChanged) projectSettings.clientPort = clientPort

        if (serverPortChanged && serverPath.isNotBlank()) ImsCodeHelper.setServerPort(serverPath, serverPort)
        if (webPortChanged && webPath.isNotBlank()) ImsCodeHelper.setWebPort(webPath, webPort)
        if (pdaPortChanged && pdaPath.isNotBlank()) ImsCodeHelper.setPdaPort(pdaPath, pdaPort)
        if (clientPortChanged && clientPath.isNotBlank()) ImsCodeHelper.setClientPort(clientPath, clientPort)
        if (dependencyPathsChanged) p.attachDependencyAttachments()
        if (scanChanged) p.getScanPanel().save()
        if (databaseChanged) p.getDatabasePanel().saveToDatabaseConfig(databaseConfig)

        // 仅项目配置发生变化时更新欢迎提示，AI-only Apply 不触碰项目状态。
        if (projectConfigurationChanged && !projectSettings.isConfigured()) {
            projectSettings.welcomeShown = false
        }
        return true
    }

    override fun reset() {
        val p = panel ?: return
        val projectSettings = ImsProjectSettings.getInstance(project)
        val g = ImsGlobalSettings.getInstance()

        ImsCodeHelper.clearVersionCache()
        p.beginBatchUpdate()

        // 语言
        p.setLanguage(g.language)
        // 开发者姓名
        p.setDeveloperName(g.developerName)
        // 编辑器
        p.setJavaSoftWrapEnabled(IdeaSettingsHelper.isJavaSoftWrapEnabled())
        // 全局优先 → IDEA 当前值兜底；未配置（null）时默认勾选
        p.setFormatterOnOffEnabled(g.formatterTagsEnabled ?: true)
        p.setOptimizeImportsOnTheFlyEnabled(g.optimizeImportsOnTheFly ?: true)
        p.setEncoding(g.encoding ?: IdeaSettingsHelper.getGlobalEncoding())
        p.setProjectSdk(g.projectSdk ?: IdeaSettingsHelper.getProjectSdkName(project))
        // Maven 路径：插件已配置的值优先，否则回显 IDEA 当前配置。
        p.setMavenHome(resolveMavenSetting(g.mavenHome) { IdeaSettingsHelper.getMavenHome(project) })
        p.setMavenUserSettings(resolveMavenSetting(g.mavenUserSettings) {
            IdeaSettingsHelper.getMavenUserSettings(project)
        })
        p.setMavenLocalRepo(resolveMavenSetting(g.mavenLocalRepo) {
            IdeaSettingsHelper.getMavenLocalRepository(project)
        })
        p.setMavenUserSettingsOverride(g.mavenUserSettingsOverride ?: false)
        p.setMavenLocalRepoOverride(g.mavenLocalRepoOverride ?: false)
        // 代码注释模板：以全局开关为准回显（不读 File Header 实际内容，避免误判用户手改）
        p.setCodeTemplateEnabled(g.codeTemplateEnabled)
        p.setProjectModeSelected(ScanConfig.getInstance().projectMode)
        // 恢复 Override 勾选后，按勾选状态切换路径框可编辑性
        p.applyMavenOverrideEnabled()
        // 项目级
        p.setProjectCode(projectSettings.projectCode)
        p.setProjectName(projectSettings.projectName)
        p.setServerPath(projectSettings.serverPath)
        p.setWebPath(projectSettings.webPath)
        p.setPdaPath(projectSettings.pdaPath)
        p.setClientPath(projectSettings.clientPath)
        p.setPlatformSourcePath(projectSettings.platformSourcePath)
        p.setServerPort(projectSettings.serverPort)
        p.setWebPort(projectSettings.webPort)
        p.setPdaPort(projectSettings.pdaPort)
        p.setClientPort(projectSettings.clientPort)
        p.setAiConfigurations(
            g.aiConfigurations,
            g.aiConfigurations.associate { it.id to SecretStore.getAiApiKey(it.id) }
        )
        p.autoFillFromOpenProject()
        p.getScanPanel().load()
        p.getDatabasePanel().loadFromDatabaseConfig(DatabaseConfig.getInstance(project))

        p.endBatchUpdate()
    }

    override fun disposeUIResources() {
        panel = null
    }

    /**
     * 代码注释模板写/还原。勾选时向 IDEA File Header 写入版权注释 + 类注释模板；
     * 取消时还原。版本号取平台版本（Server 端 lib 解析），读不到则模板里留 ${VERSION} 变量提示。
     * FileTemplateManager 写操作需在 write action 内执行；getServerPlatformVersion 涉及文件 IO，
     * 先在 write action 外完成再进入。
     */
    private fun applyCodeTemplate(enabled: Boolean, serverPath: String) {
        if (enabled) {
            val platformVersion = ImsCodeHelper.getServerPlatformVersion(project, serverPath)
            com.intellij.openapi.application.WriteAction.run<Throwable> {
                com.ims.code.helper.util.CodeTemplateHelper.applyTemplate(project, platformVersion)
            }
        } else {
            com.intellij.openapi.application.WriteAction.run<Throwable> {
                com.ims.code.helper.util.CodeTemplateHelper.revertTemplate(project)
            }
        }
    }
}
