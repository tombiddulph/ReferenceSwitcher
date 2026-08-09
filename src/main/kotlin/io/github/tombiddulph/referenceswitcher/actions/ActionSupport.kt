package io.github.tombiddulph.referenceswitcher.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.ui.Messages
import com.intellij.psi.xml.XmlTag
import io.github.tombiddulph.referenceswitcher.model.PackageReferenceInfo
import io.github.tombiddulph.referenceswitcher.service.ReferenceSwitcherService
import java.nio.file.Path

internal fun AnActionEvent.csprojPath(): Path? = CommonDataKeys.VIRTUAL_FILE.getData(dataContext)
    ?.takeIf { it.extension.equals("csproj", true) }
    ?.toNioPath()

internal fun AnActionEvent.packageIdAtCaret(): String? {
    val project = project ?: return null
    val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
    var tag = PsiTreeUtil.getParentOfType(
        psiFile.findElementAt(editor.caretModel.offset.coerceAtMost(editor.document.textLength - 1)),
        XmlTag::class.java,
        false,
    )
    while (tag != null && tag.name != "PackageReference") tag = tag.parentTag
    return tag?.getAttributeValue("Include")
}

internal fun choosePackage(
    service: ReferenceSwitcherService,
    path: Path,
    preferredPackageId: String? = null,
): PackageReferenceInfo? {
    val references = runCatching { service.packageReferences(path) }.getOrElse {
        Messages.showErrorDialog("Could not read $path: ${it.message}", "Local References")
        return null
    }
    preferredPackageId?.let { id -> references.singleOrNull { it.packageId.equals(id, true) }?.let { return it } }
    if (references.isEmpty()) {
        Messages.showInfoMessage("No PackageReference is declared directly in this project.", "Local References")
        return null
    }
    if (references.size == 1) return references.single()
    val selected = Messages.showEditableChooseDialog(
        "Select the package to use locally:", "Use Local Project", null,
        references.map { it.packageId }.distinct().toTypedArray(), references.first().packageId, null,
    ) ?: return null
    return references.singleOrNull { it.packageId == selected } ?: run {
        Messages.showErrorDialog(
            "Multiple PackageReference elements were found for $selected. No changes were made.",
            "Local References",
        )
        null
    }
}

internal fun chooseCsproj(event: AnActionEvent): Path? {
    val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("csproj").apply {
        title = "Select Local Project"
        description = "Select the SDK-style .csproj that publishes this package"
    }
    return FileChooser.chooseFile(descriptor, event.project, null)?.toNioPath()
}
