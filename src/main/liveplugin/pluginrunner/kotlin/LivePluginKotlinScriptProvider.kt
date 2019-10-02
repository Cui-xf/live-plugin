package liveplugin.pluginrunner.kotlin

import liveplugin.LivePluginPaths
import java.io.File
import kotlin.script.experimental.intellij.ScriptDefinitionsProvider

class LivePluginKotlinScriptProvider: ScriptDefinitionsProvider {
    override val id = "LivePluginKotlinScriptProvider"
    override fun getDefinitionClasses() = listOf(KotlinScriptTemplate::class.java.canonicalName)
    override fun getDefinitionsClassPath() = File(LivePluginPaths.livePluginLibPath).listFiles()?.toList() ?: emptyList()
    override fun useDiscovery() = false
}

