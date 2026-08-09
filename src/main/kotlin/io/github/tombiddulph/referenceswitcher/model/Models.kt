package io.github.tombiddulph.referenceswitcher.model

enum class ReferenceKind { NORMAL, ANALYZER }

data class ItemGroupAnchor(val index: Int, val condition: String = "")

data class PackageReferenceInfo(
    val packageId: String,
    val projectFile: String,
    val originalXml: String,
    val anchor: ItemGroupAnchor,
    val condition: String = "",
    val privateAssets: String = "",
)

data class LocalProjectInfo(
    val projectFile: String,
    val packageId: String,
    val targetFrameworks: List<String>,
    val suggestedKind: ReferenceKind,
    val shipsBuildAssets: Boolean = false,
)

data class ActiveSwitch(
    var projectFile: String = "",
    var packageId: String = "",
    var localProject: String = "",
    var referenceKind: ReferenceKind = ReferenceKind.NORMAL,
    var itemGroupIndex: Int = 0,
    var itemGroupCondition: String = "",
    var originalReference: String = "",
    var stale: Boolean = false,
)

data class ReferenceMapping(
    var packageId: String = "",
    var projectFile: String = "",
    var referenceKind: ReferenceKind = ReferenceKind.NORMAL,
)

enum class CompatibilitySeverity { INFO, WARNING, ERROR }

data class CompatibilityFinding(val severity: CompatibilitySeverity, val message: String)

data class CompatibilityReport(val findings: List<CompatibilityFinding>) {
    val errors get() = findings.filter { it.severity == CompatibilitySeverity.ERROR }
    val warnings get() = findings.filter { it.severity == CompatibilitySeverity.WARNING }
    val canSwitch get() = errors.isEmpty()
}

sealed interface SwitchResult {
    data class Success(val xml: String) : SwitchResult
    data class Failure(val message: String) : SwitchResult
}
