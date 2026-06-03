package com.ims.code.helper.util

import com.intellij.DynamicBundle
import com.ims.code.helper.config.ImsGlobalSettings
import org.jetbrains.annotations.PropertyKey
import java.util.Locale
import java.util.ResourceBundle

/**
 * 国际化消息工具
 * 支持三种模式：ide=跟随IDEA, zh=中文, en=英文
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

    /** 按当前已保存的语言设置取文案 */
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        format(bundleFor(resolveLocale()).getString(key), params)

    /** 按指定语言("ide"/"zh"/"en")取文案，用于面板实时预览尚未保存的语言选择 */
    fun messageWith(lang: String, @PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        format(bundleFor(localeOf(lang)).getString(key), params)

    private fun format(value: String, params: Array<out Any>): String =
        if (params.isEmpty()) value else String.format(value, *params)

    private fun bundleFor(locale: Locale): ResourceBundle =
        bundles.getOrPut(locale) {
            ResourceBundle.getBundle(BUNDLE, locale, ImsBundle::class.java.classLoader, noFallback)
        }

    private fun resolveLocale(): Locale =
        localeOf(try { ImsGlobalSettings.getInstance().language } catch (e: Exception) { "ide" })

    private fun localeOf(lang: String): Locale = when (lang) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.ENGLISH
        // 跟随 IDEA：尝试从 IDEA 的 locale 获取
        "ide" -> try { DynamicBundle.getLocale() } catch (e: Exception) { Locale.getDefault() }
        else -> Locale.getDefault()
    }

    fun clearCache() {
        bundles.clear()
    }
}
