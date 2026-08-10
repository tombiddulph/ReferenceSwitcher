package io.github.tombiddulph.referenceswitcher.discovery

import io.github.tombiddulph.referenceswitcher.model.LocalProjectInfo
import io.github.tombiddulph.referenceswitcher.model.ReferenceKind
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

internal data class CachedProject(
    val info: LocalProjectInfo,
    val projectModified: Long,
    val inheritedFiles: Map<String, Long>,
)

internal object ProjectDiscoveryCache {
    private const val VERSION = 1

    fun load(path: Path): Map<String, CachedProject> = runCatching {
        if (!Files.isRegularFile(path)) return emptyMap()
        DataInputStream(Files.newInputStream(path).buffered()).use { input ->
            if (input.readInt() != VERSION) return emptyMap()
            buildMap {
                repeat(input.readInt()) {
                    val info = LocalProjectInfo(
                        projectFile = input.readUTF(),
                        packageId = input.readUTF(),
                        targetFrameworks = List(input.readInt()) { input.readUTF() },
                        suggestedKind = ReferenceKind.valueOf(input.readUTF()),
                        shipsBuildAssets = input.readBoolean(),
                    )
                    val projectModified = input.readLong()
                    val inherited = buildMap {
                        repeat(input.readInt()) { put(input.readUTF(), input.readLong()) }
                    }
                    put(info.projectFile.lowercase(), CachedProject(info, projectModified, inherited))
                }
            }
        }
    }.getOrDefault(emptyMap())

    fun save(path: Path, projects: Collection<CachedProject>) {
        runCatching {
            Files.createDirectories(path.parent)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            DataOutputStream(Files.newOutputStream(temporary).buffered()).use { output ->
                output.writeInt(VERSION)
                output.writeInt(projects.size)
                projects.forEach { cached ->
                    output.writeUTF(cached.info.projectFile)
                    output.writeUTF(cached.info.packageId)
                    output.writeInt(cached.info.targetFrameworks.size)
                    cached.info.targetFrameworks.forEach(output::writeUTF)
                    output.writeUTF(cached.info.suggestedKind.name)
                    output.writeBoolean(cached.info.shipsBuildAssets)
                    output.writeLong(cached.projectModified)
                    output.writeInt(cached.inheritedFiles.size)
                    cached.inheritedFiles.forEach { (file, modified) ->
                        output.writeUTF(file)
                        output.writeLong(modified)
                    }
                }
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
