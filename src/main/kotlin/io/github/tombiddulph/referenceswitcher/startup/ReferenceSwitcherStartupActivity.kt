package io.github.tombiddulph.referenceswitcher.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.tombiddulph.referenceswitcher.discovery.ProjectDiscoveryService
import io.github.tombiddulph.referenceswitcher.service.ReferenceSwitcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReferenceSwitcherStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.IO) {
            ProjectDiscoveryService.getInstance(project).refresh()
            ReferenceSwitcherService.getInstance(project).revalidate()
        }
    }
}
