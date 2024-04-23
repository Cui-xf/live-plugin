package liveplugin.implementation.actions.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

class NameDirectoryDialog(
    val project: Project?,
    title: String,
    defaultDirectory: String,
    defaultName: String?
) : DialogWrapper(project, true) {
    private lateinit var nameField: JBTextField
    private lateinit var directoryField: MyTextFieldWithBrowseButton

    init {
        initComponents(defaultDirectory, defaultName)
        this.title = title
        init()
    }

    private fun initComponents(defaultDirectory: String, defaultName: String?) {
        nameField = JBTextField(defaultName)
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                directoryField.trySetChildPath(nameField.text.trim())
            }
        })

        val fcd = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        fcd.isShowFileSystemRoots = true
        fcd.isHideIgnored = false
        directoryField = MyTextFieldWithBrowseButton(defaultDirectory)
        directoryField.addBrowseFolderListener("Choose Directory", "Select a parent directory", project, fcd)
    }

    override fun doValidate(): ValidationInfo? {
        return validateDirectory() ?: validateName()
    }

    override fun continuousValidation(): Boolean {
        return true
    }

    private fun validateName(): ValidationInfo? {
        val name = getName()
        return when {
            name == null                                      -> ValidationInfo("Name is empty", nameField)
            "[<>:\"/\\\\|?*]".toRegex().containsMatchIn(name) -> ValidationInfo("Invalid name", nameField)

            else                                              -> null
        }
    }

    private fun validateDirectory(): ValidationInfo? {
        val directoryPath = getDirectory()
        if (directoryPath.isEmpty()) return ValidationInfo("Directory is empty", directoryField)
        if (PluginManager.containsPlugin(directoryPath)) return ValidationInfo("There is already a plugin with this directory", nameField)
        try {
            val path: Path = Paths.get(directoryPath)
            if (!path.toFile().exists()) {
                return null
            } else if (!path.toFile().isDirectory) {
                return ValidationInfo("Path not a directory", directoryField)
            } else {
                val directoryStream = Files.newDirectoryStream(path)
                if (directoryStream.iterator().hasNext()) {
                    return ValidationInfo("The directory already exists and it is not empty", directoryField)
                }
            }
        } catch (e: Exception) {
            return ValidationInfo("Invalid directory path", directoryField)
        }
        return null
    }

    private fun getName(): String? {
        return nameField.text?.trim()
    }

    private fun getDirectory(): String {
        return directoryField.text.trim()
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return nameField
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Name:") {
                cell(nameField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }

            row("Directory:") {
                cell(directoryField)
                    .align(AlignX.FILL)
            }
        }.withMinimumWidth(500)
    }

    companion object {
        fun show(project: Project?, title: String, defaultDirectory: String, defaultName: String?): Pair<String?, String?>? {
            val dialog = NameDirectoryDialog(project, title, defaultDirectory, defaultName)
            return if (dialog.showAndGet()) Pair(dialog.getName(), dialog.getDirectory()) else null
        }
    }
}

private class MyTextFieldWithBrowseButton(defaultParentPath: String) : TextFieldWithBrowseButton() {
    private val myDefaultParentPath: Path = Paths.get(defaultParentPath).toAbsolutePath()
    private var myModifiedByUser = false

    init {
        text = myDefaultParentPath.toString()
        textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                myModifiedByUser = true
            }
        })
    }

    fun trySetChildPath(child: String) {
        if (!myModifiedByUser) {
            try {
                text = myDefaultParentPath.resolve(child).toString()
            } catch (ignored: InvalidPathException) {
            } finally {
                myModifiedByUser = false
            }
        }
    }
}