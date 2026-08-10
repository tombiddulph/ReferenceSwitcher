package io.github.tombiddulph.referenceswitcher.xml

import io.github.tombiddulph.referenceswitcher.model.ActiveSwitch
import io.github.tombiddulph.referenceswitcher.model.ReferenceKind
import io.github.tombiddulph.referenceswitcher.model.SwitchResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.nio.file.Path

class MsBuildXmlTest {
    @Test
    fun `switches and restores package reference`() {
        val original = project("""
            <ItemGroup>
              <PackageReference Include="Company.Messaging" Version="2.4.1" PrivateAssets="all" />
            </ItemGroup>
        """)
        val consumer = Path.of("/repo/app/App.csproj")
        val local = Path.of("/repo/Messaging/Messaging.csproj")
        val packageReference = MsBuildXml.packageReferences(original, consumer.toString()).single()

        val switched = assertIs<SwitchResult.Success>(
            MsBuildXml.switchToProject(original, "Company.Messaging", consumer, local, ReferenceKind.NORMAL)
        ).xml
        assertContains(switched, "ProjectReference Include=\"../Messaging/Messaging.csproj\"")
        assertFalse(switched.contains('\r'))

        val state = ActiveSwitch(
            consumer.toString(), "Company.Messaging", local.toString(), ReferenceKind.NORMAL,
            packageReference.anchor.index, packageReference.anchor.condition, packageReference.originalXml,
        )
        val restored = assertIs<SwitchResult.Success>(MsBuildXml.restorePackage(switched, state)).xml
        val restoredReference = MsBuildXml.packageReferences(restored, consumer.toString()).single()
        assertEquals("Company.Messaging", restoredReference.packageId)
        assertContains(restoredReference.originalXml, "Version=\"2.4.1\"")
        assertContains(restoredReference.originalXml, "PrivateAssets=\"all\"")
        assertEquals(original, restored)
    }

    @Test
    fun `creates analyzer reference`() {
        val xml = project("<ItemGroup><PackageReference Include=\"Company.Generators\" PrivateAssets=\"all\" /></ItemGroup>")
        val result = assertIs<SwitchResult.Success>(
            MsBuildXml.switchToProject(
                xml, "Company.Generators", Path.of("C:/src/App/App.csproj"),
                Path.of("C:/src/Generators/Generators.csproj"), ReferenceKind.ANALYZER,
            )
        ).xml
        assertContains(result, "OutputItemType=\"Analyzer\"")
        assertContains(result, "ReferenceOutputAssembly=\"false\"")
    }

    @Test
    fun `preserves conditional item group`() {
        val xml = project("""
            <ItemGroup Condition="'$(TargetFramework)' == 'net8.0'">
              <PackageReference Include="Example" Condition="'$(Configuration)' == 'Debug'" />
            </ItemGroup>
        """)
        val info = MsBuildXml.packageReferences(xml, "/src/App.csproj").single()
        assertEquals("'$(TargetFramework)' == 'net8.0'", info.anchor.condition)
        val result = assertIs<SwitchResult.Success>(
            MsBuildXml.switchToProject(
                xml, "Example", Path.of("/src/App.csproj"), Path.of("/lib/Lib.csproj"), ReferenceKind.NORMAL,
            )
        ).xml
        assertContains(result, "Condition=\"'$(Configuration)' == 'Debug'\"")
        assertContains(result, "Condition=\"'$(TargetFramework)' == 'net8.0'\"")
    }

    @Test
    fun `refuses duplicate package references`() {
        val xml = project("""
            <ItemGroup><PackageReference Include="Example" /></ItemGroup>
            <ItemGroup><PackageReference Include="Example" /></ItemGroup>
        """)
        val result = MsBuildXml.switchToProject(
            xml, "Example", Path.of("/src/App.csproj"), Path.of("/lib/Lib.csproj"), ReferenceKind.NORMAL,
        )
        assertIs<SwitchResult.Failure>(result)
        assertContains(result.message, "exactly one")
    }

    @Test
    fun `does not offer PackageReference Update items`() {
        val xml = project("""
            <ItemGroup>
              <PackageReference Update="Inherited.Package" PrivateAssets="all" />
              <PackageReference Include="Direct.Package" Version="1.0.0" />
            </ItemGroup>
        """)
        val references = MsBuildXml.packageReferences(xml, "/src/App.csproj")
        assertEquals(listOf("Direct.Package"), references.map { it.packageId })
        assertContains(references.single().originalXml, "Include=\"Direct.Package\"")
    }

    @Test
    fun `detects sdk projects and packaged build assets`() {
        val xml = project("""
            <PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup>
            <ItemGroup><None Include="build/Example.targets" Pack="true" PackagePath="build/" /></ItemGroup>
        """)
        assertTrue(MsBuildXml.isSdkStyle(xml))
        assertTrue(MsBuildXml.shipsBuildAssets(xml))
        assertFalse(MsBuildXml.shipsBuildAssets(project("")))
    }

    @Test
    fun `extracts discovery metadata in one operation`() {
        val metadata = MsBuildXml.discoveryMetadata(project("""
            <PropertyGroup><PackageId>Example.Package</PackageId></PropertyGroup>
            <ItemGroup>
              <PackageReference Include="Microsoft.CodeAnalysis.CSharp" PrivateAssets="all" />
              <None Include="build/Example.targets" Pack="true" PackagePath="build/" />
            </ItemGroup>
        """))

        assertTrue(metadata.sdkStyle)
        assertEquals("Example.Package", metadata.properties["PackageId"])
        assertEquals(listOf("Microsoft.CodeAnalysis.CSharp" to "all"), metadata.packageReferences)
        assertTrue(metadata.shipsBuildAssets)
    }

    @Test
    fun `changes only selected reference text`() {
        val original = """<Project Sdk="Microsoft.NET.Sdk">

    <PropertyGroup>
        <TargetFramework>net8.0</TargetFramework>
    </PropertyGroup>

    <ItemGroup>
        <PackageReference Include="First" Version="1.0.0" />
        <PackageReference Include="Selected" Version="2.0.0" />
        <PackageReference Include="Last" Version="3.0.0" />
    </ItemGroup>
</Project>
"""
        val expected = original.replace(
            "<PackageReference Include=\"Selected\" Version=\"2.0.0\" />",
            "<ProjectReference Include=\"../Selected/Selected.csproj\" />",
        )
        val result = assertIs<SwitchResult.Success>(
            MsBuildXml.switchToProject(
                original, "Selected", Path.of("/src/App.csproj"),
                Path.of("/Selected/Selected.csproj"), ReferenceKind.NORMAL,
            )
        )
        assertEquals(expected, result.xml)
    }

    @Test
    fun `preserves CRLF outside selected reference`() {
        val original = (
            "<Project Sdk=\"Microsoft.NET.Sdk\">\n" +
                "  <ItemGroup>\n" +
                "    <PackageReference Include=\"Selected\" Version=\"2.0.0\" />\n" +
                "  </ItemGroup>\n" +
                "</Project>\n"
            ).replace("\n", "\r\n")
        val result = assertIs<SwitchResult.Success>(
            MsBuildXml.switchToProject(
                original, "Selected", Path.of("C:/src/App.csproj"),
                Path.of("C:/Selected/Selected.csproj"), ReferenceKind.NORMAL,
            )
        )
        assertEquals(5, Regex("\r\n").findAll(result.xml).count())
        assertEquals(original.replace(
            "<PackageReference Include=\"Selected\" Version=\"2.0.0\" />",
            "<ProjectReference Include=\"../Selected/Selected.csproj\" />",
        ), result.xml)
    }

    private fun project(body: String) = "<Project Sdk=\"Microsoft.NET.Sdk\">$body</Project>"
}
