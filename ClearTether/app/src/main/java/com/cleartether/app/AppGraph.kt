package com.cleartether.app

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.cleartether.app.core.session.TetherSessionManager
import com.cleartether.app.data.SettingsRepository
import com.cleartether.app.data.TetherRuntime

/** Manual dependency graph: a handful of app-scoped singletons, no DI framework needed. */
class AppGraph(app: Application) {
    val settings = SettingsRepository(app)
    val runtime = TetherRuntime()
    val session = TetherSessionManager(elapsedClock = SystemClock::elapsedRealtime)

    companion object {
        fun from(context: Context): AppGraph = (context.applicationContext as ClearTetherApp).graph
    }
}
