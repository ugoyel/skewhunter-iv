package com.cleartether.app

import android.app.Application
import com.cleartether.app.service.Notifications

class ClearTetherApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.createChannels(this)
        graph.billing.connect()
    }
}
