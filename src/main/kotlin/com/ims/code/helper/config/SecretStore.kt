package com.ims.code.helper.config

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.project.Project

/**
 * 加密存储工具
 * @author shenwl
 * @date 2026/07/12
 */
object SecretStore {

    private const val SERVICE_NAME = "V6CodeAssistant"

    private fun credentialAttributes(serviceName: String): CredentialAttributes {
        val constructor = CredentialAttributes::class.java.getConstructor(
            String::class.java,
            String::class.java,
            Class::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE
        )
        return constructor.newInstance(serviceName, null, null, false, false)
    }

    private fun aiAttributes(configurationId: String): CredentialAttributes {
        return credentialAttributes("$SERVICE_NAME:ai:$configurationId")
    }

    fun getAiApiKey(configurationId: String): String {
        val creds = PasswordSafe.instance.get(aiAttributes(configurationId))
        return creds?.getPasswordAsString() ?: ""
    }

    fun setAiApiKey(configurationId: String, apiKey: String) {
        val attrs = aiAttributes(configurationId)
        if (apiKey.isBlank()) {
            PasswordSafe.instance.set(attrs, null)
        } else {
            PasswordSafe.instance.set(attrs, Credentials(null, apiKey))
        }
    }

    fun removeAiApiKey(configurationId: String) {
        PasswordSafe.instance.set(aiAttributes(configurationId), null)
    }

    // ── 数据库密码存储 ──────────────────────────────────────────

    private fun dbAttributes(project: Project, connectionName: String): CredentialAttributes {
        return credentialAttributes("$SERVICE_NAME:db:${project.locationHash}:$connectionName")
    }

    fun getDbPassword(project: Project, connectionName: String): String {
        val creds = PasswordSafe.instance.get(dbAttributes(project, connectionName))
        return creds?.getPasswordAsString() ?: ""
    }

    fun setDbPassword(project: Project, connectionName: String, password: String) {
        val attrs = dbAttributes(project, connectionName)
        if (password.isBlank()) {
            PasswordSafe.instance.set(attrs, null)
        } else {
            PasswordSafe.instance.set(attrs, Credentials(null, password))
        }
    }

    /** 删除指定连接的密码（连接被删除时调用） */
    fun removeDbPassword(project: Project, connectionName: String) {
        PasswordSafe.instance.set(dbAttributes(project, connectionName), null)
    }
}
