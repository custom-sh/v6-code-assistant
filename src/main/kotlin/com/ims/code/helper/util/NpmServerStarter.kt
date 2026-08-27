package com.ims.code.helper.util

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File

/**
 * 前端开发服务器启动工具
 * @author shenwl
 * @date 2026/07/12
 */
object NpmServerStarter {

    private val log = Logger.getInstance(NpmServerStarter::class.java)

    private fun npmExe(): String =
        if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"

    /**
     * 探测 npm 是否可用（PATH 中能找到且能执行 `npm -v`）。
     * 用于升级前的前置校验，避免无 node 环境时静默跳过 npm install 却报成功。
     */
    fun isNpmAvailable(): Boolean {
        return try {
            val cmdLine = GeneralCommandLine()
                .withExePath(npmExe())
                .withParameters("-v")
                .withCharset(Charsets.UTF_8)
            val handler = OSProcessHandler(cmdLine)
            handler.startNotify()
            val terminated = handler.waitFor(10_000)
            if (!terminated) {
                // 挂住则强制销毁，避免僵尸进程遗留
                handler.destroyProcess()
                return false
            }
            handler.exitCode == 0
        } catch (ex: Exception) {
            log.warn("npm availability check failed: ${ex.message}")
            false
        }
    }

    /**
     * 运行 npm 命令并等待其结束（适用于有限进程如 npm install）。
     * @return 进程退出码，异常时返回 null
     */
    fun runNpmCommand(
        workingDir: File,
        args: List<String>,
        indicator: ProgressIndicator? = null,
        onText: ((text: String, isStderr: Boolean) -> Unit)? = null
    ): Int? {
        return try {
            val cmdLine = GeneralCommandLine()
                .withExePath(npmExe())
                .withParameters(args)
                .withWorkDirectory(workingDir)
                .withCharset(Charsets.UTF_8)

            val handler = OSProcessHandler(cmdLine)
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    onText?.invoke(event.text, outputType == ProcessOutputTypes.STDERR)
                    if (indicator?.isCanceled == true) handler.destroyProcess()
                }
            })

            handler.startNotify()
            // 取消或异常时 destroyProcess 已发起，但个别 npm 子进程不响应 destroy → 加 30 分钟
            // 硬超时兜底：超时后再次 destroyProcess，避免调用线程永久阻塞在 waitFor()
            if (!handler.waitFor(30 * 60_000L)) {
                handler.destroyProcess()
            }
            handler.exitCode
        } catch (ex: Exception) {
            log.error("npm ${args.joinToString(" ")} failed in ${workingDir.absolutePath}", ex)
            null
        }
    }

    /**
     * 启动前端开发服务器：VS Code 可用则打开目录 + folderOpen 任务自动跑；不可用则弹独立 cmd 兜底。
     *
     * VS Code 分支：只走 [VSCodeHelper.writeTasksJson] + [VSCodeHelper.openInVSCode]。tasks.json 里的
     * runOn: folderOpen 会在 VS Code 首次打开该目录时（且用户允许自动任务后）自动执行 npm run serve。
     * 已经打开过的窗口只会被聚焦、不会重跑任务——所以额外弹通知提示用户可 Ctrl+Shift+P 手动触发。
     *
     * cmd 分支（VS Code 不可用）：`ProcessBuilder(...).directory(moduleDir)` 是关键——不设的话
     * `cmd /c start` 会继承 IDEA 沙箱进程的工作目录（形如 `...\ideaIC-2023.1\bin`），Windows 的
     * `start` 在这种目录下解析参数会报"系统找不到指定的路径"，cmd 窗口能开但内部 `cd/npm` 根本没执行。
     */
    fun startDevServer(project: Project, moduleDir: File, moduleName: String) {
        if (VSCodeHelper.isAvailable()) {
            VSCodeHelper.writeTasksJson(moduleDir, moduleName)
            VSCodeHelper.openInVSCode(moduleDir)
            NotificationHelper.info(
                project,
                ImsBundle.message("action.server.vscode.hint.title"),
                ImsBundle.message("action.server.vscode.hint", moduleName)
            )
            return
        }
        openCmdWindow(project, moduleDir, moduleName)
    }

    private fun openCmdWindow(project: Project, moduleDir: File, title: String) {
        try {
            val hasNodeModules = File(moduleDir, "node_modules").isDirectory
            // ^& 转义 & 防止外层 cmd /c 把 && 当作命令分隔符提前切割，
            // 让 npm 命令留在内层 cmd /k 里、在 start 已定位的工作目录中执行。
            val command = if (hasNodeModules) {
                "npm run serve"
            } else {
                "npm install ^&^& npm run serve"
            }
            // .directory(moduleDir)：把外层 cmd 的 CWD 钉在目标模块，否则 start 会以 IDEA 沙箱 CWD
            // 解析参数并抛"系统找不到指定的路径"。
            ProcessBuilder("cmd", "/c", "start", "\"$title\"", "cmd", "/k", command)
                .directory(moduleDir)
                .start()
            log.info("Cmd window '$title' opened at ${moduleDir.absolutePath}")
        } catch (e: Exception) {
            log.warn("Failed to open cmd window: ${e.message}", e)
            NotificationHelper.error(
                project,
                ImsBundle.message("validate.error.title"),
                ImsBundle.message("action.server.start.error", title, e.message ?: "")
            )
        }
    }

}
