package com.ims.code.helper.util

import com.ims.code.helper.config.ImsProjectSettings
import com.ims.code.helper.generator.ModuleType
import java.io.File

/**
 * 路径解析工具
 * 根据项目配置解析代码生成的目标路径
 */
object PathResolver {

    /**
     * 获取模块的根路径
     */
    fun getModuleRootPath(settings: ImsProjectSettings, moduleType: ModuleType): String? {
        return when (moduleType) {
            ModuleType.SERVER -> settings.serverPath.ifBlank { null }
            ModuleType.WEB -> settings.webPath.ifBlank { null }
            ModuleType.PDA -> settings.pdaPath.ifBlank { null }
        }
    }

    /**
     * 解析 Java 文件的目标路径
     * @param moduleRoot 模块根路径
     * @param packageName 包名
     * @param className 类名
     * @param suffix 文件后缀（如 .java, .kt）
     */
    fun resolveJavaFilePath(moduleRoot: String, packageName: String, className: String, suffix: String = ".java"): String {
        val packagePath = packageName.replace('.', File.separatorChar)
        return "${moduleRoot}${File.separatorChar}src${File.separatorChar}main${File.separatorChar}java${File.separatorChar}${packagePath}${File.separatorChar}${className}${suffix}"
    }

    /**
     * 验证路径是否为有效的项目目录
     */
    fun isValidProjectPath(path: String): Boolean {
        if (path.isBlank()) return false
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return false
        // 检查是否包含常见项目标识
        return dir.listFiles()?.any {
            it.name in listOf("pom.xml", "build.gradle", "build.gradle.kts", "src", ".git")
        } ?: false
    }
}
