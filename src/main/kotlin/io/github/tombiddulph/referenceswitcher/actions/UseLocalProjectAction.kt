package io.github.tombiddulph.referenceswitcher.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import io.github.tombiddulph.referenceswitcher.discovery.ProjectDiscoveryService
import io.github.tombiddulph.referenceswitcher.model.LocalProjectInfo
import io.github.tombiddulph.referenceswitcher.model.ReferenceKind
import io.github.tombiddulph.referenceswitcher.model.SwitchResult
import io.github.tombiddulph.referenceswitcher.service.ReferenceSwitcherService
import io.github.tombiddulph.referenceswitcher.state.ReferenceSwitcherSettings
import java.nio.file.Path

class UseLocalProjectAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && event.csprojPath() != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val consumer = event.csprojPath() ?: return
        val service = ReferenceSwitcherService.getInstance(project)
        val packageReference = choosePackage(service, consumer, event.packageIdAtCaret()) ?: return
        val settings = ReferenceSwitcherSettings.getInstance()
        val discovery = ProjectDiscoveryService.getInstance(project)
        val mapped = settings.mapping(packageReference.packageId)?.projectFile?.let(Path::of)
        val candidates = discovery.find(packageReference.packageId)
        val selectedPath = when {
            mapped != null && mapped.toFile().isFile -> mapped
            candidates.size == 1 -> Path.of(candidates.single().projectFile)
            candidates.size > 1 -> chooseCandidate(candidates) ?: return
            else -> chooseCsproj(event) ?: return
        }
        val local = service.inspectLocalProject(selectedPath) ?: run {
            Messages.showErrorDialog("The selected file is not a supported SDK-style project.", "Local References")
            return
        }
        val report = service.check(consumer, local)
        if (!report.canSwitch) {
            Messages.showErrorDialog(report.errors.joinToString("\n") { it.message }, "Cannot Use Local Project")
            return
        }
        if (report.warnings.isNotEmpty() && Messages.showYesNoDialog(
                report.warnings.joinToString("\n") { it.message } + "\n\nSwitch anyway?",
                "Local Reference Warning", null,
            ) != Messages.YES
        ) return

        val kind = if (local.suggestedKind == ReferenceKind.ANALYZER) {
            if (Messages.showYesNoDialog(
                    "${local.packageId} looks like an analyzer or source generator. Reference it as an analyzer?",
                    "Reference Kind", "Analyzer", "Normal", null,
                ) == Messages.YES
            ) ReferenceKind.ANALYZER else ReferenceKind.NORMAL
        } else ReferenceKind.NORMAL

        when (val result = service.switch(packageReference, local, kind)) {
            is SwitchResult.Failure -> Messages.showErrorDialog(result.message, "Cannot Use Local Project")
            is SwitchResult.Success -> Unit
        }
    }

    private fun chooseCandidate(candidates: List<LocalProjectInfo>): Path? {
        val values = candidates.map { it.projectFile }.toTypedArray()
        val selected = Messages.showEditableChooseDialog(
            "More than one local project publishes this package:", "Select Local Project", null,
            values, values.first(), null,
        ) ?: return null
        return Path.of(selected)
    }
}
