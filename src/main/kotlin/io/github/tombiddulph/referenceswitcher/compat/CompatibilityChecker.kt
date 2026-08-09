package io.github.tombiddulph.referenceswitcher.compat

import io.github.tombiddulph.referenceswitcher.model.CompatibilityFinding
import io.github.tombiddulph.referenceswitcher.model.CompatibilityReport
import io.github.tombiddulph.referenceswitcher.model.CompatibilitySeverity
import io.github.tombiddulph.referenceswitcher.model.LocalProjectInfo
import io.github.tombiddulph.referenceswitcher.xml.MsBuildXml

object CompatibilityChecker {
    fun check(consumerXml: String, local: LocalProjectInfo): CompatibilityReport {
        val findings = mutableListOf<CompatibilityFinding>()
        val consumerFrameworks = targetFrameworks(MsBuildXml.properties(consumerXml))

        if (consumerFrameworks.isNotEmpty() && local.targetFrameworks.isNotEmpty() &&
            consumerFrameworks.none { consumer -> local.targetFrameworks.any { isCompatible(consumer, it) } }
        ) {
            findings += CompatibilityFinding(
                CompatibilitySeverity.ERROR,
                "No compatible target framework. Consumer: ${consumerFrameworks.joinToString()}; " +
                    "local project: ${local.targetFrameworks.joinToString()}.",
            )
        }
        if (local.shipsBuildAssets) {
            findings += CompatibilityFinding(
                CompatibilitySeverity.WARNING,
                "The local project ships MSBuild build assets that a ProjectReference will not import.",
            )
        }
        return CompatibilityReport(findings)
    }

    fun targetFrameworks(properties: Map<String, String>): List<String> =
        (properties["TargetFrameworks"] ?: properties["TargetFramework"] ?: "")
            .split(';').map(String::trim).filter(String::isNotEmpty)

    internal fun isCompatible(consumer: String, library: String): Boolean {
        val c = parseFramework(consumer) ?: return consumer.equals(library, true)
        val l = parseFramework(library) ?: return consumer.equals(library, true)
        if (c.first == l.first) {
            if (c.first == "net" && isModernNet(consumer) != isModernNet(library)) return false
            return c.second >= l.second
        }
        if (c.first == "net" && l.first == "netstandard") {
            return when {
                isModernNet(consumer) -> l.second <= 21
                c.second >= 461 -> l.second <= 20
                else -> false
            }
        }
        return false
    }

    private fun parseFramework(value: String): Pair<String, Int>? {
        val normalized = value.lowercase().substringBefore('-')
        val family = when {
            normalized.startsWith("netstandard") -> "netstandard"
            normalized.startsWith("netcoreapp") -> "netcoreapp"
            normalized.startsWith("net") -> "net"
            else -> return null
        }
        val number = normalized.removePrefix(family).replace(".", "").toIntOrNull() ?: return null
        return family to number
    }

    private fun isModernNet(value: String): Boolean =
        Regex("^net([5-9]|[1-9][0-9]+)\\.", RegexOption.IGNORE_CASE).containsMatchIn(value)
}
