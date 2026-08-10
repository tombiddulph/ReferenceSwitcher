package io.github.tombiddulph.referenceswitcher.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import io.github.tombiddulph.referenceswitcher.discovery.ProjectDiscoveryService
import io.github.tombiddulph.referenceswitcher.state.ReferenceSwitcherSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JPanel

class ReferenceSwitcherConfigurable : Configurable {
    private val model = DefaultListModel<String>()
    private val roots = mutableListOf<String>()
    private val list = JBList(model)
    private val countLabel = JLabel()
    private var refreshButton: JButton? = null
    private var panel: JPanel? = null

    override fun getDisplayName() = "Local References"

    override fun createComponent(): JComponent {
        reset()
        val add = JButton("Add...").apply { addActionListener { addRoot() } }
        val remove = JButton("Remove").apply {
            addActionListener {
                roots.removeAll(list.selectedValuesList.toSet())
                rebuildModel()
            }
        }
        val refresh = JButton("Refresh Projects").apply { addActionListener { refreshProjects() } }
        refreshButton = refresh
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            add(add)
            add(remove)
            add(refresh)
            add(countLabel)
        }
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponentFillVertically("Local source roots:", JBScrollPane(list))
            .addComponent(buttons)
            .addComponentFillVertically(JPanel(BorderLayout()), 0)
            .panel
        return panel!!
    }

    override fun isModified(): Boolean =
        roots != ReferenceSwitcherSettings.getInstance().sourceRoots()

    override fun apply() {
        ReferenceSwitcherSettings.getInstance().replaceSourceRoots(roots)
        refreshProjects()
    }

    override fun reset() {
        roots.clear()
        roots.addAll(ReferenceSwitcherSettings.getInstance().sourceRoots())
        rebuildModel()
    }

    override fun disposeUIResources() {
        panel = null
        refreshButton = null
    }

    private fun addRoot() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = "Select Local Source Root"
        }
        FileChooser.chooseFile(descriptor, null, null)?.path?.let {
            if (!roots.contains(it)) {
                roots.add(it)
                model.addElement(it)
            }
        }
    }

    private fun refreshProjects() {
        if (isModified) ReferenceSwitcherSettings.getInstance().replaceSourceRoots(roots)
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val discovery = ProjectDiscoveryService.getInstance(project)
        if (discovery.isRefreshing()) {
            countLabel.text = "Project discovery already running"
            return
        }
        refreshButton?.isEnabled = false
        countLabel.text = "Discovering projects..."
        object : Task.Backgroundable(project, "Discovering Local Projects", true) {
            override fun run(indicator: ProgressIndicator) {
                val count = discovery.refresh()
                indicator.text = "$count projects discovered"
            }

            override fun onSuccess() = finishRefresh(
                project,
                "${discovery.all().size} projects discovered",
            )
            override fun onCancel() = finishRefresh(project, "Project discovery cancelled")
            override fun onThrowable(error: Throwable) = finishRefresh(project, "Project discovery failed")
        }.queue()
    }

    private fun finishRefresh(project: Project, message: String) {
        if (panel != null && !project.isDisposed) {
            countLabel.text = message
            refreshButton?.isEnabled = true
        }
    }

    private fun rebuildModel() {
        model.removeAllElements()
        roots.forEach(model::addElement)
    }
}
