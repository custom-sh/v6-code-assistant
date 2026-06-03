package com.ims.code.helper.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 项目级配置状态
 * 存储在 .idea/ 目录下，每个项目独立配置
 * 包含：项目编码、路径配置、AI 模型配置
 */
@State(
    name = "ImsProjectSettings",
    storages = [Storage("ims-code-helper.xml")]
)
class ImsProjectSettings : PersistentStateComponent<ImsProjectSettings> {

    // 项目编码（文本，如项目代号）
    var projectCode: String = ""

    // 项目名称
    var projectName: String = ""

    // 服务端项目路径
    var serverPath: String = ""

    // Web端项目路径
    var webPath: String = ""

    // PDA端项目路径
    var pdaPath: String = ""

    // === AI 模型配置 ===
    var aiProvider: String = "OpenAI"
    var aiModel: String = ""
    var apiKey: String = ""
    var apiEndpoint: String = ""
    var apiExtraConfig: Map<String, String> = mutableMapOf()

    override fun getState(): ImsProjectSettings {
        return this
    }

    override fun loadState(state: ImsProjectSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(project: Project): ImsProjectSettings {
            return project.getService(ImsProjectSettings::class.java)
        }
    }
}

/**
 * 全局配置（所有项目共享）
 * 包含：语言偏好
 */
@State(
    name = "ImsGlobalSettings",
    storages = [Storage("ims-code-helper-global.xml")]
)
class ImsGlobalSettings : PersistentStateComponent<ImsGlobalSettings> {

    // 语言设置: "zh"=中文, "en"=英文, "ide"=跟随IDEA
    var language: String = "zh"

    override fun getState(): ImsGlobalSettings {
        return this
    }

    override fun loadState(state: ImsGlobalSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): ImsGlobalSettings {
            return com.intellij.openapi.application.ApplicationManager.getApplication().getService(ImsGlobalSettings::class.java)
        }
    }
}

/**
 * 命名规范（写死在插件代码中，不暴露到配置面板）
 */
object NamingConventions {
    const val ENTITY_SUFFIX = "Entity"
    const val DTO_SUFFIX = "DTO"
    const val SERVICE_SUFFIX = "Service"
    const val SERVICE_IMPL_SUFFIX = "ServiceImpl"
    const val CONTROLLER_SUFFIX = "Controller"
    const val MAPPER_SUFFIX = "Mapper"
    const val BASE_PACKAGE = "com.ims"
}