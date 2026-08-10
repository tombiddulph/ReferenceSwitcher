package io.github.tombiddulph.referenceswitcher.discovery

import io.github.tombiddulph.referenceswitcher.xml.MsBuildXml
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals

class LargeProjectFixtureTest {
    @Test
    fun `profiles metadata extraction for a large generated source tree`() {
        if (System.getenv("REFERENCE_SWITCHER_PERFORMANCE_TEST") != "true") return
        val root = createTempDirectory("large-project-fixture")

        try {
            repeat(100) { repository ->
                val repo = root.resolve("Repo-$repository").createDirectories()
                repo.resolve("Directory.Build.props").writeText(
                    "<Project><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>"
                )
                repeat(10) { project ->
                    repo.resolve("Project-$project.csproj").writeText(
                        "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>" +
                            "<PackageId>Repo.$repository.Project.$project</PackageId>" +
                            "</PropertyGroup></Project>"
                    )
                }
                repo.resolve("obj").createDirectories().resolve("Ignored.csproj")
                    .writeText("<Project Sdk=\"Microsoft.NET.Sdk\" />")
            }

            var count = 0
            val elapsed = measureTimeMillis {
                Files.walk(root).use { paths ->
                    paths.filter { it.fileName.toString().endsWith(".csproj") && !it.toString().contains("/obj/") }
                        .forEach {
                            if (MsBuildXml.discoveryMetadata(Files.readString(it)).sdkStyle) count++
                        }
                }
            }
            assertEquals(1_000, count)
            println("Parsed $count generated projects in $elapsed ms")
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
