package com.ims.code.helper.util

import com.intellij.DynamicBundle
import com.ims.code.helper.config.ImsGlobalSettings
import org.jetbrains.annotations.PropertyKey
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * 国际化消息工具
 * @author shenwl
 * @date 2026/07/12
 */
object ImsBundle {

    private const val BUNDLE = "messages.ImsCodeHelperBundle"
    private val bundles = mutableMapOf<Locale, ResourceBundle>()

    // 禁用「回退到系统默认 locale」这一步：
    // 否则在中文系统上选 en（无 _en 文件）会被回退到 _zh，导致选 English 仍显示中文。
    // NoFallback 仍保留到 base bundle(ImsCodeHelperBundle.properties=英文) 的回退。
    private val noFallback = ResourceBundle.Control.getNoFallbackControl(
        ResourceBundle.Control.FORMAT_PROPERTIES
    )

    /**
     * 按当前已保存的语言设置取文案。
     *
     * key 缺失（规则漏配 `.name`/`.message` 等）时回退占位 `"!$key!"` 而非抛
     * `MissingResourceException`：[message] 在 Inspection 注册期、Settings 面板渲染期
     * 被高频调用，单条漏配不应拖垮整棵 Inspection 树/Settings 面板（见审查报告 §1.8）。
     */
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        format(bundleFor(resolveLocale()), key, params)

    /** 按指定语言("ide"/"zh"/"en")取文案，用于面板实时预览尚未保存的语言选择 */
    fun messageWith(lang: String, @PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        format(bundleFor(localeOf(lang)), key, params)

    private fun format(bundle: ResourceBundle, key: String, params: Array<out Any>): String {
        val value = try { bundle.getString(key) } catch (e: MissingResourceException) { "!$key!" }
        if (params.isEmpty()) return value
        // 资源串含未转义 % 时 String.format 抛 IllegalFormatException，会让 Inspection 树/Settings 面板渲染崩溃；
        // 失败回退原值（占位符原样保留），仅记 warn 便于发现漏配。
        return try {
            String.format(value, *params)
        } catch (e: java.util.IllegalFormatException) {
            LOG.warn("格式化文案失败 key=$key value=$value", e)
            value
        }
    }

    private fun bundleFor(locale: Locale): ResourceBundle =
        bundles.getOrPut(locale) {
            ResourceBundle.getBundle(BUNDLE, locale, ImsBundle::class.java.classLoader, noFallback)
        }

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ImsBundle::class.java)

    private fun resolveLocale(): Locale =
        localeOf(try { ImsGlobalSettings.getInstance().language } catch (e: Exception) {
            LOG.warn("读取语言设置失败，回退 ide", e); "ide"
        })

    private fun localeOf(lang: String): Locale = when (lang) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.ENGLISH
        // 跟随 IDEA：尝试从 IDEA 的 locale 获取
        "ide" -> try { DynamicBundle.getLocale() } catch (e: Exception) { LOG.warn("DynamicBundle.getLocale 失败", e); Locale.getDefault() }
        else -> Locale.getDefault()
    }

    fun clearCache() {
        bundles.clear()
    }

    /**
     * 由规则 shortName 拼 i18n key 的中段：去掉 "Ims" 前缀、首字母小写。
     * 显示名 key = `inspection.{displayNameSuffix(shortName)}.name`。
     *
     * 抽到此处为单一真相源（见审查报告 §1.9）：`ImsJavaInspectionBase.getDisplayName`
     * 与 `InspectionProfileHelper.displayNameLabel` 原各持一份逐字节复制的私有实现，
     * key 约定改一处易漏另一处，导致 Settings 树显示名与报告名不一致。
     */
    fun displayNameSuffix(ruleId: String): String {
        val rest = ruleId.removePrefix("Ims")
        if (rest.isEmpty()) return "unknown"
        return rest.substring(0, 1).lowercase() + rest.substring(1)
    }
}
