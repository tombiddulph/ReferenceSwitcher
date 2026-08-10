package io.github.tombiddulph.referenceswitcher.discovery

import io.github.tombiddulph.referenceswitcher.model.LocalProjectInfo
import io.github.tombiddulph.referenceswitcher.model.ReferenceKind
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectDiscoveryCacheTest {
    @Test
    fun `round trips cached projects`() {
        val root = createTempDirectory("discovery-cache")
        val path = root.resolve("projects.dat")
        val cached = CachedProject(
            LocalProjectInfo(
                "/source/Example.csproj",
                "Example.Package",
                listOf("net8.0", "netstandard2.0"),
                ReferenceKind.ANALYZER,
                true,
            ),
            123L,
            mapOf("/source/Directory.Build.props" to 456L),
        )

        try {
            ProjectDiscoveryCache.save(path, listOf(cached))
            assertEquals(cached, ProjectDiscoveryCache.load(path).values.single())
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `ignores invalid cache`() {
        val path = Files.createTempFile("discovery-cache", ".dat")
        try {
            Files.writeString(path, "not a cache")
            assertEquals(emptyMap(), ProjectDiscoveryCache.load(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
