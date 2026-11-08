package com.willingtocode.mydidstation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    /**
     * A conversation a notification asked for. Held as Compose state so a tap
     * while the activity is already running navigates too, not only a cold start.
     */
    private var target by mutableStateOf<Target?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        target = targetFrom(intent)
        setContent {
            MyDidStationTheme {
                Root(container, open = target, onConsumed = { target = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        target = targetFrom(intent)
    }

    private fun targetFrom(intent: Intent?): Target? {
        // A voicemail notification carries a mailbox and nothing else, and the voicemail
        // tab is its own stack, so it is a separate shape rather than a did with no contact.
        val mailbox = intent?.getStringExtra(EXTRA_MAILBOX)?.takeIf { it.isNotBlank() }
        if (mailbox != null) return Target("", "", mailbox)
        val did = intent?.getStringExtra(EXTRA_DID)?.takeIf { it.isNotBlank() } ?: return null
        val contact = intent.getStringExtra(EXTRA_CONTACT)?.takeIf { it.isNotBlank() } ?: return null
        return Target(did, contact)
    }

    companion object {
        const val EXTRA_DID = "did"
        const val EXTRA_CONTACT = "contact"
        const val EXTRA_MAILBOX = "mailbox"
    }
}
