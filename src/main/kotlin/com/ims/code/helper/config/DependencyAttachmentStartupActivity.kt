package com.ims.code.helper.config

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProject

/**
 * 项目启动及 Maven 导入后的依赖附件关联入口
 * @author shenwl
 * @date 2026/07/31
 */
class DependencyAttachmentStartupActivity : StartupActivity.DumbAware {

    override fun runActivity(project: Project) {
        project.messageBus.connect(project).subscribe(
            MavenImportListener.TOPIC,
            object : MavenImportListener {
                override fun importFinished(importedProjects: Collection<MavenProject>, newModules: List<Module>) {
                    DependencyAttachmentService.attachConfiguredAsync(project)
                }
            }
        )
        DependencyAttachmentService.attachConfiguredAsync(project)
    }
}
