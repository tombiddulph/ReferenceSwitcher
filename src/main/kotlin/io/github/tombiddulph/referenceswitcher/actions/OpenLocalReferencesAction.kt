package io.github.tombiddulph.referenceswitcher.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import io.github.tombiddulph.referenceswitcher.ui.LocalReferencesDialog

class OpenLocalReferencesAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { LocalReferencesDialog(it).show() }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }
}
