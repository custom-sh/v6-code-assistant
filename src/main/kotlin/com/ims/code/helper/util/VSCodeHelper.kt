package com.ims.code.helper.util

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * VS Code 集成工具
 * @author shenwl
 * @date 2026/07/12
 */
object VSCodeHelper {

    private val log = Logger.getInstance(VSCodeHelper::class.java)

    // ── 检测 ──────────────────────────────────────────────────────

    // 缓存已找到的 code 路径，避免重复探测
    @Volatile private var cachedCodePath: String? = null
    @Volatile private var cacheChecked = false

    /** 探测 code 命令路径，找不到返回 null */
    fun findCodeCommand(): String? {
        if (cacheChecked) return cachedCodePath
        return synchronized(this) {
            // 双重检查：避免多线程重复走完整个检测链
            if (cacheChecked) return@synchronized cachedCodePath
            cacheChecked = true

            // 1. PATH 中查找
            val fromPath = findInPath()
            if (fromPath != null) {
                cachedCodePath = fromPath
                log.info("VS Code found in PATH: $fromPath")
                return@synchronized fromPath
            }

            // 2. Windows：注册表查询
            if (isWin) {
                val fromReg = findInRegistry()
                if (fromReg != null) {
                    cachedCodePath = fromReg
                    log.info("VS Code found via registry: $fromReg")
                    return@synchronized fromReg
                }
            }

            // 3. Windows：PowerShell Get-Command
            if (isWin) {
                val fromPs = findViaPowerShell()
                if (fromPs != null) {
                    cachedCodePath = fromPs
                    log.info("VS Code found via PowerShell: $fromPs")
                    return@synchronized fromPs
                }
            }

            // 4. 已知安装路径
            for (candidate in knownPaths()) {
                val f = File(candidate)
                if (f.isFile && (isWin || f.canExecute())) {
                    // 验证可执行
                    if (verifyCodeCommand(candidate)) {
                        cachedCodePath = candidate
                        log.info("VS Code found at: $candidate")
                        return@synchronized candidate
                    }
                }
            }

            log.info("VS Code not found")
            return@synchronized null
        }
    }

    /** 刷新缓存（用户安装了 VS Code 后重新检测） */
    fun refreshCache() {
        cacheChecked = false
        cachedCodePath = null
    }

    /** VS Code 是否可用 */
    fun isAvailable(): Boolean = findCodeCommand() != null

    private val isWin: Boolean get() = System.getProperty("os.name").lowercase().contains("win")

    /**
     * 启动子进程并等待，超时 destroyForcibly。
     * 返回 Pair(exitCode, output)；超时/异常/失败返回 null。
     * 各个 finder 全走这个 helper，避免 where/reg query/PowerShell 挂住冻结调用线程。
     */
    private fun runWithTimeout(cmd: List<String>, timeoutMs: Long = 10_000): Pair<Int, String>? {
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                return null
            }
            proc.exitValue() to output.trim()
        } catch (_: Exception) { null }
    }

    private fun findInPath(): String? {
        val (code, output) = runWithTimeout(if (isWin) listOf("where", "code") else listOf("which", "code"))
            ?: return null
        if (code != 0 || output.isBlank()) return null
        val found = output.lines().firstOrNull { it.contains("code") } ?: output
        val trimmed = found.trim()
        return if (verifyCodeCommand(trimmed)) trimmed else null
    }

    /** 通过 Windows 注册表查找 VS Code 安装路径 */
    private fun findInRegistry(): String? {
        val (code, output) = runWithTimeout(listOf(
            "reg", "query",
            "HKEY_CLASSES_ROOT\\*\\shell\\VSCode\\command",
            "/ve"
        )) ?: return null
        if (code != 0 || output.isBlank()) return null
        // 输出格式：...\code.cmd" "%1"
        val match = Regex("""([A-Za-z]:\\[^"]+code\.cmd)""").find(output) ?: return null
        val path = match.groupValues[1].trim()
        return if (File(path).isFile) path else null
    }

    /** 通过 PowerShell Get-Command 查找 code */
    private fun findViaPowerShell(): String? {
        val (code, output) = runWithTimeout(listOf(
            "powershell", "-NoProfile", "-Command",
            "Get-Command code -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source"
        )) ?: return null
        if (code != 0 || output.isBlank()) return null
        val path = output.lines().firstOrNull { it.contains("code") }?.trim() ?: output.trim()
        return if (File(path).isFile && verifyCodeCommand(path)) path else null
    }

    /** 验证 code 命令可正常执行 */
    private fun verifyCodeCommand(codePath: String): Boolean {
        val (code, output) = runWithTimeout(listOf(codePath, "--version")) ?: return false
        return code == 0 && output.isNotBlank()
    }

    private fun knownPaths(): List<String> {
        val home = System.getProperty("user.home")
        val localAppData = System.getenv("LOCALAPPDATA") ?: ""
        return if (isWin) listOf(
            "$localAppData\\Programs\\Microsoft VS Code\\bin\\code.cmd",
            "C:\\Program Files\\Microsoft VS Code\\bin\\code.cmd",
            "$home\\AppData\\Local\\Programs\\Microsoft VS Code\\bin\\code.cmd",
            "C:\\Program Files (x86)\\Microsoft VS Code\\bin\\code.cmd"
        ) else listOf(
            "/usr/local/bin/code",
            "/usr/bin/code",
            "/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code",
            "$home/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code"
        )
    }

    // ── tasks.json ────────────────────────────────────────────────

    /**
     * 在模块目录下写入 .vscode/tasks.json。
     * 配置了一个 folderOpen 自动执行的任务：npm run serve。
     * 如果 node_modules 不存在，先执行 npm install 再 serve。
     */
    fun writeTasksJson(moduleDir: File, moduleName: String) {
        val vscodeDir = File(moduleDir, ".vscode")
        vscodeDir.mkdirs()

        // ── settings.json：允许自动任务（不覆盖已有配置） ──
        val settingsFile = File(vscodeDir, "settings.json")
        if (!settingsFile.isFile) {
            settingsFile.writeText("{\n    \"task.allowAutomaticTasks\": \"on\"\n}\n")
        }

        // ── tasks.json：folderOpen 自动运行（type: npm 利用 VS Code 内置 npm 脚本检测）──
        val hasNodeModules = File(moduleDir, "node_modules").isDirectory
        val tasksJson = if (hasNodeModules) {
            """
{
    "version": "2.0.0",
    "tasks": [
        {
            "type": "npm",
            "script": "serve",
            "isBackground": true,
            "problemMatcher": [],
            "presentation": {
                "reveal": "always",
                "panel": "new"
            },
            "runOptions": {
                "runOn": "folderOpen"
            }
        }
    ]
}
            """.trimIndent()
        } else {
            """
{
    "version": "2.0.0",
    "tasks": [
        {
            "label": "npm install & serve",
            "type": "shell",
            "command": "npm install && npm run serve",
            "isBackground": true,
            "problemMatcher": [],
            "presentation": {
                "reveal": "always",
                "panel": "new"
            },
            "runOptions": {
                "runOn": "folderOpen"
            },
            "dependsOn": []
        }
    ]
}
            """.trimIndent()
        }

        File(vscodeDir, "tasks.json").writeText(tasksJson)
        log.info(".vscode/ written for $moduleName in ${vscodeDir.absolutePath}")
    }

    // ── 启动 ──────────────────────────────────────────────────────

    /**
     * 使用 VS Code 打开模块目录。
     * @return true 启动成功，false 失败
     */
    fun openInVSCode(moduleDir: File): Boolean {
        val codeExe = findCodeCommand() ?: return false

        return try {
            ProcessBuilder(codeExe, moduleDir.absolutePath)
                .inheritIO()
                .directory(moduleDir)
                .start()
            log.info("VS Code launched for ${moduleDir.absolutePath}")
            true
        } catch (e: Exception) {
            log.warn("Failed to launch VS Code: ${e.message}")
            false
        }
    }
}
