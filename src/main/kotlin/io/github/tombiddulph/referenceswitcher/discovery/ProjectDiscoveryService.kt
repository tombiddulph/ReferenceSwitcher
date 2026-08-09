package io.github.tombiddulph.referenceswitcher.discovery

import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import io.github.tombiddulph.referenceswitcher.compat.CompatibilityChecker
import io.github.tombiddulph.referenceswitcher.model.LocalProjectInfo
import io.github.tombiddulph.referenceswitcher.model.ReferenceKind
import io.github.tombiddulph.referenceswitcher.state.ReferenceSwitcherSettings
import io.github.tombiddulph.referenceswitcher.xml.MsBuildXml
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.io.IOException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

class ProjectDiscoveryService(private val project: Project) {
    private val index = ConcurrentHashMap<String, MutableList<LocalProjectInfo>>()

    fun all(): List<LocalProjectInfo> = index.values.flatten().sortedBy { it.packageId.lowercase() }

    fun find(packageId: String): List<LocalProjectInfo> = index[packageId.lowercase()].orEmpty().toList()

    fun refresh(): Int {
        val discovered = mutableListOf<LocalProjectInfo>()
        ReferenceSwitcherSettings.getInstance().sourceRoots().mapNotNull { runCatching { Path.of(it) }.getOrNull() }
            .filter(Files::isDirectory)
            .forEach { root ->
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        return if (dir != root && dir.fileName.toString().lowercase() in excludedDirectories) {
                            FileVisitResult.SKIP_SUBTREE
                        } else FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile && file.extension.equals("csproj", true)) {
                            inspect(file)?.let(discovered::add)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                })
            }
        index.clear()
        val unique = discovered.distinctBy { it.projectFile.lowercase() }
        unique.forEach { index.computeIfAbsent(it.packageId.lowercase()) { mutableListOf() }.add(it) }
        return unique.size
    }

    fun inspect(path: Path): LocalProjectInfo? = runCatching {
        if (!path.isRegularFile() || !path.extension.equals("csproj", true)) return null
        val xml = path.readText()
        if (!MsBuildXml.isSdkStyle(xml)) return null
        val properties = inheritedProperties(path) + MsBuildXml.properties(xml)
        val projectName = path.nameWithoutExtension
        val packageId = resolve(properties["PackageId"], projectName)
            ?: resolve(properties["AssemblyName"], projectName)
            ?: projectName
        val frameworks = CompatibilityChecker.targetFrameworks(properties)
        val packageRefs = MsBuildXml.packageReferences(xml, path.toString())
        val analyzer = properties["IsRoslynComponent"].equals("true", true) ||
            properties["DevelopmentDependency"].equals("true", true) ||
            packageRefs.any { it.packageId.equals("Microsoft.CodeAnalysis.CSharp", true) } ||
            (frameworks == listOf("netstandard2.0") && packageRefs.any { it.privateAssets.equals("all", true) })
        LocalProjectInfo(
            path.toAbsolutePath().normalize().toString(),
            packageId,
            frameworks,
            if (analyzer) ReferenceKind.ANALYZER else ReferenceKind.NORMAL,
            MsBuildXml.shipsBuildAssets(xml),
        )
    }.onFailure {
        logger.warn("Could not inspect local project: $path", it)
    }.getOrNull()

    private fun inheritedProperties(projectFile: Path): Map<String, String> {
        val parents = generateSequence(projectFile.parent) { it.parent }.toList().asReversed()
        val result = linkedMapOf<String, String>()
        parents.forEach { directory ->
            val props = directory.resolve("Directory.Build.props")
            if (props.isRegularFile()) result.putAll(MsBuildXml.properties(props.readText()))
        }
        return result
    }

    private fun resolve(value: String?, projectName: String): String? {
        if (value.isNullOrBlank()) return null
        val resolved = value.replace("$(MSBuildProjectName)", projectName)
        return resolved.takeUnless { it.contains("$(") }
    }

    companion object {
        private val logger = Logger.getInstance(ProjectDiscoveryService::class.java)
        private val excludedDirectories = setOf("bin", "obj", "node_modules", ".git", ".vs", "artifacts")

        fun getInstance(project: Project): ProjectDiscoveryService = project.getService(ProjectDiscoveryService::class.java)
    }
}
