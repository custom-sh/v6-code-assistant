package com.ims.code.helper.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 全局 AI 模型配置
 * @author shenwl
 * @date 2026/08/04
 */
data class AiModelConfiguration(
    var id: String = "",
    var iconId: String = "custom",
    var name: String = "",
    var provider: String = "Custom",
    var protocol: String = "OpenAI-compatible",
    var endpoint: String = "",
    var model: String = "",
    var isDefault: Boolean = false,
    var timeoutSeconds: Int = 120
)

internal const val BUILT_IN_DEEPSEEK_CONFIGURATION_ID = "built-in-deepseek-default"
internal const val DEEPSEEK_PROVIDER = "DeepSeek"
internal const val DEEPSEEK_ENDPOINT = "https://api.deepseek.com"
internal const val DEEPSEEK_MODEL = "deepseek-v4-flash"
internal const val AGNES_PROVIDER = "Agnes AI"
internal const val AGNES_ENDPOINT = "https://api.agnes-ai.cn/v1"
internal const val AGNES_MODEL = "agnes-2.0-flash"

internal fun defaultAiModelConfigurations(): MutableList<AiModelConfiguration> = mutableListOf(
    AiModelConfiguration(
        id = BUILT_IN_DEEPSEEK_CONFIGURATION_ID,
        iconId = "deepseek",
        name = DEEPSEEK_PROVIDER,
        provider = DEEPSEEK_PROVIDER,
        endpoint = DEEPSEEK_ENDPOINT,
        model = DEEPSEEK_MODEL,
        isDefault = true
    )
)

internal fun normalizeAiModelConfigurations(
    configurations: List<AiModelConfiguration>
): MutableList<AiModelConfiguration> {
    if (configurations.isEmpty()) return mutableListOf()
    val defaultIndex = configurations.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
    return configurations.mapIndexed { index, configuration ->
        configuration.copy(isDefault = index == defaultIndex)
    }.toMutableList()
}

internal fun findIncompleteAiConfiguration(
    configurations: List<AiModelConfiguration>,
    apiKeys: Map<String, String>
): AiModelConfiguration? = configurations.firstOrNull { configuration ->
    val apiKey = apiKeys[configuration.id].orEmpty()
    val isUnconfiguredBuiltInTemplate =
        configuration.id == BUILT_IN_DEEPSEEK_CONFIGURATION_ID &&
            configuration.name == DEEPSEEK_PROVIDER &&
            configuration.provider == DEEPSEEK_PROVIDER &&
            configuration.endpoint == DEEPSEEK_ENDPOINT &&
            configuration.model == DEEPSEEK_MODEL &&
            apiKey.isBlank()

    !isUnconfiguredBuiltInTemplate &&
        (configuration.name.isBlank() ||
            configuration.endpoint.isBlank() ||
            configuration.model.isBlank() ||
            apiKey.isBlank())
}

/**
 * 项目级配置状态
 * @author shenwl
 * @date 2026/07/12
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

    // CS端项目路径（可选，对应 client 目录）
    var clientPath: String = ""

    // 平台功能源码库路径（可选，用于建立快速导入索引）
    var platformSourcePath: String = ""

    // === 端口配置 ===
    var serverPort: Int = 8080
    var webPort: Int = 3000
    var pdaPort: Int = 3001
    var clientPort: Int = 0

    // === File Encodings ===
    var encoding: String = ""

    // === 欢迎提示 ===
    // false=尚未弹过欢迎气球；true=已弹过。WelcomeNotifier 弹窗前置 true；
    // ImsProjectConfigurable.apply() 保存后若配置仍不完整则重置为 false。
    var welcomeShown: Boolean = false

    override fun getState(): ImsProjectSettings {
        return this
    }

    /**
     * 是否已完成项目级最小配置：项目编码 + 三端路径四项全非空。
     *
     * 首次打开新项目时 ims-code-helper.xml 不存在，全字段为空默认值 → 视为未配置，
     * 由 [com.ims.code.helper.util.ConfigGate] 统一拦截功能 Action，引导用户先配置。
     * 判据只看持久化值，不兜底自动探测——自动探测结果不落盘，不能替代用户主动确认。
     */
    fun isConfigured(): Boolean =
        projectCode.isNotBlank() && serverPath.isNotBlank() &&
            webPath.isNotBlank() && pdaPath.isNotBlank()

    /**
     * 项目编码兜底补值：当持久化的 [projectCode] 为空时，从项目文件重新读取一次。
     *
     * 场景：历史项目曾把项目编码锁为只读，保存后持久化值可能为空，导致 [isConfigured]
     * 恒为 false、门禁死循环（面板只读改不了、门禁非要它非空）。项目编码本就由项目文件
     * （ImsSystemDml / XxxColumnType）决定，这里补到即落盘，与面板自动回显行为一致。
     *
     * 名称同步补：取到项目名称则写入，取不到则用项目编码兜底。
     *
     * @return true 表示补值成功（且 [isConfigured] 此后满足）；false 表示读不到，调用方应引导配置
     */
    fun ensureProjectCode(project: com.intellij.openapi.project.Project): Boolean {
        if (projectCode.isNotBlank()) return true
        if (serverPath.isBlank()) return false
        val (code, name) = com.ims.code.helper.util.ImsCodeHelper
            .getProjectCodeAndName(project, serverPath)
        if (code.isBlank()) return false
        projectCode = code
        projectName = name.ifBlank { code }
        return true
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
 * 包含：语言偏好、Formatter、Maven、SDK、File Encodings
 * 设置为非空值后，每个项目打开时自动读取并作为面板默认值
 */
@State(
    name = "ImsGlobalSettings",
    storages = [Storage("ims-code-helper-global.xml")]
)
class ImsGlobalSettings : PersistentStateComponent<ImsGlobalSettings> {

    // 语言设置: "zh"=中文, "en"=英文, "ide"=跟随IDEA
    var language: String = "zh"

    // 开发者姓名（保存时必填）
    var developerName: String = ""

    // === 跨项目全局生效的设置（null = 未配置，面板回退读取 IDEA 当前值） ===
    var encoding: String? = null
    var formatterTagsEnabled: Boolean? = null
    var optimizeImportsOnTheFly: Boolean? = null
    var mavenHome: String? = null
    var mavenUserSettings: String? = null
    var mavenLocalRepo: String? = null
    /**
     * Override 勾选状态：勾上才把对应 Maven 路径写回 IDEA，不勾保持 IDEA 默认。
     * null = 旧版本未持久化该字段：迁移时若对应 Maven 路径非空则默认勾上
     * （保留旧版"非空即写回"行为），否则默认不勾。迁移后落为非 null。
     */
    var mavenUserSettingsOverride: Boolean? = null
    var mavenLocalRepoOverride: Boolean? = null
    var projectSdk: String? = null

    // 代码注释模板：勾选时向 IDEA File Header（File Template include）写入版权注释 + 类注释模板，
    // 新建类时自动生成。作者取 developerName，版本取平台版本，年份/日期取当前时间。
    // 唯一需要新建时输入的是类描述（IDEA 原生变量提示 ${DESCRIPTION}）。
    var codeTemplateEnabled: Boolean = false

    // API Key 按配置 id 单独保存到 PasswordSafe，不进入全局 XML。
    var aiConfigurations: MutableList<AiModelConfiguration> = defaultAiModelConfigurations()

    override fun getState(): ImsGlobalSettings {
        return this
    }

    override fun loadState(state: ImsGlobalSettings) {
        XmlSerializerUtil.copyBean(state, this)
        aiConfigurations = normalizeAiModelConfigurations(aiConfigurations)
        // 旧版本升级迁移：override 字段首次反序列化为 null。
        // 已配置过对应 Maven 路径的老用户默认勾上，保留旧版"非空即写回"行为；
        // 未配置过的不勾，与新版默认一致。
        if (mavenUserSettingsOverride == null) {
            mavenUserSettingsOverride = !mavenUserSettings.isNullOrBlank()
        }
        if (mavenLocalRepoOverride == null) {
            mavenLocalRepoOverride = !mavenLocalRepo.isNullOrBlank()
        }
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
