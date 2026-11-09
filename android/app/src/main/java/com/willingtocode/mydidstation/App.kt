package com.willingtocode.mydidstation

import android.app.Application
import android.content.Context
import kotlin.concurrent.thread
import okhttp3.OkHttpClient

class App : Application() {

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        // The legacy prefs cleanup that used to happen inside the Settings constructor. It
        // creates and reads no key, but it is file work, and app start is the one place where
        // file work on the main thread turns into a visible stall.
        thread(name = "legacy-settings") { container.settings.migrateLegacy() }
        // The channel has to exist before the first notification, and creating it
        // is idempotent, so app start is the simplest place to guarantee it.
        container.notifier.ensureChannel()
        SyncWorker.schedule(this)
    }
}

class Container(
    private val context: Context,
    // The two things a JVM cannot supply. Robolectric has no AndroidKeyStore provider, so the
    // real box cannot run in a test, and a real notifier posts to the system. The API needs no
    // seam: it is reached through Settings.endpoint(), which a test points at a MockWebServer.
    box: SecretBox = KeystoreBox(),
    private val notifierOverride: Notifier? = null,
) {
    // Lazy so that starting the app touches no database, no network and no Keystore.
    // A launch must survive a broken Keystore, and the tests have to be able to build a
    // container on a JVM with no AndroidKeyStore provider at all.
    val settings by lazy { Settings(context, box) }
    val store by lazy { Store(context) }
    val api by lazy { VoipMs(settings) }
    val notifier by lazy { notifierOverride ?: AndroidNotifier(context) }
    val sync by lazy { Sync(store, api, notifier) }

    /** Downloaded audio, in the cache directory the OS may empty whenever it likes. */
    val voicemailCache by lazy { VoicemailCache(context.cacheDir) }

    /**
     * Attachment downloads. One fetcher for the process, so its cache and its byte caps are
     * the same for every bubble rather than per screen.
     */
    val media by lazy { MediaFetcher(OkHttpClient(), context.cacheDir) }
}
