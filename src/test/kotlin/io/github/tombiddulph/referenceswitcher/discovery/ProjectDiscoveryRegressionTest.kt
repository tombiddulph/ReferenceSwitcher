package io.github.tombiddulph.referenceswitcher.discovery

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

class ProjectDiscoveryRegressionTest {
    @Test
    fun `inspects SDK project with UTF-8 BOM`() {
        val project = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, _, _ -> null } as Project
        val service = ProjectDiscoveryService(project)

        val path = createTempFile("DatabaseClient", ".csproj")
        try {
            path.writeText(
                "\uFEFF<Project Sdk=\"Microsoft.NET.Sdk\">" +
                    "<PropertyGroup><TargetFramework>net8.0</TargetFramework>" +
                    "<PackageId>NewDay.Stratus.DatabaseClient</PackageId></PropertyGroup>" +
                    "</Project>"
            )
            assertNotNull(service.inspect(path))
        } finally {
            path.deleteIfExists()
        }
    }
}
