package io.github.tombiddulph.referenceswitcher.xml

import io.github.tombiddulph.referenceswitcher.model.ItemGroupAnchor
import io.github.tombiddulph.referenceswitcher.model.PackageReferenceInfo
import io.github.tombiddulph.referenceswitcher.model.ProjectDiscoveryMetadata
import io.github.tombiddulph.referenceswitcher.model.ReferenceKind
import io.github.tombiddulph.referenceswitcher.model.SwitchResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

object MsBuildXml {
    fun discoveryMetadata(xml: String): ProjectDiscoveryMetadata {
        val document = parse(xml)
        val root = document.documentElement
        val references = itemGroups(document).flatMap { group ->
            childElements(group, "PackageReference").mapNotNull { element ->
                element.getAttribute("Include").takeIf(String::isNotBlank)?.let {
                    it to attributeOrChild(element, "PrivateAssets")
                }
            }
        }
        return ProjectDiscoveryMetadata(
            sdkStyle = root.hasAttribute("Sdk") || root.getElementsByTagName("Sdk").length > 0,
            properties = properties(document),
            packageReferences = references,
            shipsBuildAssets = shipsBuildAssets(document),
        )
    }

    fun packageReferences(xml: String, projectFile: String): List<PackageReferenceInfo> {
        val document = parse(xml)
        val references = itemGroups(document).flatMapIndexed { index, group ->
            childElements(group, "PackageReference").mapNotNull { element ->
                val packageId = element.getAttribute("Include")
                packageId.takeIf(String::isNotBlank)?.let {
                    PackageReferenceInfo(
                        packageId = it,
                        projectFile = projectFile,
                        originalXml = serialize(element, omitDeclaration = true),
                        anchor = ItemGroupAnchor(index, group.getAttribute("Condition")),
                        condition = element.getAttribute("Condition"),
                        privateAssets = attributeOrChild(element, "PrivateAssets"),
                    )
                }
            }
        }
        val sourceElements = elementRanges(xml, "PackageReference")
            .filter { it.element.getAttribute("Include").isNotBlank() }
            .groupBy { it.element.getAttribute("Include").lowercase() }
            .mapValues { ArrayDeque(it.value) }
        return references.map { reference ->
            val source = sourceElements[reference.packageId.lowercase()]?.removeFirstOrNull()
            reference.copy(originalXml = source?.text ?: reference.originalXml)
        }
    }

    fun switchToProject(
        xml: String,
        packageId: String,
        consumerProject: Path,
        localProject: Path,
        kind: ReferenceKind,
    ): SwitchResult = runCatching {
        val document = parse(xml)
        val matches = itemGroups(document).flatMap { group ->
            childElements(group, "PackageReference").filter {
                it.getAttribute("Include").equals(packageId, true)
            }
        }
        if (matches.size != 1) {
            return SwitchResult.Failure("Expected exactly one PackageReference for $packageId, found ${matches.size}.")
        }

        val sourceMatches = elementRanges(xml, "PackageReference").filter {
            it.element.getAttribute("Include").equals(packageId, true)
        }
        if (sourceMatches.size != 1) {
            return SwitchResult.Failure("Could not locate the source XML for $packageId without ambiguity.")
        }
        val relative = consumerProject.parent.relativize(localProject).toString().replace('\\', '/')
        val attributes = mutableListOf("Include=\"${escapeAttribute(relative)}\"")
        matches.single().getAttribute("Condition").takeIf(String::isNotBlank)?.let {
            attributes += "Condition=\"${escapeAttribute(it)}\""
        }
        if (kind == ReferenceKind.ANALYZER) {
            attributes += "OutputItemType=\"Analyzer\""
            attributes += "ReferenceOutputAssembly=\"false\""
        }
        val replacement = "<ProjectReference ${attributes.joinToString(" ")} />"
        SwitchResult.Success(replaceRange(xml, sourceMatches.single(), replacement))
    }.getOrElse { SwitchResult.Failure("Could not read the project XML: ${it.message}") }

    fun restorePackage(xml: String, state: io.github.tombiddulph.referenceswitcher.model.ActiveSwitch): SwitchResult = runCatching {
        val document = parse(xml)
        val expected = Path.of(state.projectFile).parent.relativize(Path.of(state.localProject)).toString()
            .replace('\\', '/')
        val matches = itemGroups(document).flatMap { group ->
            childElements(group, "ProjectReference").filter {
                normalizePath(it.getAttribute("Include")) == normalizePath(expected)
            }
        }
        if (matches.size != 1) {
            return SwitchResult.Failure(
                "${state.packageId} is no longer using the ProjectReference created by Local References. No changes were made."
            )
        }
        parseFragment(document, state.originalReference)
        val sourceMatches = elementRanges(xml, "ProjectReference").filter {
            normalizePath(it.element.getAttribute("Include")) == normalizePath(expected)
        }
        if (sourceMatches.size != 1) {
            return SwitchResult.Failure("Could not locate the managed ProjectReference without ambiguity.")
        }
        SwitchResult.Success(replaceRange(xml, sourceMatches.single(), state.originalReference))
    }.getOrElse { SwitchResult.Failure("Could not restore ${state.packageId}: ${it.message}") }

    fun projectReferenceExists(xml: String, consumer: Path, target: Path): Boolean {
        val expected = normalizePath(consumer.parent.relativize(target).toString())
        return runCatching {
            itemGroups(parse(xml)).flatMap { childElements(it, "ProjectReference") }
                .any { normalizePath(it.getAttribute("Include")) == expected }
        }.getOrDefault(false)
    }

    fun properties(xml: String): Map<String, String> {
        return properties(parse(xml))
    }

    private fun properties(document: Document): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val groups = document.documentElement.childNodes
        for (i in 0 until groups.length) {
            val group = groups.item(i) as? Element ?: continue
            if (group.tagName != "PropertyGroup") continue
            val children = group.childNodes
            for (j in 0 until children.length) {
                val child = children.item(j) as? Element ?: continue
                if (!result.containsKey(child.tagName) && child.textContent.isNotBlank()) {
                    result[child.tagName] = child.textContent.trim()
                }
            }
        }
        return result
    }

    fun isSdkStyle(xml: String): Boolean {
        val root = parse(xml).documentElement
        return root.hasAttribute("Sdk") || root.getElementsByTagName("Sdk").length > 0
    }

    fun shipsBuildAssets(xml: String): Boolean = runCatching {
        shipsBuildAssets(parse(xml))
    }.getOrDefault(false)

    private fun shipsBuildAssets(document: Document): Boolean {
        val tags = listOf("None", "Content")
        return tags.any { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.any {
                it.getAttribute("Pack").equals("true", true) &&
                    it.getAttribute("PackagePath").replace('\\', '/').startsWith("build", true)
            }
        }
    }

    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml.removePrefix("\uFEFF"))))
    }

    private fun itemGroups(document: Document): List<Element> =
        childElements(document.documentElement, "ItemGroup")

    private fun childElements(parent: Element, name: String): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == name }
    }

    private fun attributeOrChild(element: Element, name: String): String =
        element.getAttribute(name).ifBlank { childElements(element, name).firstOrNull()?.textContent?.trim().orEmpty() }

    private fun parseFragment(owner: Document, fragment: String): Element {
        val parsed = parse("<Root>$fragment</Root>").documentElement.firstChild
        var current = parsed
        while (current != null && current !is Element) current = current.nextSibling
        return owner.importNode(current ?: error("Stored PackageReference is invalid"), true) as Element
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/').trimStart('.').lowercase()

    private data class ElementRange(val start: Int, val end: Int, val text: String, val element: Element)

    private fun elementRanges(xml: String, name: String): List<ElementRange> {
        val opening = Regex("<$name\\b[^>]*>")
        val closing = Regex("</$name\\s*>")
        return opening.findAll(xml).mapNotNull { match ->
            val end = if (match.value.trimEnd().endsWith("/>")) {
                match.range.last + 1
            } else {
                closing.find(xml, match.range.last + 1)?.range?.last?.plus(1) ?: return@mapNotNull null
            }
            val text = xml.substring(match.range.first, end)
            val element = runCatching { parseFragment(parse("<Root/>"), text) }.getOrNull() ?: return@mapNotNull null
            ElementRange(match.range.first, end, text, element)
        }.toList()
    }

    private fun replaceRange(xml: String, range: ElementRange, replacement: String): String =
        xml.substring(0, range.start) + replacement + xml.substring(range.end)

    private fun escapeAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun serialize(node: org.w3c.dom.Node, omitDeclaration: Boolean = false): String {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, if (omitDeclaration) "yes" else "yes")
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        }
        return StringWriter().also { transformer.transform(DOMSource(node), StreamResult(it)) }.toString()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim() +
            if (node is Document) "\n" else ""
    }
}
