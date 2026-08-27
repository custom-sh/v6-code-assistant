package com.ims.code.helper.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.JavadocOrderRootType
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Server 与 CS 依赖附件自动关联服务
 * @author shenwl
 * @date 2026/07/31
 */
object DependencyAttachmentService {

    enum class AttachmentKind {
        SOURCES,
        JAVADOC
    }

    data class AttachmentCandidate(
        val mainJar: File,
        val attachmentJar: File,
        val kind: AttachmentKind
    )

    data class AttachmentResult(
        val discovered: Int,
        val attachedSources: Int,
        val attachedJavadocs: Int,
        val alreadyAttached: Int,
        val dependencyNotFound: Int,
        val failed: Int,
        val serverFullyAttached: Boolean,
        val clientFullyAttached: Boolean
    )

    private data class ModuleAttachmentResult(
        val discovered: Int,
        val attachedSources: Int,
        val attachedJavadocs: Int,
        val alreadyAttached: Int,
        val dependencyNotFound: Int,
        val failed: Int
    ) {
        val fullyAttached: Boolean
            get() = discovered > 0 && dependencyNotFound == 0 && failed == 0 &&
                attachedSources + attachedJavadocs + alreadyAttached == discovered
    }

    private data class PendingAttachment(
        val library: Library,
        val url: String,
        val rootType: OrderRootType,
        val kind: AttachmentKind
    )

    private data class AttachmentPlan(
        val pending: List<PendingAttachment>,
        val alreadyAttached: Int,
        val dependencyNotFound: Int
    )

    fun attachConfiguredAsync(project: Project, onComplete: ((AttachmentResult) -> Unit)? = null) {
        val settings = ImsProjectSettings.getInstance(project)
        attachAsync(
            project = project,
            serverPath = settings.serverPath,
            clientPath = settings.clientPath,
            onComplete = onComplete
        )
    }

    fun attachAsync(
        project: Project,
        serverPath: String,
        clientPath: String,
        onComplete: ((AttachmentResult) -> Unit)? = null
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                synchronized(ATTACH_LOCK) {
                    attach(project, serverPath, clientPath)
                }
            } catch (e: Throwable) {
                LOG.warn("Failed to attach dependency sources and javadocs", e)
                AttachmentResult(0, 0, 0, 0, 0, 1, false, false)
            }
            if (onComplete != null) {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) onComplete(result)
                }
            }
        }
    }

    internal fun discoverAttachments(modulePath: String): List<AttachmentCandidate> {
        if (modulePath.isBlank()) return emptyList()
        val libDir = File(modulePath, "lib")
        val jars = libDir.listFiles { file -> file.isFile && file.extension.equals("jar", true) }
            ?.toList()
            .orEmpty()
        if (jars.isEmpty()) return emptyList()

        val jarsByName = jars.associateBy { it.name.lowercase() }
        return jars.mapNotNull { attachment ->
            val match = ATTACHMENT_PATTERN.matchEntire(attachment.name) ?: return@mapNotNull null
            val mainJar = jarsByName["${match.groupValues[1]}.jar".lowercase()] ?: return@mapNotNull null
            val kind = when (match.groupValues[2].lowercase()) {
                "source", "sources" -> AttachmentKind.SOURCES
                else -> AttachmentKind.JAVADOC
            }
            AttachmentCandidate(mainJar, attachment, kind)
        }.sortedWith(compareBy({ it.mainJar.name.lowercase() }, { it.kind.name }))
    }

    private fun attach(
        project: Project,
        serverPath: String,
        clientPath: String
    ): AttachmentResult {
        val server = attachModule(project, serverPath)
        val client = attachModule(project, clientPath)
        return AttachmentResult(
            discovered = server.discovered + client.discovered,
            attachedSources = server.attachedSources + client.attachedSources,
            attachedJavadocs = server.attachedJavadocs + client.attachedJavadocs,
            alreadyAttached = server.alreadyAttached + client.alreadyAttached,
            dependencyNotFound = server.dependencyNotFound + client.dependencyNotFound,
            failed = server.failed + client.failed,
            serverFullyAttached = server.fullyAttached,
            clientFullyAttached = client.fullyAttached
        )
    }

    private fun attachModule(project: Project, modulePath: String): ModuleAttachmentResult {
        if (project.isDisposed) return ModuleAttachmentResult(0, 0, 0, 0, 0, 0)
        val candidates = discoverAttachments(modulePath).distinctBy { normalizedPath(it.attachmentJar) }
        if (candidates.isEmpty()) return ModuleAttachmentResult(0, 0, 0, 0, 0, 0)

        val plan = ReadAction.compute<AttachmentPlan, RuntimeException> {
            buildAttachmentPlan(project, candidates)
        }
        var attachedSources = 0
        var attachedJavadocs = 0
        var failed = 0

        if (plan.pending.isNotEmpty() && !project.isDisposed) {
            WriteAction.runAndWait<RuntimeException> {
                plan.pending.groupBy { it.library }.forEach { (library, attachments) ->
                    val model = try {
                        library.modifiableModel
                    } catch (e: Throwable) {
                        LOG.warn("Unable to modify IDEA library ${library.name}", e)
                        failed += attachments.size
                        return@forEach
                    }
                    try {
                        attachments.forEach { model.addRoot(it.url, it.rootType) }
                        model.commit()
                        attachedSources += attachments.count { it.kind == AttachmentKind.SOURCES }
                        attachedJavadocs += attachments.count { it.kind == AttachmentKind.JAVADOC }
                    } catch (e: Throwable) {
                        try {
                            model.dispose()
                        } catch (_: Throwable) {
                            // commit 失败后模型可能已经由平台释放
                        }
                        failed += attachments.size
                        LOG.warn("Unable to attach sources or javadocs to IDEA library ${library.name}", e)
                    }
                }
            }
        }

        return ModuleAttachmentResult(
            discovered = candidates.size,
            attachedSources = attachedSources,
            attachedJavadocs = attachedJavadocs,
            alreadyAttached = plan.alreadyAttached,
            dependencyNotFound = plan.dependencyNotFound,
            failed = failed
        )
    }

    private fun buildAttachmentPlan(
        project: Project,
        candidates: List<AttachmentCandidate>
    ): AttachmentPlan {
        val byMainJar = candidates.groupBy { normalizedPath(it.mainJar) }
        val matched = linkedSetOf<AttachmentCandidate>()
        val pending = linkedSetOf<PendingAttachment>()
        var alreadyAttached = 0

        for (module in ModuleManager.getInstance(project).modules) {
            for (entry in ModuleRootManager.getInstance(module).orderEntries.filterIsInstance<LibraryOrderEntry>()) {
                val library = entry.library ?: continue
                val matchingCandidates = library.getFiles(OrderRootType.CLASSES)
                    .flatMap { root -> byMainJar[normalizedPath(root)].orEmpty() }
                for (candidate in matchingCandidates) {
                    if (!matched.add(candidate)) continue
                    val rootType = rootType(candidate.kind)
                    val attachmentUrl = VfsUtil.getUrlForLibraryRoot(candidate.attachmentJar)
                    val attachmentPath = normalizedPath(candidate.attachmentJar)
                    if (library.getFiles(rootType).any { normalizedPath(it) == attachmentPath }) {
                        alreadyAttached++
                    } else {
                        pending += PendingAttachment(library, attachmentUrl, rootType, candidate.kind)
                    }
                }
            }
        }

        return AttachmentPlan(
            pending = pending.toList(),
            alreadyAttached = alreadyAttached,
            dependencyNotFound = candidates.size - matched.size
        )
    }

    private fun rootType(kind: AttachmentKind): OrderRootType = when (kind) {
        AttachmentKind.SOURCES -> OrderRootType.SOURCES
        AttachmentKind.JAVADOC -> JavadocOrderRootType.getInstance()
    }

    private fun normalizedPath(file: File): String = normalizeCase(
        FileUtil.toCanonicalPath(file.absolutePath).replace('\\', '/')
    )

    private fun normalizedPath(root: VirtualFile): String {
        val localFile = JarFileSystem.getInstance().getVirtualFileForJar(root) ?: root
        return normalizeCase(FileUtil.toCanonicalPath(localFile.path).replace('\\', '/'))
    }

    private fun normalizeCase(path: String): String =
        if (SystemInfo.isFileSystemCaseSensitive) path else path.lowercase()

    private val ATTACHMENT_PATTERN = Regex(
        pattern = "^(.+)-(source|sources|doc|docs|javadoc|javadocs)\\.jar$",
        option = RegexOption.IGNORE_CASE
    )

    private val ATTACH_LOCK = Any()
    private val LOG = Logger.getInstance(DependencyAttachmentService::class.java)
}
