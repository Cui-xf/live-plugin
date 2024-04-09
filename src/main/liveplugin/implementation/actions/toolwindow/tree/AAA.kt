package liveplugin.implementation.actions.toolwindow.tree

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.fileChooser.FileSystemTree
import com.intellij.openapi.fileChooser.tree.FileNodeVisitor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Color
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import kotlin.streams.asStream

open class PluginTree(tree: Tree) : FileSystemTree {
    private val myTree = tree
    private val myFileTreeModel = PluginTreeMode(tree, listOf())
    private val myAsyncTreeModel = AsyncTreeModel(myFileTreeModel, this)

    init {
        myTree.model = myAsyncTreeModel
        myTree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        //todo
        myTree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                var name: String? = null
                if (value is PluginTreeMode.Node) {
                    this.icon = value.icon
                    name = value.name
                    var style = SimpleTextAttributes.STYLE_PLAIN
                    if (!value.isValid) {
                        style = style or SimpleTextAttributes.STYLE_STRIKEOUT
                    }
                    val attributes = SimpleTextAttributes(style, color)
                    if (name != null) this.append(name, attributes)
                }
            }

        }

        TreeUIHelper.getInstance().installTreeSpeedSearch(myTree)
        TreeUtil.installActions(myTree)
        registerTreeActions()
    }


    private fun registerTreeActions() {
        myTree.registerKeyboardAction(
            { performEnterAction(true) },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED
        )
        object : DoubleClickListener() {
            override fun onDoubleClick(e: MouseEvent): Boolean {
                performEnterAction(false)
                return true
            }
        }.installOn(myTree)
    }

    private fun performEnterAction(toggleNodeState: Boolean) {
        val path = myTree.selectionPath
        if (path != null) {
            if (toggleNodeState) {
                if (myTree.isExpanded(path)) {
                    myTree.collapsePath(path)
                } else {
                    myTree.expandPath(path)
                }
            }
        }
    }


    override fun areHiddensShown(): Boolean {
        return false
    }

    override fun showHiddens(showHidden: Boolean) {}

    override fun updateTree() {
        myFileTreeModel.invalidate()
    }

    override fun dispose() {
    }

    override fun select(file: VirtualFile, onDone: Runnable?) {
        select(arrayOf(file), onDone)
    }

    override fun select(file: Array<VirtualFile>, onDone: Runnable?) {
        when (file.size) {
            0    -> {
                myTree.clearSelection()
                onDone?.run()
            }

            1    -> {
                myTree.clearSelection()
                TreeUtil.promiseSelect(myTree, FileNodeVisitor(file[0])).onProcessed { onDone?.run() }
            }

            else -> {
                myTree.clearSelection()
                TreeUtil.promiseSelect(myTree, file.asSequence().map { FileNodeVisitor(it) }.asStream())
                    .onProcessed { onDone?.run() }
            }
        }
    }

    override fun expand(file: VirtualFile, onDone: Runnable?) {
        TreeUtil.promiseExpand(myTree, FileNodeVisitor(file))
            .onSuccess { if (it != null && onDone != null) onDone.run() }
    }


    override fun getTree(): JTree {
        return myTree
    }


    override fun getNewFileParent(): VirtualFile? {
        return selectedFile
    }

    override fun getSelectedFile(): VirtualFile? {
        val path = myTree.selectionPath ?: return null
        return getVirtualFile(path)
    }

    override fun getSelectedFiles(): Array<VirtualFile> {
        val paths = myTree.selectionPaths ?: return VirtualFile.EMPTY_ARRAY
        return paths
            .mapNotNull {
                getVirtualFile(it)
            }
            .filter { it.isValid }
            .toTypedArray()
    }

    override fun selectionExists(): Boolean {
        return selectedFiles.isNotEmpty()
    }

    override fun isUnderRoots(file: VirtualFile): Boolean {
        throw UnsupportedOperationException()
    }

    override fun addListener(listener: FileSystemTree.Listener, parent: Disposable) {
        throw UnsupportedOperationException()
    }

    override fun <T> getData(key: DataKey<T>): T? {
        throw UnsupportedOperationException()
    }

    private fun getVirtualFile(path: TreePath): VirtualFile? {
        val component = path.lastPathComponent
        if (component is PluginTreeMode.Node) {
            return component.file
        }
        return null
    }
}