/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.refactoring.move.moveDeclarations.ui

import com.intellij.ide.util.DirectoryUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.help.HelpManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextComponentAccessor
import com.intellij.openapi.util.Disposer
import com.intellij.psi.*
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.copy.CopyFilesOrDirectoriesDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.NonFocusableCheckBox
import com.intellij.ui.RecentsManager
import com.intellij.ui.TextFieldWithHistoryWithBrowseButton
import com.intellij.ui.components.JBLabelDecorator
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import org.jetbrains.kotlin.idea.core.packageMatchesDirectoryOrImplicit
import org.jetbrains.kotlin.idea.core.util.onTextChange
import org.jetbrains.kotlin.idea.refactoring.isInJavaSourceRoot
import org.jetbrains.kotlin.idea.util.application.executeCommand
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import javax.swing.JComponent

class KotlinAwareMoveFilesOrDirectoriesDialog(
    private val project: Project,
    private val initialDirectory: PsiDirectory?,
    private val callback: (KotlinAwareMoveFilesOrDirectoriesDialog?) -> Unit
) : DialogWrapper(project, true) {
    companion object {
        private const val RECENT_KEYS = "MoveFile.RECENT_KEYS"
        private const val MOVE_FILES_OPEN_IN_EDITOR = "MoveFile.OpenInEditor"
    }

    private val nameLabel = JBLabelDecorator.createJBLabelDecorator().setBold(true)
    private val targetDirectoryField = TextFieldWithHistoryWithBrowseButton()
    private val searchReferencesCb = NonFocusableCheckBox("Search r${UIUtil.MNEMONIC}eferences").apply { isSelected = true }
    private val openInEditorCb = NonFocusableCheckBox("Open moved files in editor")
    private val updatePackageDirectiveCb = NonFocusableCheckBox()

    private var helpID: String? = null
    var targetDirectory: PsiDirectory? = null
        private set

    init {
        title = RefactoringBundle.message("move.title")
        init()
    }

    val updatePackageDirective: Boolean
        get() = updatePackageDirectiveCb.isSelected

    val searchReferences: Boolean
        get() = searchReferencesCb.isSelected

    override fun createActions() = arrayOf(okAction, cancelAction, helpAction)

    override fun getPreferredFocusedComponent() = targetDirectoryField.childComponent

    override fun createCenterPanel() = null

    override fun createNorthPanel(): JComponent {
        RecentsManager.getInstance(project).getRecentEntries(RECENT_KEYS)?.let { targetDirectoryField.childComponent.history = it }

        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        targetDirectoryField.addBrowseFolderListener(
            RefactoringBundle.message("select.target.directory"),
            RefactoringBundle.message("the.file.will.be.moved.to.this.directory"),
            project,
            descriptor,
            TextComponentAccessor.TEXT_FIELD_WITH_HISTORY_WHOLE_TEXT
        )
        val textField = targetDirectoryField.childComponent.textEditor
        FileChooserFactory.getInstance().installFileCompletion(textField, descriptor, true, disposable)
        textField.onTextChange { validateOKButton() }
        targetDirectoryField.setTextFieldPreferredWidth(CopyFilesOrDirectoriesDialog.MAX_PATH_LENGTH)
        Disposer.register(disposable, targetDirectoryField)

        openInEditorCb.isSelected = isOpenInEditor()

        val shortcutText = KeymapUtil.getFirstKeyboardShortcutText(ActionManager.getInstance().getAction(IdeActions.ACTION_CODE_COMPLETION))
        return FormBuilder.createFormBuilder()
            .addComponent(nameLabel)
            .addLabeledComponent(RefactoringBundle.message("move.files.to.directory.label"), targetDirectoryField, UIUtil.LARGE_VGAP)
            .addTooltip(RefactoringBundle.message("path.completion.shortcut", shortcutText))
            .addComponentToRightColumn(searchReferencesCb, UIUtil.LARGE_VGAP)
            .addComponentToRightColumn(openInEditorCb, UIUtil.LARGE_VGAP)
            .addComponentToRightColumn(updatePackageDirectiveCb, UIUtil.LARGE_VGAP)
            .panel
    }

    fun setData(psiElements: Array<out PsiElement>, initialTargetDirectory: PsiDirectory?, helpID: String) {
        val psiElement = psiElements.singleOrNull()
        if (psiElement != null) {
            val shortenedPath = CopyFilesOrDirectoriesDialog.shortenPath((psiElement as PsiFileSystemItem).virtualFile)
            nameLabel.text = when (psiElement) {
                is PsiFile -> RefactoringBundle.message("move.file.0", shortenedPath)
                else -> RefactoringBundle.message("move.directory.0", shortenedPath)
            }
        } else {
            val isFile = psiElements.all { it is PsiFile }
            val isDirectory = psiElements.all { it is PsiDirectory }
            nameLabel.text = when {
                isFile -> RefactoringBundle.message("move.specified.files")
                isDirectory -> RefactoringBundle.message("move.specified.directories")
                else -> RefactoringBundle.message("move.specified.elements")
            }
        }

        targetDirectoryField.childComponent.text = initialTargetDirectory?.virtualFile?.presentableUrl ?: ""

        validateOKButton()
        this.helpID = helpID

        with(updatePackageDirectiveCb) {
            val jetFiles = psiElements.filterIsInstance<KtFile>().filter(KtFile::isInJavaSourceRoot)
            if (jetFiles.isEmpty()) {
                parent.remove(updatePackageDirectiveCb)
                return
            }

            val singleFile = jetFiles.singleOrNull()
            isSelected = singleFile == null || singleFile.packageMatchesDirectoryOrImplicit()
            text = "Update package directive (Kotlin files)"
        }
    }

    override fun doHelpAction() = HelpManager.getInstance().invokeHelp(helpID)

    private fun isOpenInEditor(): Boolean {
        if (ApplicationManager.getApplication().isUnitTestMode) return false
        return PropertiesComponent.getInstance().getBoolean(MOVE_FILES_OPEN_IN_EDITOR, false)
    }

    private fun validateOKButton() {
        isOKActionEnabled = targetDirectoryField.childComponent.text.isNotEmpty()
    }

    override fun doOKAction() {
        PropertiesComponent.getInstance().setValue(MOVE_FILES_OPEN_IN_EDITOR, openInEditorCb.isSelected, false)
        RecentsManager.getInstance(project).registerRecentEntry(RECENT_KEYS, targetDirectoryField.childComponent.text)

        if (DumbService.isDumb(project)) {
            Messages.showMessageDialog(project, "Move refactoring is not available while indexing is in progress", "Indexing", null)
            return
        }

        project.executeCommand(RefactoringBundle.message("move.title"), null) {
            runWriteAction {
                val directoryName = targetDirectoryField.childComponent.text.replace(File.separatorChar, '/').let {
                    when {
                        it.startsWith(".") -> (initialDirectory?.virtualFile?.path ?: "") + "/" + it
                        else -> it
                    }
                }
                try {
                    targetDirectory = DirectoryUtil.mkdirs(PsiManager.getInstance(project), directoryName)
                } catch (e: IncorrectOperationException) {
                    // ignore
                }
            }

            if (targetDirectory == null) {
                CommonRefactoringUtil.showErrorMessage(
                    title,
                    RefactoringBundle.message("cannot.create.directory"),
                    helpID,
                    project
                )
                return@executeCommand
            }

            callback(this@KotlinAwareMoveFilesOrDirectoriesDialog)
        }
    }
}