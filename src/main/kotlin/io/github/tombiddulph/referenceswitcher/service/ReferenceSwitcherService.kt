package io.github.tombiddulph.referenceswitcher.service

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.tombiddulph.referenceswitcher.compat.CompatibilityChecker
import io.github.tombiddulph.referenceswitcher.discovery.ProjectDiscoveryService
import io.github.tombiddulph.referenceswitcher.model.ActiveSwitch
import io.github.tombiddulph.referenceswitcher.model.CompatibilityReport
import io.github.tombiddulph.referenceswitcher.model.LocalProjectInfo
import io.github.tombiddulph.referenceswitcher.model.PackageReferenceInfo
import io.github.tombiddulph.referenceswitcher.model.ReferenceKind
import io.github.tombiddulph.referenceswitcher.model.ReferenceMapping
import io.github.tombiddulph.referenceswitcher.model.SwitchResult
import io.github.tombiddulph.referenceswitcher.state.ActiveSwitchService
import io.github.tombiddulph.referenceswitcher.state.ReferenceSwitcherSettings
import io.github.tombiddulph.referenceswitcher.xml.MsBuildXml
import java.nio.file.Files
import java.nio.file.Path

class ReferenceSwitcherService(private val project: Project) {
    private val active get() = ActiveSwitchService.getInstance(project)

    fun packageReferences(projectFile: Path): List<PackageReferenceInfo> =
        MsBuildXml.packageReferences(read(projectFile), projectFile.toString())

    fun inspectLocalProject(path: Path): LocalProjectInfo? =
        ProjectDiscoveryService.getInstance(project).inspect(path)

    fun check(consumerFile: Path, local: LocalProjectInfo): CompatibilityReport =
        CompatibilityChecker.check(read(consumerFile), local)

    fun switch(
        packageReference: PackageReferenceInfo,
        local: LocalProjectInfo,
        kind: ReferenceKind,
    ): SwitchResult {
        val consumer = Path.of(packageReference.projectFile).toAbsolutePath().normalize()
        val target = Path.of(local.projectFile).toAbsolutePath().normalize()
        if (!Files.isRegularFile(consumer)) return SwitchResult.Failure("The consuming project no longer exists: $consumer")
        if (!Files.isRegularFile(target)) return SwitchResult.Failure("The selected project no longer exists: $target")
        if (consumer == target) return SwitchResult.Failure("A project cannot reference itself.")

        val xml = read(consumer)
        if (!MsBuildXml.isSdkStyle(xml)) return SwitchResult.Failure("Only SDK-style projects are supported.")
        if (MsBuildXml.projectReferenceExists(xml, consumer, target)) {
            return SwitchResult.Failure("An equivalent ProjectReference already exists. No changes were made.")
        }
        val report = CompatibilityChecker.check(xml, local)
        if (!report.canSwitch) return SwitchResult.Failure(report.errors.joinToString("\n") { it.message })
        val result = MsBuildXml.switchToProject(xml, packageReference.packageId, consumer, target, kind)
        if (result is SwitchResult.Success && commit(consumer, result.xml, "Use Local Project")) {
            active.put(
                ActiveSwitch(
                    consumer.toString(), packageReference.packageId, target.toString(), kind,
                    packageReference.anchor.index, packageReference.anchor.condition,
                    packageReference.originalXml,
                )
            )
            ReferenceSwitcherSettings.getInstance().remember(
                ReferenceMapping(packageReference.packageId, target.toString(), kind)
            )
            return result
        }
        return if (result is SwitchResult.Failure) result else
            SwitchResult.Failure("Rider could not write the project file. See the IDE log for details.")
    }

    fun restore(state: ActiveSwitch): SwitchResult {
        val projectFile = Path.of(state.projectFile)
        if (!Files.isRegularFile(projectFile)) return SwitchResult.Failure("The consuming project no longer exists: $projectFile")
        val result = MsBuildXml.restorePackage(read(projectFile), state)
        if (result is SwitchResult.Success && commit(projectFile, result.xml, "Restore Package Reference")) {
            active.remove(state.projectFile, state.packageId)
            return result
        }
        return if (result is SwitchResult.Failure) result else
            SwitchResult.Failure("Rider could not write the project file. See the IDE log for details.")
    }

    fun revalidate() {
        active.all().forEach { state ->
            val consumer = Path.of(state.projectFile)
            val target = Path.of(state.localProject)
            state.stale = !Files.isRegularFile(consumer) || !Files.isRegularFile(target) ||
                !MsBuildXml.projectReferenceExists(runCatching { read(consumer) }.getOrDefault(""), consumer, target)
        }
    }

    private fun read(path: Path): String {
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
        val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
        return document?.text ?: Files.readString(path)
    }

    private fun commit(path: Path, text: String, commandName: String): Boolean {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return false
        if (!file.isWritable) return false
        val manager = FileDocumentManager.getInstance()
        val document = manager.getDocument(file) ?: return false
        return runCatching {
            WriteCommandAction.runWriteCommandAction(project, commandName, null, {
                document.setText(text)
                manager.saveDocument(document)
            })
            true
        }.onFailure {
            logger.warn("Could not write project file: $path", it)
        }.getOrDefault(false)
    }

    companion object {
        private val logger = Logger.getInstance(ReferenceSwitcherService::class.java)

        fun getInstance(project: Project): ReferenceSwitcherService = project.getService(ReferenceSwitcherService::class.java)
    }
}
