package com.cleartether.app

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.cleartether.app.billing.BillingRepository
import com.cleartether.app.core.session.FreeTierPolicy
import com.cleartether.app.core.session.TetherSessionManager
import com.cleartether.app.data.SettingsRepository
import com.cleartether.app.data.TetherRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency graph: a handful of app-scoped singletons, no DI framework needed. */
class AppGraph(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsRepository(app)
    val runtime = TetherRuntime()
    val freeTier = FreeTierPolicy()
    val session = TetherSessionManager(settings, freeTier, elapsedClock = SystemClock::elapsedRealtime)
    val billing = BillingRepository(app, appScope, settings)

    companion object {
        fun from(context: Context): AppGraph = (context.applicationContext as ClearTetherApp).graph
    }
}
