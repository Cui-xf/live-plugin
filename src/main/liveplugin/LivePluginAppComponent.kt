package liveplugin

import com.intellij.codeInsight.daemon.impl.analysis.DefaultHighlightingSettingProvider
import com.intellij.codeInsight.daemon.impl.analysis.FileHighlightingSetting
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.PathManager.getHomePath
import com.intellij.openapi.application.PathManager.getPluginsPath
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessExtension
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.Project.DIRECTORY_STORE_FOLDER
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt.toSystemIndependentName
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.IndexableSetContributor
import liveplugin.IdeUtil.askIfUserWantsToRestartIde
import liveplugin.IdeUtil.downloadFile
import liveplugin.IdeUtil.invokeLaterOnEDT
import liveplugin.LivePluginPaths.groovyExamplesPath
import liveplugin.LivePluginPaths.livePluginsPath
import liveplugin.pluginrunner.ErrorReporter
import liveplugin.pluginrunner.PluginRunner
import liveplugin.pluginrunner.createPluginRunners
import liveplugin.pluginrunner.groovy.GroovyPluginRunner
import liveplugin.pluginrunner.kotlin.KotlinPluginRunner
import liveplugin.pluginrunner.runPlugins
import liveplugin.toolwindow.util.ExamplePluginInstaller
import java.io.File
import java.io.IOException

object LivePluginPaths {
    val ideJarsPath = toSystemIndependentName(getHomePath() + "/lib")

    val livePluginPath = toSystemIndependentName(getPluginsPath() + "/LivePlugin/")
    val livePluginLibPath = toSystemIndependentName(getPluginsPath() + "/LivePlugin/lib/")
    val livePluginsCompiledPath = toSystemIndependentName(getPluginsPath() + "/live-plugins-compiled")
    @JvmField val livePluginsPath = toSystemIndependentName(getPluginsPath() + "/live-plugins")

    const val groovyExamplesPath = "/groovy/"
    const val kotlinExamplesPath = "/kotlin/"
}

class LivePluginAppComponent: DumbAware {
    init {
        checkThatGroovyIsOnClasspath()

        val settings = Settings.getInstance()
        if (settings.justInstalled) {
            installHelloWorldPlugins()
            settings.justInstalled = false
        }
        if (settings.runAllPluginsOnIDEStartup) {
            runAllPlugins()
        }
    }

    companion object {
        const val livePluginId = "LivePlugin"
        private val logger = Logger.getInstance(LivePluginAppComponent::class.java)
        private val livePluginNotificationGroup = NotificationGroup.balloonGroup("Live Plugin")

        private const val defaultIdeaOutputFolder = "out"

        fun pluginIdToPathMap(): Map<String, String> {
            val containsIdeaProjectFolder = File("$livePluginsPath/$DIRECTORY_STORE_FOLDER").exists()

            val files = File(livePluginsPath)
                .listFiles { file ->
                    file.isDirectory &&
                    file.name != DIRECTORY_STORE_FOLDER &&
                    !(containsIdeaProjectFolder && file.name == defaultIdeaOutputFolder)
                } ?: return emptyMap()

            return files.associate { Pair(it.name, toSystemIndependentName(it.absolutePath)) }
        }

        fun isInvalidPluginFolder(virtualFile: VirtualFile): Boolean {
            val scriptFiles = listOf(
                GroovyPluginRunner.mainScript,
                KotlinPluginRunner.mainScript
            )
            return scriptFiles.none { findScriptFilesIn(virtualFile.path, it).isNotEmpty() }
        }

        fun findPluginRootsFor(files: Array<VirtualFile>): Set<VirtualFile> =
            files.mapNotNull { it.pluginFolder() }.toSet()

        private fun VirtualFile.pluginFolder(): VirtualFile? {
            val parent = parent ?: return null

            val pluginsRoot = File(livePluginsPath)
            // Compare with FileUtil because string comparison was observed to not work on windows (e.g. "c:/..." and "C:/...")
            return if (!FileUtil.filesEqual(File(parent.path), pluginsRoot)) parent.pluginFolder() else this
        }

        fun defaultPluginScript(): String = readSampleScriptFile(groovyExamplesPath, "default-plugin.groovy")

        fun defaultPluginTestScript(): String = readSampleScriptFile(groovyExamplesPath, "default-plugin-test.groovy")

        fun readSampleScriptFile(pluginPath: String, file: String): String {
            return try {
                val path = pluginPath + file
                val inputStream = LivePluginAppComponent::class.java.classLoader.getResourceAsStream(path) ?: error("Could find resource for '$path'.")
                FileUtil.loadTextAndClose(inputStream)
            } catch (e: IOException) {
                logger.error(e)
                ""
            }
        }

        fun pluginExists(pluginId: String): Boolean = pluginIdToPathMap().keys.contains(pluginId)

        private val isGroovyOnClasspath: Boolean
            get() = IdeUtil.isOnClasspath("org.codehaus.groovy.runtime.DefaultGroovyMethods")

        private fun runAllPlugins() {
            invokeLaterOnEDT {
                val event = AnActionEvent(
                    null,
                    IdeUtil.dummyDataContext,
                    PluginRunner.ideStartup,
                    Presentation(),
                    ActionManager.getInstance(),
                    0
                )
                val errorReporter = ErrorReporter()
                val pluginPaths = pluginIdToPathMap().keys.map { pluginIdToPathMap().getValue(it) }
                runPlugins(pluginPaths, event, errorReporter, createPluginRunners(errorReporter))
            }
        }

        fun checkThatGroovyIsOnClasspath(): Boolean {
            if (isGroovyOnClasspath) return true

            // This can be useful for non-java IDEs because they don't have bundled groovy libs.
            val listener = NotificationListener { notification, _ ->
                val groovyVersion = "2.4.17" // Version of groovy used by latest IJ.
                val downloaded = downloadFile(
                    "http://repo1.maven.org/maven2/org/codehaus/groovy/groovy-all/$groovyVersion/",
                    "groovy-all-$groovyVersion.jar",
                    LivePluginPaths.livePluginLibPath
                )
                if (downloaded) {
                    notification.expire()
                    askIfUserWantsToRestartIde("For Groovy libraries to be loaded IDE restart is required. Restart now?")
                } else {
                    livePluginNotificationGroup
                        .createNotification("Failed to download Groovy libraries", NotificationType.WARNING)
                }
            }
            livePluginNotificationGroup.createNotification(
                "LivePlugin didn't find Groovy libraries on classpath",
                "Without it plugins won't work. <a href=\"\">Download Groovy libraries</a> (~7Mb)",
                NotificationType.ERROR,
                listener
            ).notify(null)

            return false
        }

        private fun installHelloWorldPlugins() {
            val loggingListener = object: ExamplePluginInstaller.Listener {
                override fun onException(e: Exception, pluginPath: String) = logger.warn("Failed to install plugin: $pluginPath", e)
            }
            ExamplePluginInstaller(groovyExamplesPath + "hello-world/", listOf("plugin.groovy")).installPlugin(loggingListener)
            ExamplePluginInstaller(groovyExamplesPath + "ide-actions/", listOf("plugin.groovy")).installPlugin(loggingListener)
            ExamplePluginInstaller(groovyExamplesPath + "insert-new-line-above/", listOf("plugin.groovy")).installPlugin(loggingListener)
            ExamplePluginInstaller(groovyExamplesPath + "popup-menu/", listOf("plugin.groovy")).installPlugin(loggingListener)
        }
    }
}

class LivePluginIndexableSetContributor: IndexableSetContributor() {
    override fun getAdditionalRootsToIndex(): MutableSet<VirtualFile> {
        val path = livePluginsPath.findFileByUrl() ?: return HashSet()
        return mutableSetOf(path)
    }
}

class EnableHighlightingForLivePlugins: DefaultHighlightingSettingProvider(), DumbAware {
    override fun getDefaultSetting(project: Project, file: VirtualFile): FileHighlightingSetting? {
        val isUnderPluginsRootPath = FileUtil.startsWith(file.path, livePluginsPath)
        return if (isUnderPluginsRootPath) FileHighlightingSetting.FORCE_HIGHLIGHTING else null
    }
}

class MakePluginFilesAlwaysEditable: NonProjectFileWritingAccessExtension {
    override fun isWritable(file: VirtualFile) = FileUtil.startsWith(file.path, livePluginsPath)
}
