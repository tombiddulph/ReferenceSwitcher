package io.github.tombiddulph.referenceswitcher.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import io.github.tombiddulph.referenceswitcher.discovery.ProjectDiscoveryService
import io.github.tombiddulph.referenceswitcher.model.ActiveSwitch
import io.github.tombiddulph.referenceswitcher.model.SwitchResult
import io.github.tombiddulph.referenceswitcher.service.ReferenceSwitcherService
import io.github.tombiddulph.referenceswitcher.state.ActiveSwitchService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class LocalReferencesDialog(private val project: Project) : DialogWrapper(project) {
    private val model = DefaultListModel<ActiveSwitch>()
    private val list = JBList(model)
    private val discovered = JLabel()

    init {
        title = "Local References"
        init()
        refreshState()
    }

    override fun createCenterPanel(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = SimpleListCellRenderer.create { label, value, _ ->
            label.text = buildString {
                if (value.stale) append("[stale] ")
                append(value.packageId)
                append("  |  ")
                append(java.nio.file.Path.of(value.projectFile).fileName)
                append(" -> ")
                append(value.localProject)
            }
        }
        val restore = JButton("Restore Package").apply { addActionListener { restoreSelected() } }
        val forget = JButton("Forget").apply { addActionListener { forgetSelected() } }
        val refresh = JButton("Refresh Local Projects").apply { addActionListener { refreshDiscovery() } }
        val configure = JButton("Configure Source Roots...").apply {
            addActionListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ReferenceSwitcherConfigurable::class.java)
                refreshDiscovery()
            }
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(restore)
            add(forget)
            add(refresh)
            add(configure)
            add(discovered)
        }
        return JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(760, 320)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    private fun restoreSelected() {
        val selected = list.selectedValue ?: return
        when (val result = ReferenceSwitcherService.getInstance(project).restore(selected)) {
            is SwitchResult.Failure -> Messages.showErrorDialog(project, result.message, "Cannot Restore Package")
            is SwitchResult.Success -> refreshState()
        }
    }

    private fun forgetSelected() {
        list.selectedValue?.let { ActiveSwitchService.getInstance(project).forget(it) }
        refreshState()
    }

    private fun refreshState() {
        ReferenceSwitcherService.getInstance(project).revalidate()
        model.removeAllElements()
        ActiveSwitchService.getInstance(project).all().forEach(model::addElement)
    }

    private fun refreshDiscovery() {
        val count = ProjectDiscoveryService.getInstance(project).refresh()
        discovered.text = "$count projects discovered"
    }
}
