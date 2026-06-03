package com.ims.code.helper.config

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.ims.code.helper.ui.ImsProjectSettingsPanel
import com.ims.code.helper.util.ImsBundle
import javax.swing.JComponent

/**
 * 统一配置面板控制器
 * 语言切换：Apply 后生效，重新打开设置页面显示新语言
 */
class ImsProjectConfigurable(private val project: Project) : Configurable {

    private var panel: ImsProjectSettingsPanel? = null

    override fun getDisplayName(): String {
        return ImsBundle.message("configurable.display.name")
    }

    override fun createComponent(): JComponent {
        panel = ImsProjectSettingsPanel(project)
        return panel!!.createPanel()
    }

    override fun isModified(): Boolean {
        val projectSettings = ImsProjectSettings.getInstance(project)
        val globalSettings = ImsGlobalSettings.getInstance()
        val p = panel ?: return false

        return p.getLanguage() != globalSettings.language ||
                p.getProjectCode() != projectSettings.projectCode ||
                p.getProjectName() != projectSettings.projectName ||
                p.getServerPath() != projectSettings.serverPath ||
                p.getWebPath() != projectSettings.webPath ||
                p.getPdaPath() != projectSettings.pdaPath ||
                p.getAiProvider() != projectSettings.aiProvider ||
                p.getAiModel() != projectSettings.aiModel ||
                p.getApiKey() != projectSettings.apiKey ||
                p.getApiEndpoint() != projectSettings.apiEndpoint ||
                p.getExtraConfig() != projectSettings.apiExtraConfig
    }

    override fun apply() {
        val projectSettings = ImsProjectSettings.getInstance(project)
        val globalSettings = ImsGlobalSettings.getInstance()
        val p = panel ?: return

        // 语言设置（全局）
        globalSettings.language = p.getLanguage()
        ImsBundle.clearCache()

        // 项目配置
        projectSettings.projectCode = p.getProjectCode()
        projectSettings.projectName = p.getProjectName()
        projectSettings.serverPath = p.getServerPath()
        projectSettings.webPath = p.getWebPath()
        projectSettings.pdaPath = p.getPdaPath()
        projectSettings.aiProvider = p.getAiProvider()
        projectSettings.aiModel = p.getAiModel()
        projectSettings.apiKey = p.getApiKey()
        projectSettings.apiEndpoint = p.getApiEndpoint()
        projectSettings.apiExtraConfig = p.getExtraConfig()
    }

    override fun reset() {
        val projectSettings = ImsProjectSettings.getInstance(project)
        val globalSettings = ImsGlobalSettings.getInstance()
        val p = panel ?: return

        p.setLanguage(globalSettings.language)
        p.setProjectCode(projectSettings.projectCode)
        p.setProjectName(projectSettings.projectName)
        p.setServerPath(projectSettings.serverPath)
        p.setWebPath(projectSettings.webPath)
        p.setPdaPath(projectSettings.pdaPath)
        p.setAiProvider(projectSettings.aiProvider)
        p.setAiModel(projectSettings.aiModel)
        p.setApiKey(projectSettings.apiKey)
        p.setApiEndpoint(projectSettings.apiEndpoint)
        p.setExtraConfig(projectSettings.apiExtraConfig)

        // 行为 A：回填持久化值后，对仍为空的 server/web/pda 尝试从当前打开的项目自动识别
        p.autoFillFromOpenProject()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
