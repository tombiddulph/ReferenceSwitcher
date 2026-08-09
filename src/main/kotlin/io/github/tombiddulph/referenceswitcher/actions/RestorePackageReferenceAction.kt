package io.github.tombiddulph.referenceswitcher.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import io.github.tombiddulph.referenceswitcher.model.ActiveSwitch
import io.github.tombiddulph.referenceswitcher.model.SwitchResult
import io.github.tombiddulph.referenceswitcher.service.ReferenceSwitcherService
import io.github.tombiddulph.referenceswitcher.state.ActiveSwitchService

class RestorePackageReferenceAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && event.csprojPath() != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.csprojPath()?.toAbsolutePath()?.normalize()?.toString() ?: return
        val states = ActiveSwitchService.getInstance(project).all().filter { it.projectFile.equals(file, true) }
        if (states.isEmpty()) {
            Messages.showInfoMessage("This project has no references managed by Local References.", "Local References")
            return
        }
        val state = choose(states) ?: return
        when (val result = ReferenceSwitcherService.getInstance(project).restore(state)) {
            is SwitchResult.Failure -> Messages.showErrorDialog(result.message, "Cannot Restore Package")
            is SwitchResult.Success -> Unit
        }
    }

    private fun choose(states: List<ActiveSwitch>): ActiveSwitch? {
        if (states.size == 1) return states.single()
        val packageId = Messages.showEditableChooseDialog(
            "Select the package reference to restore:", "Restore Package Reference", null,
            states.map { it.packageId }.toTypedArray(), states.first().packageId, null,
        ) ?: return null
        return states.firstOrNull { it.packageId == packageId }
    }
}
