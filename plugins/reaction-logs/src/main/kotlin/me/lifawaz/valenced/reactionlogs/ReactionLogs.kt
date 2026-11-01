@file:JvmName("ReactionLogs")

package me.lifawaz.valenced.reactionlogs

import app.valenced.plugins.plugin
import app.valenced.xposed.api.registerNativeMethod

@Suppress("UNUSED")
val reactionLogs = plugin {
    var hooks: ReactionLogHooks? = null

    start {
        hooks = ReactionLogHooks(dexFinder)

        registerNativeMethod("lifawaz.reaction-logs.setRemoved") { args ->
            hooks?.setRemoved(args[0] as String)
            null
        }

        registerNativeMethod("lifawaz.reaction-logs.clear") {
            hooks?.clear()
            null
        }
    }

    stop {
        hooks?.dispose()
        hooks = null
    }
}