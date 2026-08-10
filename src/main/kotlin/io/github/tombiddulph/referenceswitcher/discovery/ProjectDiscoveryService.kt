package io.github.tombiddulph.referenceswitcher.discovery

import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressManager
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
import java.util.concurrent.locks.ReentrantLock
import java.security.MessageDigest
import kotlin.concurrent.withLock
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

class ProjectDiscoveryService(private val project: Project) {
    private val index = ConcurrentHashMap<String, MutableList<LocalProjectInfo>>()
    private val refreshLock = ReentrantLock()
    private val cachePath = Path.of(
        PathManager.getSystemPath(),
        "reference-switcher",
        "projects-${runCatching { project.locationHash }.getOrNull()?.takeIf(String::isNotBlank) ?: pathHash(project.name.orEmpty())}.dat",
    )
    private var cachedProjects = ProjectDiscoveryCache.load(cachePath)

    init {
        replaceIndex(cachedProjects.values.map { it.info })
    }

    fun all(): List<LocalProjectInfo> = index.values.flatten().sortedBy { it.packageId.lowercase() }

    fun find(packageId: String): List<LocalProjectInfo> = index[packageId.lowercase()].orEmpty().toList()

    fun isRefreshing(): Boolean = refreshLock.isLocked

    fun refresh(): Int = refreshLock.withLock {
        val started = System.nanoTime()
        val discovered = mutableListOf<LocalProjectInfo>()
        val refreshedCache = mutableMapOf<String, CachedProject>()
        val inheritedProperties = mutableMapOf<Path, Map<String, String>>()
        var reused = 0
        var inspected = 0
        val roots = sourceRoots(ReferenceSwitcherSettings.getInstance().sourceRoots())
        roots.forEach { root ->
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        ProgressManager.checkCanceled()
                        return if (dir != root && dir.fileName.toString().lowercase() in excludedDirectories) {
                            FileVisitResult.SKIP_SUBTREE
                        } else FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        ProgressManager.checkCanceled()
                        if (attrs.isRegularFile && file.extension.equals("csproj", true)) {
                            val normalized = file.toAbsolutePath().normalize()
                            val key = normalized.toString().lowercase()
                            val previous = cachedProjects[key]
                            val cached = previous?.takeIf { isCurrent(normalized, it) } ?: run {
                                inspected++
                                inspectCached(normalized, inheritedProperties)
                            }
                            if (cached != null) {
                                if (cached === previous) reused++
                                refreshedCache[key] = cached
                                discovered.add(cached.info)
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                })
            }
        ProgressManager.checkCanceled()
        val unique = discovered.distinctBy { it.projectFile.lowercase() }
        replaceIndex(unique)
        cachedProjects = refreshedCache
        ProjectDiscoveryCache.save(cachePath, refreshedCache.values)
        logger.info(
            "Local project discovery found ${unique.size} projects in " +
                "${(System.nanoTime() - started) / 1_000_000} ms ($reused cached, $inspected inspected)"
        )
        unique.size
    }

    fun inspect(path: Path): LocalProjectInfo? = inspect(path, mutableMapOf())

    internal fun sourceRoots(configuredRoots: List<String>): List<Path> = configuredRoots
        .mapNotNull { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
        .filter(Files::isDirectory)
        .distinct()
        .let { candidates ->
            candidates.filter { candidate -> candidates.none { it != candidate && candidate.startsWith(it) } }
        }

    private fun inspect(path: Path, propertyCache: MutableMap<Path, Map<String, String>>): LocalProjectInfo? = runCatching {
        if (!path.isRegularFile() || !path.extension.equals("csproj", true)) return null
        val xml = path.readText()
        val metadata = MsBuildXml.discoveryMetadata(xml)
        if (!metadata.sdkStyle) return null
        val properties = inheritedProperties(path, propertyCache) + metadata.properties
        val projectName = path.nameWithoutExtension
        val packageId = resolve(properties["PackageId"], projectName)
            ?: resolve(properties["AssemblyName"], projectName)
            ?: projectName
        val frameworks = CompatibilityChecker.targetFrameworks(properties)
        val analyzer = properties["IsRoslynComponent"].equals("true", true) ||
            properties["DevelopmentDependency"].equals("true", true) ||
            metadata.packageReferences.any { it.first.equals("Microsoft.CodeAnalysis.CSharp", true) } ||
            (frameworks == listOf("netstandard2.0") && metadata.packageReferences.any { it.second.equals("all", true) })
        LocalProjectInfo(
            path.toAbsolutePath().normalize().toString(),
            packageId,
            frameworks,
            if (analyzer) ReferenceKind.ANALYZER else ReferenceKind.NORMAL,
            metadata.shipsBuildAssets,
        )
    }.onFailure {
        logger.warn("Could not inspect local project: $path", it)
    }.getOrNull()

    private fun inspectCached(
        path: Path,
        propertyCache: MutableMap<Path, Map<String, String>>,
    ): CachedProject? {
        val inheritedFiles = inheritedPropertyFiles(path).associate { it.toString() to Files.getLastModifiedTime(it).toMillis() }
        val info = inspect(path, propertyCache) ?: return null
        return CachedProject(info, Files.getLastModifiedTime(path).toMillis(), inheritedFiles)
    }

    private fun isCurrent(path: Path, cached: CachedProject): Boolean = runCatching {
        Files.getLastModifiedTime(path).toMillis() == cached.projectModified &&
            inheritedPropertyFiles(path).associate { it.toString() to Files.getLastModifiedTime(it).toMillis() } ==
            cached.inheritedFiles
    }.getOrDefault(false)

    private fun replaceIndex(projects: Collection<LocalProjectInfo>) {
        val replacement = projects.groupByTo(ConcurrentHashMap()) { it.packageId.lowercase() }
            .mapValues { it.value.toMutableList() }
        index.clear()
        index.putAll(replacement)
    }

    private fun inheritedProperties(
        projectFile: Path,
        propertyCache: MutableMap<Path, Map<String, String>>,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        inheritedPropertyFiles(projectFile).forEach { props ->
            result.putAll(propertyCache.getOrPut(props) { MsBuildXml.properties(props.readText()) })
        }
        return result
    }

    private fun inheritedPropertyFiles(projectFile: Path): List<Path> =
        generateSequence(projectFile.parent) { it.parent }.toList().asReversed()
            .map { it.resolve("Directory.Build.props") }
            .filter(Path::isRegularFile)

    private fun resolve(value: String?, projectName: String): String? {
        if (value.isNullOrBlank()) return null
        val resolved = value.replace("$(MSBuildProjectName)", projectName)
        return resolved.takeUnless { it.contains("$(") }
    }

    companion object {
        private val logger = Logger.getInstance(ProjectDiscoveryService::class.java)
        private val excludedDirectories = setOf("bin", "obj", "node_modules", ".git", ".vs", "artifacts")

        private fun pathHash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        fun getInstance(project: Project): ProjectDiscoveryService = project.getService(ProjectDiscoveryService::class.java)
    }
}
