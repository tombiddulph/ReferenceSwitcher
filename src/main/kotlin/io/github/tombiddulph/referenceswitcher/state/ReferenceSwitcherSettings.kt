package io.github.tombiddulph.referenceswitcher.state

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.RoamingType
import com.intellij.util.xmlb.XmlSerializerUtil
import io.github.tombiddulph.referenceswitcher.model.ReferenceMapping

@State(
    name = "ReferenceSwitcherSettings",
    storages = [Storage("ReferenceSwitcher.xml", roamingType = RoamingType.DISABLED)],
)
class ReferenceSwitcherSettings : PersistentStateComponent<ReferenceSwitcherSettings.SettingsState> {
    class SettingsState {
        var sourceRoots: MutableList<String> = mutableListOf()
        var mappings: MutableList<ReferenceMapping> = mutableListOf()
    }

    private var state = SettingsState()

    override fun getState(): SettingsState = state

    override fun loadState(state: SettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun mapping(packageId: String): ReferenceMapping? =
        state.mappings.firstOrNull { it.packageId.equals(packageId, true) }

    fun sourceRoots(): List<String> = state.sourceRoots.toList()

    fun replaceSourceRoots(roots: List<String>) {
        state.sourceRoots = roots.distinct().toMutableList()
    }

    fun remember(mapping: ReferenceMapping) {
        state.mappings.removeAll { it.packageId.equals(mapping.packageId, true) }
        state.mappings.add(mapping)
    }

    companion object {
        fun getInstance(): ReferenceSwitcherSettings =
            ApplicationManager.getApplication().getService(ReferenceSwitcherSettings::class.java)
    }
}
