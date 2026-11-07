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

class Container(private val context: Context) {
    // Lazy so that starting the app touches no database, no network and no Keystore.
    // A launch must survive a broken Keystore, and the tests have to be able to build a
    // container on a JVM with no AndroidKeyStore provider at all.
    val settings by lazy { Settings(context) }
    val store by lazy { Store(context) }
    val api by lazy { VoipMs(settings) }
    val notifier by lazy { AndroidNotifier(context) }
    val sync by lazy { Sync(store, api, notifier) }
}
