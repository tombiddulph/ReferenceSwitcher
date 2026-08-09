package io.github.tombiddulph.referenceswitcher.state

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import io.github.tombiddulph.referenceswitcher.model.ActiveSwitch

@State(name = "ReferenceSwitcherActiveSwitches", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ActiveSwitchService(private val project: Project) : PersistentStateComponent<ActiveSwitchService.SwitchState> {
    class SwitchState {
        var activeSwitches: MutableList<ActiveSwitch> = mutableListOf()
    }

    private var state = SwitchState()

    override fun getState(): SwitchState = state

    override fun loadState(state: SwitchState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun all(): List<ActiveSwitch> = state.activeSwitches.toList()

    fun find(projectFile: String, packageId: String): ActiveSwitch? = state.activeSwitches.firstOrNull {
        it.projectFile.equals(projectFile, true) && it.packageId.equals(packageId, true)
    }

    fun put(value: ActiveSwitch) {
        remove(value.projectFile, value.packageId)
        state.activeSwitches.add(value)
    }

    fun remove(projectFile: String, packageId: String) {
        state.activeSwitches.removeAll {
            it.projectFile.equals(projectFile, true) && it.packageId.equals(packageId, true)
        }
    }

    fun forget(value: ActiveSwitch) = remove(value.projectFile, value.packageId)

    companion object {
        fun getInstance(project: Project): ActiveSwitchService = project.getService(ActiveSwitchService::class.java)
    }
}
