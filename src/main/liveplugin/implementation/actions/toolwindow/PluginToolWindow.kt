package liveplugin.implementation.actions.toolwindow

import com.intellij.ide.actions.CollapseAllAction
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.PopupMenuPreloader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.EditSourceOnEnterKeyHandler
import com.intellij.util.ui.tree.TreeUtil
import liveplugin.implementation.PluginManager
import liveplugin.implementation.actions.*
import liveplugin.implementation.actions.gist.AddPluginFromGistAction
import liveplugin.implementation.actions.git.AddPluginFromGitHubDelegateAction
import liveplugin.implementation.actions.git.SharePluginAsGistDelegateAction
import liveplugin.implementation.actions.settings.AddLivePluginAndIdeJarsAsDependencies
import liveplugin.implementation.actions.settings.RunPluginsOnIDEStartAction
import liveplugin.implementation.actions.settings.RunProjectSpecificPluginsAction
import liveplugin.implementation.actions.toolwindow.NewElementPopupAction.Companion.livePluginNewElementPopup
import liveplugin.implementation.common.Icons.addPluginIcon
import liveplugin.implementation.common.Icons.collapseAllIcon
import liveplugin.implementation.common.Icons.deletePluginIcon
import liveplugin.implementation.common.Icons.settingsIcon
import liveplugin.implementation.common.Icons.sharePluginIcon
import liveplugin.implementation.common.IdeUtil.livePluginActionPlace
import java.awt.GridLayout
import java.awt.event.KeyEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.tree.TreeSelectionModel

class LivePluginToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ApplicationManager.getApplication().getService(ContentFactory::class.java)
            .createContent(MySimpleToolWindowPanel(project), "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class MySimpleToolWindowPanel(val project: Project) : SimpleToolWindowPanel(true) {
    private val pluginTree: PluginTree

    init {
        val tree = MTree()
        pluginTree = PluginTree({ PluginManager.getPluginList() }, tree)
        Disposer.register(project, pluginTree)

        this.add(ScrollPaneFactory.createScrollPane(tree))
        this.toolbar = createToolBar()
    }

    private fun createToolBar(): JComponent {
        fun AnAction.with(icon: Icon) = also { it.templatePresentation.icon = icon }

        val actionGroup = DefaultActionGroup().apply {
            add(DefaultActionGroup("Add Plugin", true).apply {
                add(AddNewKotlinPluginAction())
                add(AddNewGroovyPluginAction())
                add(AddPluginFromGistAction())
                add(AddPluginFromGitHubDelegateAction())
                add(AddKotlinExamplesActionGroup())
                add(AddGroovyExamplesActionGroup())
            }.with(addPluginIcon))
            add(DeleteFiΩleAction(deletePluginIcon))
            add(RunPluginAction())
            add(UnloadPluginAction())
            add(RunPluginTestsAction())
            add(DefaultActionGroup("Share Plugin", true).apply {
                add(SharePluginAsGistDelegateAction())
                add(CreateKotlinPluginZipAction())
            }.with(sharePluginIcon))
            addSeparator()
            add(CollapseAllAction().with(collapseAllIcon))
            addSeparator()
            add(DefaultActionGroup("Settings", true).apply {
                add(RunPluginsOnIDEStartAction())
                add(RunProjectSpecificPluginsAction())
                add(AddLivePluginAndIdeJarsAsDependencies())
            }.with(settingsIcon))
            add(ShowHelpAction())
        }

        return JPanel(GridLayout()).also {
            // this is a "hack" to force drop-down box appear below button
            // (see com.intellij.openapi.actionSystem.ActionPlaces#isToolbarPlace implementation for details)
            val place = ActionPlaces.EDITOR_TOOLBAR
            val toolbar = ActionManager.getInstance().createActionToolbar(place, actionGroup, true)
            // Set target component to avoid this error:
            // 'EditorToolbar' toolbar by default uses any focused component to update its actions. Toolbar actions that need local UI context would be incorrectly disabled. Please call toolbar.setTargetComponent() explicitly. java.lang.Throwable: toolbar creation trace
            toolbar.targetComponent = it
            it.add(toolbar.component)
        }
    }

    override fun getData(dataId: String): Any? {
        return when (dataId) {
            PluginTree.DATA_KEY.name -> pluginTree
            PlatformDataKeys.NAVIGATABLE_ARRAY.name -> {
                val node = pluginTree.selectedPath()?.lastPathComponent as? MNode
                if (node != null && node.isValid && !node.file!!.isDirectory) {
                    arrayOf(OpenFileDescriptor(project, node.file!!))
                } else {
                    null
                }
            }

            else -> super.getData(dataId)
        }
    }
}


class MTree : Tree() {
    init {
        emptyText.text = "No plugins to show"
        isRootVisible = false
        selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION

        installTreeActions()
    }


    private fun installTreeActions() {
        TreeUIHelper.getInstance().installTreeSpeedSearch(this)
        TreeUtil.installActions(this)
        this.registerKeyboardAction({ performEnterAction(true) }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)

        EditSourceOnDoubleClickHandler.install(this)
        EditSourceOnEnterKeyHandler.install(this)


        fun shortcutsOf(actionId: String) = KeymapManager.getInstance().activeKeymap.getShortcuts(actionId)
        NewElementPopupAction().registerCustomShortcutSet(CustomShortcutSet(*shortcutsOf("NewElement")), this)

        val popupActionGroup = DefaultActionGroup(
            livePluginNewElementPopup,
            RunLivePluginsGroup(),
            RenameFileAction().also { it.registerCustomShortcutSet(CustomShortcutSet(*shortcutsOf("RenameElement")), this) },
            DeleteFileAction(null).also { it.registerCustomShortcutSet(CustomShortcutSet(*shortcutsOf("SafeDelete")), this) }
        ).also { it.isPopup = true }

        val popupHandler = PopupHandler.installPopupMenu(this, popupActionGroup, livePluginActionPlace)
        @Suppress("UnstableApiUsage")
        PopupMenuPreloader.install(this, livePluginActionPlace, popupHandler) { popupActionGroup }
    }

    private fun performEnterAction(toggleNodeState: Boolean) {
        val path = selectionPath
        if (path != null) {
            if (toggleNodeState) {
                if (isExpanded(path)) {
                    collapsePath(path)
                } else {
                    expandPath(path)
                }
            }
        }
    }
}
