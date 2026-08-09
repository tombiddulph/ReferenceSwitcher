package io.github.tombiddulph.referenceswitcher.compat

import io.github.tombiddulph.referenceswitcher.model.LocalProjectInfo
import io.github.tombiddulph.referenceswitcher.model.ReferenceKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompatibilityCheckerTest {
    @Test
    fun `accepts older net and netstandard targets`() {
        assertTrue(CompatibilityChecker.isCompatible("net8.0", "net6.0"))
        assertTrue(CompatibilityChecker.isCompatible("net8.0", "netstandard2.0"))
    }

    @Test
    fun `rejects newer target`() {
        assertFalse(CompatibilityChecker.isCompatible("net8.0", "net9.0"))
        val local = LocalProjectInfo("/lib/Lib.csproj", "Lib", listOf("net9.0"), ReferenceKind.NORMAL)
        val report = CompatibilityChecker.check(
            "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>",
            local,
        )
        assertFalse(report.canSwitch)
    }
}
