import liveplugin.show

//
// To run a plugin press "Run plugin" button in LivePlugin toolwindow or use "alt+C, alt+E" shortcut.
// This will run plugin currently open in editor or plugin selected in the toolwindow.
//
// You might notice that it takes few seconds the first time you run a Kotlin plugin.
// However, it should get faster on the following runs.
//

// The code below will show balloon message with "Hello world" text (it will also appear in IDE "Event Log" toolwindow).
// (If there is no balloon, it might be disabled in "IDE Settings - Notifications".)
show("Hello kotlin world")

// "isIdeStartup" - true on IDE startup, otherwise false. Plugins are executed on IDE startup
//                  if "Plugins toolwindow -> Settings -> Run all plugins on IDE start" option is enabled.
show("isIdeStartup: $isIdeStartup")

// There are several implicit variables available in plugin files.
// "project" - project in which plugin is executed, can be null on IDE startup or if no projects are open.
show("project: $project")

// "pluginPath" - absolute path to this plugin folder.
show("pluginPath: $pluginPath")

// "pluginDisposable" - instance of com.intellij.openapi.Disposable which is disposed before plugin is run again.
// Can be useful for cleanup, e.g. un-registering IDE listeners.
show("pluginDisposable: $pluginDisposable")

//
// See next ide-actions example.
//          ^^^^^^^^^^^
