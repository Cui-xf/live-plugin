package liveplugin.implementation.actions.toolwindow.tree

import com.intellij.icons.AllIcons.Nodes.ErrorMark
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.tree.FileRefresher
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.ui.LayeredIcon
import com.intellij.ui.tree.MapBasedTree
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.IconUtil.getIcon
import com.intellij.util.PlatformIcons
import com.intellij.util.concurrency.Invoker
import com.intellij.util.concurrency.InvokerSupplier
import com.intellij.util.ui.tree.AbstractTreeModel
import liveplugin.implementation.LivePlugin
import liveplugin.implementation.common.Icons.pluginIcon
import liveplugin.implementation.common.IdeUtil.runLaterOnEdt
import javax.swing.Icon

class PluginTreeMode(tree: Tree, pluginProvider: () -> List<LivePlugin>) : AbstractTreeModel(), InvokerSupplier {
    private val invoker = Invoker.forBackgroundThreadWithReadAction(this)
    private val treeModel: TreeModel

    init {
        val refresher = FileRefresher(true, 3) { ModalityState.stateForComponent(tree) }
        treeModel = TreeModel(refresher, pluginProvider)
        Disposer.register(this, refresher)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                invoker.invoke { process(events) }
            }
        })
    }

    fun invalidate() {
        invoker.invoke {
            treeModel.invalidate()
            treeStructureChanged(null, null, null)
        }
    }

    override fun getInvoker(): Invoker {
        return invoker
    }

    override fun getRoot(): Any {
        return treeModel.getRootEntry().node
    }

    override fun getChild(node: Any, index: Int): Any? {
        return if (node is Node) {
            treeModel.getChildNode(node, index)
        } else null

    }

    override fun getChildCount(node: Any): Int {
        if (node is Node) {
            val entry = treeModel.getEntry(node, true)
            if (entry != null) return entry.childCount
        }
        return 0
    }

    override fun isLeaf(node: Any): Boolean {
        if (node is Node) {
            val entry = treeModel.getEntry(node, false)
            return entry?.isLeaf ?: false
        }
        return false
    }

    override fun getIndexOfChild(parent: Any, child: Any): Int {
        if (parent is Node && child is Node) {
            val entry = treeModel.getEntry(parent, true)
            if (entry != null) return entry.getIndexOf(child)
        }
        return -1
    }

    private fun process(events: List<VFileEvent>) {
        for (event in events) {
            when (event) {
                is VFileCreateEvent         -> event.parent.findEntry()?.refresh()
                is VFileCopyEvent           -> event.newParent.findEntry()?.refresh()
                is VFileMoveEvent           -> {
                    val entry = event.file.findEntry()
                    if (entry != null && entry.isPluginRoot()) {
                        treeModel.getRootEntry().refresh()
                    } else {
                        event.newParent.findEntry()?.refresh()
                        event.oldParent.findEntry()?.refresh()
                    }
                }

                is VFilePropertyChangeEvent -> {
                    if (event.isRename) {
                        val entry = event.file.findEntry()
                        if (entry != null && entry.isPluginRoot()) {
                            treeModel.getRootEntry().refresh()
                        } else {
                            event.file.parent.findEntry()?.refresh()
                        }
                    }
                }

                is VFileDeleteEvent         -> {
                    val entry = event.file.findEntry()
                    if (entry != null && entry.isPluginRoot()) {
                        treeModel.getRootEntry().refresh()
                    } else {
                        event.file.parent.findEntry()?.refresh()
                    }
                }
            }
        }
    }

    private fun VirtualFile?.findEntry() = treeModel.findEntry(this)

    private fun MapBasedTree.Entry<Node>.refresh() {
        val update = treeModel.updateChildren(this)
        val removed = update.removed.isNotEmpty()
        val inserted = update.inserted.isNotEmpty()
        val contained = update.contained.isNotEmpty()
        if (!removed && !inserted && !contained) {
            return
        }
        if (!removed && inserted) {
            runLaterOnEdt {
                listeners.treeNodesInserted(update.getEvent(this@PluginTreeMode, this, update.inserted))
            }
            return
        }
        if (!inserted && removed) {
            runLaterOnEdt {
                listeners.treeNodesRemoved(update.getEvent(this@PluginTreeMode, this, update.removed))
            }
            return
        }
        runLaterOnEdt {
            treeStructureChanged(this, null, null)
        }
    }

    private fun MapBasedTree.Entry<Node>.isPluginRoot(): Boolean {
        return this.node.isPluginRoot
    }

    class Node(val name: String?, val file: VirtualFile?, val isPluginRoot: Boolean) {
        val isValid: Boolean
            get() {
                if (file == null) return false
                if (!file.isValid) return false
                return file.isDirectory || !isPluginRoot
            }
        val icon: Icon?
            get() {
                if (file == null) return null
                if (isPluginRoot) return pluginIcon
                if (!isValid) return ErrorMark
                val baseIcon = getIcon(file, Iconable.ICON_FLAG_READ_STATUS, null)
                return if (file.`is`(VFileProperty.SYMLINK)) {
                    LayeredIcon.layeredIcon(arrayOf(baseIcon, PlatformIcons.SYMLINK_ICON))
                } else {
                    baseIcon
                }
            }

        override fun toString(): String {
            return name ?: ""
        }
    }

    private class TreeModel(private val refresher: FileRefresher, val pluginProvider: () -> List<LivePlugin>) {
        private val mbt = MapBasedTree<VirtualFile?, Node>(false) { it.file }
        private val rootNode = Node("Root", null, false)

        init {
            mbt.updateRoot(Pair.create(rootNode, false))
        }

        fun invalidate() {
            mbt.invalidate()
        }

        fun getRootEntry(): MapBasedTree.Entry<Node> {
            return mbt.rootEntry
        }

        fun findEntry(file: VirtualFile?): MapBasedTree.Entry<Node>? {
            return mbt.findEntry(file)
        }

        fun getEntry(node: Node, loadChildren: Boolean): MapBasedTree.Entry<Node>? {
            val entry = if (node == rootNode) getRootEntry() else mbt.getEntry(node)
            return entry?.also {
                if (loadChildren && it.isLoadingRequired) {
                    updateChildren(it)
                }
            }
        }

        fun getChildNode(parent: Node, index: Int): Node? {
            val entry = getEntry(parent, true)
            return entry?.getChild(index)
        }

        fun updateChildren(parent: MapBasedTree.Entry<Node>): MapBasedTree.UpdateResult<Node> {
            val children = loadChildren(parent) ?: return mbt.update(parent, null)
            val list = children
                .filter { it.file == null || it.file.isValid }
                .onEach { if (refresher.isRecursive && it.file != null) refresher.register(it.file) }
                .sortedWith(compareBy({ it.file?.isDirectory }, { it.name }))
                .map { Pair.create(it, it.file != null && !it.file.isDirectory) }
                .toList()
            mbt.update(parent, null)
            return mbt.update(parent, list)
        }


        private fun loadChildren(parent: MapBasedTree.Entry<Node>): List<Node>? {
            if (parent.node == rootNode) {
                return pluginProvider().map { Node(it.id, it.path.toVirtualFile(), true) }.toList()
            } else {
                val file = parent.node.file
                if (file == null || !file.isValid || !file.isDirectory) {
                    return null
                }
                val children = file.children ?: return null
                return children.map { Node(it.name, it, false) }.toList()
            }
        }
    }
}
