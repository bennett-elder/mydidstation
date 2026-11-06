package com.willingtocode.mydidstation

import android.app.Application
import android.content.Context

class App : Application() {

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        // The channel has to exist before the first notification, and creating it
        // is idempotent, so app start is the simplest place to guarantee it.
        container.notifier.ensureChannel()
        SyncWorker.schedule(this)
    }
}

class Container(context: Context) {
    val settings = Settings(context)
    val store = Store(context)
    val api = VoipMs(settings)
    val notifier = AndroidNotifier(context)
    val sync = Sync(store, api, notifier)
}
