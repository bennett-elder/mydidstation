package com.willingtocode.mydidstation

import android.app.Application
import android.content.Context

class App : Application() {

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        SyncWorker.schedule(this)
    }
}

class Container(context: Context) {
    val settings = Settings(context)
    val store = Store(context)
    val api = VoipMs(settings)
    val sync = Sync(store, api)
}
