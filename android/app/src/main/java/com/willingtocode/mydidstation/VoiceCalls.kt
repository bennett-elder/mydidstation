package com.willingtocode.mydidstation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp


/**
 * The calls tab.
 *
 * The gate in front of it is not a screen choice: without credentials there is no account to
 * call from, and without a chosen number there is nothing to present a call with. The voice app
 * handled both inside its own shell; here those two pages are hosted inside the app's chrome,
 * which is why there is no second bottom bar underneath them.
 */
@Composable
internal fun VoiceCallsTab(
    voice: Voice,
    showLines: Boolean,
    onShowLines: (Boolean) -> Unit,
) {
    var hasCredentials by remember { mutableStateOf(voice.apiSettings.credentials() != null) }
    var confirmed by remember { mutableStateOf(voice.apiSettings.numbersConfirmed) }
    var dialNumber by remember { mutableStateOf(voice.apiSettings.lastNumber) }
    var dialDid by remember { mutableStateOf<String?>(null) }
    var dialOpen by remember { mutableStateOf(false) }

    val call by voice.callState.collectAsState()
    val starting by voice.starting.collectAsState()
    val fetching by voice.fetching.collectAsState()
    val readiness by voice.readiness.collectAsState()
    val provision by voice.provision.collectAsState()
    val dids by voice.dids.collectAsState()

    // The saved numbers draw first and registration follows them, so a launch shows what it
    // already knows instead of waiting on the provider.
    LaunchedEffect(hasCredentials) { if (hasCredentials) voice.startUp() }

    // The line settings are a page of this tab, so Back returns to the call list rather than
    // leaving the app. The app's other stacks behave the same way.
    BackHandler(enabled = showLines) { onShowLines(false) }

    // Setup just finished, so a call can arrive from now on. Ask for the microphone here as
    // well as at launch: the launch ask cannot happen before numbers have been chosen.
    MicrophonePermission(enabled = confirmed)

    // No Scaffold here. The app's own Scaffold already reserves the room for the bottom bar
    // and the system bars, and a second one inside it applied the navigation-bar inset again:
    // that was the white strip under this list with nothing in it. Each page below now keeps
    // its own end padding inside its scroll, so the padding scrolls away with the content
    // instead of cropping it.
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar {
                if (hasCredentials && confirmed) {
                    TextButton(onClick = { onShowLines(!showLines) }) {
                        Text(if (showLines) "Done" else "Lines")
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !hasCredentials -> ConnectPage(voice) { hasCredentials = true }
                    showLines -> SettingsPage(
                        pathfinder = voice,
                        onSignOut = { hasCredentials = false; onShowLines(false) },
                        onReset = { confirmed = false; onShowLines(false) },
                    )
                    !confirmed -> OnboardingScreen(voice) { confirmed = true }
                    else -> HistoryPage(
                        pathfinder = voice,
                        onUseInDialer = { dial, numbers ->
                            // A tap on history is not consent to call, so nothing is dialled.
                            dialNumber = dial
                            dialDid = voice.didForCall(numbers)
                            dialOpen = true
                        },
                    )
                }
            }
        }
        // Only over the list. On the lines page it covered the controls at the end of the
        // settings, which is the one place a floating button has no business.
        if (hasCredentials && confirmed && !showLines) {
            FloatingActionButton(
                onClick = { dialOpen = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                Icon(painterResource(R.drawable.ic_dialpad), contentDescription = "Dial")
            }
        }
    }

    if (dialOpen && hasCredentials && confirmed) {
        DialDialog(
            pathfinder = voice,
            number = dialNumber,
            onNumber = { dialNumber = it },
            fromDid = dialDid,
            onFromDid = { dialDid = it },
            status = dialStatus(readiness, call, starting),
            onDismiss = { dialOpen = false },
        )
    }

    if (hasCredentials && confirmed && (starting || (fetching && dids.isEmpty()))) {
        LoadingDialog(
            title = if (starting) "Setting up your phone…" else "Reading your account…",
            detail = (provision as? ProvisionState.Working)?.step
                ?: if (readiness.registered > 0) "Registered, coming up."
                else "This takes a few seconds.",
        )
    }
}

/**
 * A ringing or connected call, above whatever screen is showing.
 *
 * The voice app drew these inside its own shell, which worked because that shell was the whole
 * screen. Here the app owns the bottom bar, so a call has to be able to cover it: a call screen
 * confined to one tab would leave a navigation bar underneath a call it cannot hang up.
 */
@Composable
internal fun VoiceCallOverlay(voice: Voice) {
    val call by voice.callState.collectAsState()
    val ringing by voice.incoming.collectAsState()
    val muted by voice.muted.collectAsState()
    val stats by voice.stats.collectAsState()
    val ring = ringing
    val live = call
    val shown = when (live) {
        is PathState.Dialing -> live.number
        is PathState.Ringing -> live.number
        is PathState.Connected -> live.number
        else -> ""
    }
    // Each branch brings its own opaque surface. The standalone shell used to supply one for
    // the whole screen; without it the call drew its text straight over whichever tab was
    // underneath. A Surface also gives the screen a background that follows dark mode.
    when {
        ring != null -> Surface(Modifier.fillMaxSize()) {
            IncomingScreen(
                call = ring,
                onAnswer = { voice.answerIncoming() },
                onDecline = { voice.declineIncoming() },
            )
        }
        live is PathState.Dialing || live is PathState.Ringing || live is PathState.Connected ->
            Surface(Modifier.fillMaxSize()) {
                CallScreen(
                    number = shown,
                    state = live,
                    muted = muted,
                    stats = stats,
                    onMuted = { voice.setMuted(it) },
                    onSpeaker = { voice.setSpeaker(it) },
                    onHangUp = { voice.hangUp() },
                    onKey = { voice.sendDtmf(it) },
                )
            }
    }
}
