import com.intellij.openapi.components.*
import liveplugin.implementation.LivePluginPaths.livePluginsPath
import liveplugin.implementation.common.FilePath

@Service
@State(name = "PluginList", storages = [Storage(value = "/Users/cxf/Downloads/aaa/plugin.xml")])
class PluginManagementServices : SimplePersistentStateComponent<MyState>(MyState()) {
    companion object {
        val state: MyState by lazy { service<PluginManagementServices>().state }
        val pluginPathList: Set<String> get() = state.value + livePluginsPath.listFiles { it.isDirectory }.map { it.value }
    }
}

fun FilePath.isCustomDirPlugin() = PluginManagementServices.state.value.contains(this.value)

class MyState : BaseState() {
    var value by stringSet()
}