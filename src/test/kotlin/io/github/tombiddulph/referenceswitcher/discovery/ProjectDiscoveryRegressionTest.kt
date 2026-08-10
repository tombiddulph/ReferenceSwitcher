package io.github.tombiddulph.referenceswitcher.discovery

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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

    @Test
    fun `combines inherited properties from parent directories`() {
        val project = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, _, _ -> null } as Project
        val service = ProjectDiscoveryService(project)
        val root = createTempDirectory("discovery")

        try {
            root.resolve("Directory.Build.props").writeText(
                "<Project><PropertyGroup><TargetFramework>net8.0</TargetFramework>" +
                    "<PackageId>Root.Package</PackageId></PropertyGroup></Project>"
            )
            val child = root.resolve("src").createDirectories()
            child.resolve("Directory.Build.props").writeText(
                "<Project><PropertyGroup><PackageId>Child.Package</PackageId></PropertyGroup></Project>"
            )
            val path = child.resolve("Library.csproj")
            path.writeText("<Project Sdk=\"Microsoft.NET.Sdk\" />")

            val discovered = assertNotNull(service.inspect(path))
            assertEquals("Child.Package", discovered.packageId)
            assertEquals(listOf("net8.0"), discovered.targetFrameworks)
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `collapses duplicate and nested source roots`() {
        val project = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, _, _ -> null } as Project
        val service = ProjectDiscoveryService(project)
        val root = createTempDirectory("roots")
        val nested = root.resolve("nested").createDirectories()

        try {
            assertEquals(
                listOf(root.toAbsolutePath().normalize()),
                service.sourceRoots(listOf(nested.toString(), root.toString(), root.toString())),
            )
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
