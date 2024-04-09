package liveplugin.implementation.actions.toolwindow.tree

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.tree.FileRefresher
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.ui.tree.MapBasedTree
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.Invoker
import com.intellij.util.concurrency.InvokerSupplier
import com.intellij.util.ui.tree.AbstractTreeModel
import javax.swing.Icon

class PluginTreeMode(tree: Tree, pluginList: List<String>) : AbstractTreeModel(), InvokerSupplier {
    private val invoker = Invoker.forBackgroundThreadWithReadAction(this)
    private val pluginTree: PluginTree

    init {
        val refresher = FileRefresher(true, 3) { ModalityState.stateForComponent(tree) }
        pluginTree = PluginTree(refresher, pluginList)
        Disposer.register(this, refresher)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                invoker.invoke { process(events) }
            }
        })
    }

    fun invalidate() {
        invoker.invoke {
            pluginTree.invalidate()
            treeStructureChanged(null, null, null)
        }
    }

    override fun getInvoker(): Invoker {
        return invoker
    }

    override fun getRoot(): Any {
        return pluginTree.getRootEntry().node
    }

    override fun getChild(node: Any, index: Int): Any? {
        return if (node is Node) {
            pluginTree.getChildNode(node, index)
        } else null

    }

    override fun getChildCount(node: Any): Int {
        if (node is Node) {
            val entry = pluginTree.getEntry(node, true)
            if (entry != null) return entry.childCount
        }
        return 0
    }

    override fun isLeaf(node: Any): Boolean {
        if (node is Node) {
            val entry = pluginTree.getEntry(node, false)
            return entry?.isLeaf ?: false
        }
        return false
    }

    override fun getIndexOfChild(parent: Any, child: Any): Int {
        if (parent is Node && child is Node) {
            val entry = pluginTree.getEntry(parent, true)
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
                        pluginTree.getRootEntry().refresh()
                    } else {
                        event.newParent.findEntry()?.refresh()
                        event.oldParent.findEntry()?.refresh()
                    }
                }

                is VFilePropertyChangeEvent -> {
                    if (event.isRename) {
                        val entry = event.file.findEntry()
                        if (entry != null && entry.isPluginRoot()) {
                            pluginTree.getRootEntry().refresh()
                        } else {
                            event.file.parent.findEntry()?.refresh()
                        }
                    }
                }

                is VFileDeleteEvent         -> {
                    val entry = event.file.findEntry()
                    if (entry != null && entry.isPluginRoot()) {
                        pluginTree.getRootEntry().refresh()
                    } else {
                        event.file.parent.findEntry()?.refresh()
                    }
                }
            }
        }
    }

    private fun VirtualFile?.findEntry() = pluginTree.findEntry(this)

    private fun MapBasedTree.Entry<Node>.refresh() {
        val update = pluginTree.updateChildren(this)
        val removed = update.removed.isNotEmpty()
        val inserted = update.inserted.isNotEmpty()
        val contained = update.contained.isNotEmpty()
        if (!removed && !inserted && !contained) {
            return
        }
        if (!removed && inserted) {
            listeners.treeNodesInserted(update.getEvent(this@PluginTreeMode, this, update.inserted))
            return
        }
        if (!inserted && removed) {
            listeners.treeNodesRemoved(update.getEvent(this@PluginTreeMode, this, update.removed))
            return
        }
        treeStructureChanged(this, null, null)
    }

    private fun MapBasedTree.Entry<Node>.isPluginRoot(): Boolean {
        return this.parentPath == pluginTree.getRootEntry()
    }

    class Node(val file: VirtualFile?) {
        val name = file?.name
        val icon: Icon?
        val isValid = file?.isValid ?: false

        init {
            icon = file?.icon
        }

        override fun toString(): String {
            return name
        }
    }

    private class PluginTree(private val refresher: FileRefresher, pluginList: List<String>) {
        private val mbt = MapBasedTree<VirtualFile?, Node>(false) { it.file }
        private val rootNode = Node(null)

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
            val entry = mbt.getEntry(node)
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
            val children = loadChildren(parent.node) ?: return mbt.update(parent, null)
            if (children.isEmpty()) return mbt.update(parent, emptyList())
            val list = children
                .filter { it.isValid }
                .onEach { if (refresher.isRecursive) refresher.register(it) }
                .sortedWith(compareBy({ it.isDirectory }, { it.name }))
                .map { Pair.create(Node(it), !it.isDirectory) }
                .toList()
            return mbt.update(parent, list)
        }

        private fun loadChildren(node: Node): Array<VirtualFile>? {
            if (node === rootNode) {
                return emptyArray()
            } else {
                val file = node.file
                return if (file != null && file.isValid && file.isDirectory)
                    file.children
                else null
            }
        }
    }
}
