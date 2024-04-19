package liveplugin.implementation.actions.toolwindow


import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.tree.FileRefresher
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.BaseTreeModel
import com.intellij.ui.tree.TreeVisitor
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.IconUtil.getIcon
import com.intellij.util.concurrency.Invoker
import com.intellij.util.concurrency.InvokerSupplier
import com.intellij.util.ui.tree.TreeUtil
import liveplugin.implementation.LivePlugin
import liveplugin.implementation.common.Icons.pluginIcon
import java.util.stream.Stream
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.TreePath


class PluginTree(pluginSupplier: () -> List<LivePlugin>, tree: MTree) : Disposable {
    private val myTree = tree
    private val invoker = Invoker.forBackgroundThreadWithReadAction(this)
    private var myFileTreeModel = MTreeModel(pluginSupplier, invoker, myTree, this)
    private val myAsyncTreeModel: AsyncTreeModel = AsyncTreeModel(myFileTreeModel, this)

    init {
        myTree.model = myAsyncTreeModel
        myTree.cellRenderer = MCellRenderer()
        fileChangeListener()
    }


    fun updateTree() {
        myFileTreeModel.updateTree(null)
    }

    private fun fileChangeListener() {
        ApplicationManager.getApplication().messageBus.connect().subscribe<BulkFileListener>(VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    invoker.invoke {
                        for (event in events) {
                            when (event) {
                                is VFileCreateEvent         -> updateTree(event.parent)
                                is VFileDeleteEvent         -> updateTree(event.file.parent)
                                is VFileCopyEvent           -> updateTree(event.newParent)
                                is VFileMoveEvent           -> {
                                    updateTree(event.oldParent)
                                    updateTree(event.newParent)
                                }

                                is VFilePropertyChangeEvent -> if (event.isRename) updateTree(event.file.parent)
                            }
                        }
                    }
                }
            })
    }

    fun updateTree(file: VirtualFile?) {
        if (file == null) return
        TreeUtil.promiseVisit(myTree, MFileNodeVisitor(file)).onProcessed { myFileTreeModel.updateTree(it) }
    }

    fun select(file: VirtualFile) {
        select(arrayOf(file))
    }

    fun select(file: Array<VirtualFile>) {
        when (file.size) {
            0    -> {
                myTree.clearSelection()
            }

            1    -> {
                myTree.clearSelection()
                TreeUtil.promiseSelect(myTree, MFileNodeVisitor(file[0]))
            }

            else -> {
                myTree.clearSelection()
                TreeUtil.promiseSelect(myTree, Stream.of(*file).map { MFileNodeVisitor(it) })
            }
        }
    }

    fun selectionPath(): Array<TreePath>? {
        return myTree.selectionPaths
    }

    fun selectedPath(): TreePath? {
        return myTree.selectionPath
    }


    companion object {
        val DATA_KEY: DataKey<PluginTree> = DataKey.create("PluginTree")
    }

    private class MFileNodeVisitor(target: VirtualFile) : TreeVisitor.ByComponent<VirtualFile, VirtualFile>(target, { if (it is MNode) it.file else null }) {
        override fun contains(pathFile: VirtualFile, target: VirtualFile): Boolean {
            return target.path.startsWith(pathFile.path)
        }

        override fun visit(path: TreePath): TreeVisitor.Action {
            if (path.parentPath == null) {
                return TreeVisitor.Action.CONTINUE
            }
            return super.visit(path)
        }
    }

    override fun dispose() {
    }
}


private class MTreeModel(val pluginSupplier: () -> List<LivePlugin>, private val invoker: Invoker, tree: Tree, disposable: Disposable) : BaseTreeModel<MNode>(), InvokerSupplier {
    private val refresher = FileRefresher(true, 3) { ModalityState.stateForComponent(tree) }
    private val root = Any()

    init {
        Disposer.register(disposable, refresher)
    }

    override fun getRoot(): Any {
        return root
    }

    override fun getChildren(parent: Any?): MutableList<out MNode>? {
        val children = when (parent) {
            root           -> pluginSupplier().map { MPluginNode(it.name, it.file) }.toMutableList()
            is MPluginNode -> parent.file?.children?.map { MFileNode(it, parent) }?.toMutableList()
            is MFileNode   -> parent.file.children?.map { MFileNode(it, parent) }?.toMutableList()
            else           -> null
        }
        children?.forEach { if (refresher.isRecursive) refresher.register(it.file) }
        return children
    }

    fun updateTree(path: TreePath?) {
//        val p = path ?: TreePathUtil.createTreePath(null, root)
        treeStructureChanged(path, null, null)
    }

    override fun getInvoker() = invoker

}

interface MNode {
    val name: String
    val icon: Icon
    val isValid: Boolean
    val file: VirtualFile?
    val parent: MNode?
}

class MPluginNode(override val name: String, override val file: VirtualFile?) : MNode {
    override val isValid get() = (file != null && file.isValid && file.isDirectory)
    override val parent = null
    override val icon = pluginIcon
}

class MFileNode(override val file: VirtualFile, override val parent: MNode) : MNode {
    override val icon: Icon = getIcon(file, Iconable.ICON_FLAG_READ_STATUS, null)
    override val name: String = file.name
    override val isValid get() = file.isValid
}

private class MCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
        if (value is MNode) {
            this.icon = value.icon
            var style = SimpleTextAttributes.STYLE_PLAIN
            if (!value.isValid) style = style or SimpleTextAttributes.STYLE_STRIKEOUT
            this.append(value.name, SimpleTextAttributes(style, null))
            if (value is MPluginNode && value.file != null) {
                this.append("   ${value.file!!.path}", SimpleTextAttributes.GRAY_ATTRIBUTES)
            }
        } else {
            this.append(value.toString())
        }
    }

}