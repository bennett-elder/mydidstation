package com.willingtocode.mydidstation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat as AndroidDateFormat
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone

/** A pending row older than this is assumed to have never been sent. */
private const val STALE_PENDING_SECONDS = 15L * 60

/**
 * How far a pull at the end of a thread has to travel before it counts as a refresh.
 *
 * Roughly two finger-widths: far enough not to fire while flicking to the bottom, close
 * enough that it feels like a gesture rather than a hold.
 */
private const val PULL_UP_TO_REFRESH = 96

/** "12:34" style countdown to the next background poll. */
private fun countdownText(nextAt: Long?, now: Long): String? {
    if (nextAt == null) return null
    val left = nextAt - now
    if (left <= 0) return "due now"
    val secs = left / 1000
    return if (secs >= 3600) {
        String.format(Locale.US, "%dh %02dm", secs / 3600, (secs % 3600) / 60)
    } else {
        String.format(Locale.US, "%d:%02d", secs / 60, secs % 60)
    }
}

private fun durationText(millis: Long): String = String.format(Locale.US, "%.1f s", millis / 1000.0)

/**
 * The status strip, kept at the top of a list so it stays visible however many
 * rows there are. It owns its one-second tick, so only this line recomposes.
 */
@Composable
private fun SyncStatus(container: Container, summary: String?) {
    val context = LocalContext.current
    var nextAt by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastProbe by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            val tick = System.currentTimeMillis()
            now = tick
            val current = nextAt
            val stale = current == null || tick >= current
            // Ask again once the countdown runs out, but no more than every few
            // seconds, so an unknown schedule does not mean a query per second.
            if (stale && tick - lastProbe > 5_000) {
                lastProbe = tick
                nextAt = withContext(Dispatchers.IO) {
                    SyncWorker.nextRunMillis(context, container.settings.lastPollAt())
                }
            }
            delay(1000)
        }
    }

    val countdown = countdownText(nextAt, now)
    val text = when {
        summary != null && countdown != null -> "$summary  ·  next in $countdown"
        summary != null -> summary
        countdown != null -> "next refresh in $countdown"
        else -> null
    }
    if (text != null) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * Whether this screen is actually in front of the user. A foreground poll must
 * stop when the app goes to the background, and Compose keeps the composition
 * alive then, so lifecycle state is the only reliable signal.
 */
@Composable
private fun isResumed(): Boolean {
    val owner = LocalLifecycleOwner.current
    var resumed by remember {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

@Composable
fun MyDidStationTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

/** Where the Texts tab is. It is the only stack with depth so far. */
sealed interface Screen {
    data object Dids : Screen
    data class Convs(val did: String) : Screen
    data class Msgs(val did: String, val convId: Long, val contact: String) : Screen
}

/** The app's top-level places. Each keeps its own stack, so Back stays inside a tab. */
enum class Tab(val label: String) {
    Texts("Texts"),
    Voicemail("Voicemail"),
    Settings("Settings"),
}

/** Where the Voicemail tab is. Mailboxes first, then one mailbox's recordings. */
sealed interface VmScreen {
    data object Mailboxes : VmScreen
    data class Messages(val mailbox: String, val name: String) : VmScreen
}

/** A conversation a notification wants opened, as read from the intent. */
data class Target(val did: String, val contact: String)

@Composable
fun Root(container: Container, open: Target? = null, onConsumed: () -> Unit = {}) {
    var tab by remember { mutableStateOf(Tab.Texts) }
    // The Voicemail tab's own stack, so Back inside it returns to the mailbox list
    // instead of leaving the tab.
    var vm by remember { mutableStateOf<VmScreen>(VmScreen.Mailboxes) }
    // A notification tap starts at the thread it is about, rather than flashing the
    // numbers list on the way.
    var screen by remember {
        mutableStateOf<Screen>(
            open?.let { Screen.Msgs(it.did, 0L, it.contact) } ?: Screen.Dids,
        )
    }

    // The thread lives in the Texts stack, so the tab has to be selected as well or it
    // would open behind whichever tab was showing.
    LaunchedEffect(open) {
        if (open != null) {
            tab = Tab.Texts
            screen = Screen.Msgs(open.did, 0L, open.contact)
            onConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Texts,
                    onClick = { tab = Tab.Texts },
                    icon = { Icon(Icons.Filled.Email, contentDescription = null) },
                    label = { Text(Tab.Texts.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.Voicemail,
                    onClick = { tab = Tab.Voicemail },
                    icon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                    label = { Text(Tab.Voicemail.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.Settings,
                    onClick = { tab = Tab.Settings },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(Tab.Settings.label) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Texts -> when (val current = screen) {
                    Screen.Dids -> {
                        BackHandler(enabled = false) {}
                        DidListScreen(
                            container = container,
                            onOpen = { screen = Screen.Convs(it) },
                        )
                    }

                    is Screen.Convs -> {
                        BackHandler { screen = Screen.Dids }
                        ConversationsScreen(
                            container = container,
                            did = current.did,
                            onBack = { screen = Screen.Dids },
                            onOpen = { convId, contact ->
                                screen = Screen.Msgs(current.did, convId, contact)
                            },
                            onNew = { screen = Screen.Msgs(current.did, 0L, "") },
                        )
                    }

                    is Screen.Msgs -> {
                        // Keyed, because a notification tap can go from one thread straight
                        // to another, and the screen would otherwise keep the previous
                        // conversation's remembered state and show the wrong messages.
                        key(current.did, current.convId, current.contact) {
                            BackHandler { screen = Screen.Convs(current.did) }
                            MessagesScreen(
                                container = container,
                                did = current.did,
                                conversationId = current.convId,
                                contact = current.contact,
                                onBack = { screen = Screen.Convs(current.did) },
                            )
                        }
                    }
                }

                Tab.Voicemail -> when (val current = vm) {
                    VmScreen.Mailboxes -> VoicemailScreen(
                        container = container,
                        onOpen = { mailbox, name -> vm = VmScreen.Messages(mailbox, name) },
                    )

                    is VmScreen.Messages -> {
                        BackHandler { vm = VmScreen.Mailboxes }
                        MailboxScreen(
                            container = container,
                            mailbox = current.mailbox,
                            name = current.name,
                            onBack = { vm = VmScreen.Mailboxes },
                        )
                    }
                }

                Tab.Settings -> SettingsScreen(container, onDone = { tab = Tab.Texts })
            }
        }
    }
}

/**
 * Mailboxes, not numbers. A box can be fed by several numbers or by none, so this list
 * comes from getVoicemails and never from the DID list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicemailScreen(container: Container, onOpen: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<MailboxRow>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun loadCached() {
        rows = withContext(Dispatchers.IO) { container.store.mailboxes() }
        loaded = true
    }

    suspend fun refresh() {
        busy = true
        status = null
        try {
            container.sync.refreshMailboxes()
        } catch (e: Exception) {
            status = e.message ?: "could not reach VoIP.ms"
        }
        rows = withContext(Dispatchers.IO) { container.store.mailboxes() }
        loaded = true
        busy = false
    }

    // Cached rows first, network after. The mailbox list is one request per screen open,
    // and waiting for it before drawing made a screen the app already has the data for
    // feel like it was starting from nothing every time.
    LaunchedEffect(Unit) {
        loadCached()
        refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voicemail") },
                actions = {
                    TextButton(onClick = { scope.launch { refresh() } }, enabled = !busy) {
                        Text(if (busy) "Checking…" else "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            status?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (loaded && rows.isEmpty()) {
                Text(
                    "No mailboxes yet. They appear once the account reports them.",
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.mailbox }) { row ->
                    MailboxRowItem(row, onClick = { onOpen(row.mailbox, row.name) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MailboxRowItem(row: MailboxRow, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name.ifBlank { "Mailbox ${row.mailbox}" },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (row.newCount > 0) {
                Text(
                    if (row.newCount == 1) "1 new" else "${row.newCount} new",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Text(
            buildString {
                append("Mailbox ")
                append(row.mailbox)
                // Numbers, plural: a box is fed by however many are routed to it, and zero
                // is a normal state rather than a bug.
                when (row.dids) {
                    0 -> append(" · no number routed")
                    1 -> append(" · 1 number")
                    else -> append(" · ${row.dids} numbers")
                }
                if (row.transcription) append(" · transcriptions on")
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * A download arrow: shaft, head and a floor line.
 *
 * Drawn here rather than pulled from `material-icons-extended`, which is a large dependency
 * for one glyph, and rather than copying Material's path data into an MIT repository. The
 * fill is black and the tint comes from [Icon], which is how the theme decides its color.
 */
private val DownloadGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "Download",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // The shaft.
            moveTo(10.6f, 3f)
            lineTo(13.4f, 3f)
            lineTo(13.4f, 12f)
            lineTo(10.6f, 12f)
            close()
            // The head.
            moveTo(6.8f, 10.4f)
            lineTo(12f, 16.4f)
            lineTo(17.2f, 10.4f)
            close()
            // The floor the arrow lands on.
            moveTo(5f, 18.6f)
            lineTo(19f, 18.6f)
            lineTo(19f, 21f)
            lineTo(5f, 21f)
            close()
        }
    }.build()
}

/**
 * One mailbox's recordings.
 *
 * Tapping a recording downloads it into the cache and plays it. Export and the
 * mark-listened write come next; this makes the mailbox audible, which is the whole point
 * of the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxScreen(container: Container, mailbox: String, name: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<VoiceMailRow>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    // "folder/number" rather than the number alone: the API moves a message between
    // folders when it is marked listened, so the number is not an identity on its own.
    var playing by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf(LookBack(days = 0, more = false)) }
    var paused by remember { mutableStateOf(false) }
    var playingLabel by remember { mutableStateOf("") }
    // Which recordings are already on the device. Derived from the cache rather than stored,
    // so a file the OS evicted stops claiming to be here without anything having to notice.
    var downloaded by remember { mutableStateOf<Set<String>>(emptySet()) }
    // A recording that will not play is that recording's problem, not the screen's, so the
    // failure is kept against its own row instead of being shown at the top of the list.
    var failures by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val player = remember { VoicemailPlayer() }

    // Leaving the screen has to stop the audio. Nothing else owns the player, so without
    // this a recording keeps playing over the mailbox list.
    DisposableEffect(Unit) { onDispose { player.stop() } }

    /**
     * Works out which recordings are already on the device.
     *
     * The cache is the OS's to empty, so this is recomputed rather than remembered
     * permanently: a file evicted between two visits stops showing as downloaded.
     */
    suspend fun noteDownloaded(source: List<VoiceMailRow>) {
        downloaded = withContext(Dispatchers.IO) {
            source
                .filter {
                    container.voicemailCache.existing(it.mailbox, it.folder, it.messageNum) != null
                }
                .map { "${it.folder}/${it.messageNum}" }
                .toSet()
        }
    }

    suspend fun loadCached() {
        rows = withContext(Dispatchers.IO) { container.store.voicemails(mailbox) }
        plan = container.sync.voicemailLookBackPlan(mailbox)
        noteDownloaded(rows)
        loaded = true
    }

    suspend fun refresh() {
        busy = true
        status = null
        try {
            container.sync.refreshVoicemails(mailbox)
        } catch (e: Exception) {
            status = e.message ?: "could not reach VoIP.ms"
        }
        rows = withContext(Dispatchers.IO) { container.store.voicemails(mailbox) }
        plan = container.sync.voicemailLookBackPlan(mailbox)
        noteDownloaded(rows)
        loaded = true
        busy = false
    }

    fun toggle(row: VoiceMailRow) {
        val key = "${row.folder}/${row.messageNum}"
        if (playing == key) {
            player.stop()
            playing = null
            paused = false
            return
        }
        player.stop()
        playing = null
        paused = false
        fetching = key
        scope.launch {
            try {
                val file = container.sync.voicemailAudioFile(
                    container.voicemailCache,
                    row.mailbox,
                    row.folder,
                    row.messageNum,
                )
                player.play(file) {
                    if (playing == key) {
                        playing = null
                        paused = false
                    }
                }
                playing = key
                playingLabel = row.callerId.ifBlank { "Unknown caller" }
                failures = failures - key
                downloaded = downloaded + key
            } catch (e: Exception) {
                failures = failures + (key to (e.message ?: "could not fetch that recording"))
            }
            fetching = null
        }
    }

    fun loadOlder() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                container.sync.voicemailLookBack(mailbox)
                rows = withContext(Dispatchers.IO) { container.store.voicemails(mailbox) }
                plan = container.sync.voicemailLookBackPlan(mailbox)
                noteDownloaded(rows)
                status = null
            } catch (e: Exception) {
                status = e.message ?: "could not load older recordings"
            }
            busy = false
        }
    }

    LaunchedEffect(mailbox) {
        loadCached()
        refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name.ifBlank { "Mailbox $mailbox" })
                        Text("Mailbox $mailbox", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { scope.launch { refresh() } }, enabled = !busy) {
                        Text(if (busy) "Checking…" else "Refresh")
                    }
                },
            )
        },
        bottomBar = {
            // Only while something is loaded. Skip buttons are here because a long message
            // otherwise has to be replayed from the top to hear one part of it again.
            if (playing != null) {
                Surface(tonalElevation = 3.dp) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                playingLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                            TextButton(onClick = { player.seekBy(-5) }) { Text("−5s") }
                            TextButton(
                                onClick = {
                                    if (paused) player.resume() else player.pause()
                                    paused = !paused
                                },
                            ) {
                                Text(if (paused) "Play" else "Pause")
                            }
                            TextButton(onClick = { player.seekBy(5) }) { Text("+5s") }
                            TextButton(
                                onClick = {
                                    player.stop()
                                    playing = null
                                    paused = false
                                },
                            ) {
                                Text("Stop")
                            }
                        }
                        // The controls sat flush against the tab strip and read as part of
                        // it. A rule between them keeps the two things separate.
                        HorizontalDivider()
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            status?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (loaded && rows.isEmpty()) {
                Text("No recordings in this mailbox.", modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                // The key cannot be the message number alone. Marking a message listened
                // moves it from INBOX to Old, so (INBOX, 1) and (Old, 1) can both exist
                // and a bare number would give the list two identical keys.
                items(rows, key = { "${it.folder}/${it.messageNum}" }) { row ->
                    val key = "${row.folder}/${row.messageNum}"
                    VoiceMailItem(
                        row = row,
                        playing = playing == key,
                        fetching = fetching == key,
                        downloaded = key in downloaded,
                        failure = failures[key],
                        onClick = { toggle(row) },
                    )
                    HorizontalDivider()
                }
                item {
                    // Same control as the thread screen, deliberately: one window size,
                    // one label, one behaviour.
                    if (plan.more) {
                        TextButton(
                            onClick = { loadOlder() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (busy) "Loading…" else "Load ${plan.days} more days")
                        }
                    }
                }
            }
        }
    }
}

/**
 * One attachment, fetched when it comes into view.
 *
 * This is what makes the fetcher real on a device: the byte cap, the type sniffing and the
 * cache all run here, against whatever the server actually sends. Drawing the image is the
 * next step; until then the line states what arrived, so a failure is legible rather than an
 * empty space where a picture should be.
 *
 * Nothing is downloaded for a bubble that is never shown, which is what keeps a poll that
 * discovers ten photos from pulling twenty megabytes.
 */
@Composable
private fun AttachmentLine(container: Container, url: String) {
    var result by remember(url) { mutableStateOf<MediaResult?>(null) }
    var expanded by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) { result = container.media.fetch(url) }

    val loaded = result as? MediaResult.Loaded
    if (loaded == null) {
        val label = when (val state = result) {
            null -> "attachment…"
            is MediaResult.Failed -> state.reason
            // Only Loaded reaches the branch above, so this is unreachable but harmless.
            is MediaResult.Loaded -> "attachment"
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
        return
    }

    // Images are drawn here. Audio plays here, because a voice note is the one non-image
    // attachment worth hearing in place. Everything else is handed to another app rather than
    // being reimplemented: whoever sent a video probably already has something that plays it,
    // and the system already knows what that is.
    when (loaded.type.kind) {
        MediaKind.Audio -> {
            AudioAttachmentRow(loaded)
            return
        }
        MediaKind.Video, MediaKind.Other -> {
            OpenAttachmentRow(loaded)
            return
        }
        MediaKind.Image -> Unit
    }

    // Height-capped so one tall photo cannot fill the screen and hide the conversation, and
    // fitted rather than cropped: a crop hides part of what someone sent, which is worse than
    // a smaller picture. A tap opens it whole for detail.
    AsyncImage(
        model = loaded.file,
        contentDescription = "Attachment",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .heightIn(max = 240.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable { expanded = true },
    )
    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            // Without this the dialog is inset from the screen edges, which defeats the point
            // of opening a picture larger: the margins are pixels the image cannot use.
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // Three ways out, because each covers a case the others do not: the button is
            // visible, the background tap is what people try first, and back is what the
            // system promises. An image that fills the width leaves no background to tap.
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AsyncImage(
                    model = loaded.file,
                    contentDescription = "Attachment",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { expanded = false },
                )
                TextButton(
                    onClick = { expanded = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Text("Close", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun AudioAttachmentRow(loaded: MediaResult.Loaded) {
    val player = remember { VoicemailPlayer() }
    var playing by remember { mutableStateOf(false) }
    // Nothing else owns this player, so leaving the screen has to stop it.
    DisposableEffect(Unit) { onDispose { player.stop() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                if (playing) {
                    player.stop()
                    playing = false
                } else {
                    player.play(loaded.file) { playing = false }
                    playing = true
                }
            },
        ) {
            Text(if (playing) "Stop" else "Play")
        }
        Text(
            "audio · ${(loaded.file.length() + 1023) / 1024} KB",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Anything this app will not draw or play, offered to whatever can.
 *
 * A `content://` URI is required: passing a `file://` to another app throws on Android 7 and
 * later, and the read permission has to travel with the intent or the receiving app gets a
 * handle it is not allowed to open.
 */
@Composable
private fun OpenAttachmentRow(loaded: MediaResult.Loaded) {
    val context = LocalContext.current
    val mime = when (loaded.type) {
        MediaType.Mp4 -> "video/mp4"
        MediaType.ThreeGp -> "video/3gpp"
        MediaType.Midi -> "audio/midi"
        MediaType.Pdf -> "application/pdf"
        else -> "application/octet-stream"
    }
    val label = when (loaded.type.kind) {
        MediaKind.Video -> "video"
        else -> loaded.type.name.lowercase()
    }
    TextButton(
        onClick = {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                loaded.file,
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // No handler is possible on a phone with nothing installed for the type, and that
            // is not an error worth a dialog.
            runCatching { context.startActivity(intent) }
        },
    ) {
        Text("Open $label · ${(loaded.file.length() + 1023) / 1024} KB")
    }
}

@Composable
private fun VoiceMailItem(
    row: VoiceMailRow,
    playing: Boolean,
    fetching: Boolean,
    downloaded: Boolean,
    failure: String?,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.callerId.ifBlank { "Unknown caller" },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            // A download glyph before the duration means the audio is on the device, so this
            // tap will be instant and will work with no network. The subtitle already
            // carries "new", so the position next to the duration keeps the two apart.
            if (downloaded) {
                Icon(
                    DownloadGlyph,
                    contentDescription = "Downloaded",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                when {
                    fetching -> "Downloading…"
                    playing -> "Playing…"
                    failure != null -> "Can't play"
                    else -> row.duration
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            buildString {
                append(row.date)
                if (row.urgent) append(" · urgent")
                if (!row.listened) append(" · new")
                if (row.folder.isNotBlank() && row.folder != "INBOX") append(" · ${row.folder}")
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (failure != null) {
            Text(
                failure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (row.transcript.isNotBlank()) {
            Text(row.transcript, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DidListScreen(container: Container, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val time = rememberTimeStyle()
    val now = System.currentTimeMillis() / 1000
    var rows by remember { mutableStateOf<List<DidRow>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val configured = remember { container.settings.credentials() != null }

    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* the system is the record of the answer; it is re-read where it matters */ }

    suspend fun reload() {
        rows = withContext(Dispatchers.IO) { container.store.didsWithCounts() }
    }

    LaunchedEffect(Unit) {
        reload()
        if (configured && rows.isEmpty()) {
            runCatching { container.sync.discoverDids() }
            reload()
        }
    }

    // Asked once, on the first run. A messaging app that silently never notifies
    // reads as broken rather than as quiet, and Android 13 will not ask on its own.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val settings = container.settings
        if (!settings.notificationsEnabled() || settings.notifyAsked()) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return@LaunchedEffect
        settings.setNotifyAsked()
        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("My DID Station")
                        Text(
                            if (container.settings.usingCustomEndpoint()) {
                                "Numbers · not the real API"
                            } else {
                                "Numbers · VoIP.ms"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    val started = System.currentTimeMillis()
                    try {
                        // Discovery runs here too, so a number the account stops
                        // supporting disappears on a pull rather than needing the
                        // credentials saved again.
                        if (configured) container.sync.discoverDids()
                        val added = container.sync.refreshAll()
                        reload()
                        status = "Refreshed ${plural(rows.size, "number")}, $added new in " +
                            durationText(System.currentTimeMillis() - started)
                    } catch (e: Exception) {
                        status = e.message ?: "refresh failed"
                    }
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { SyncStatus(container, status) }
                if (!configured) {
                    item {
                        Text(
                            "Add your VoIP.ms API credentials in Settings to get started. " +
                                "SMS and MMS from every DID on your account arrive here, " +
                                "one row per number.",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(rows, key = { it.did }) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onOpen(row.did) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.did, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (!row.opened) {
                                    // Nothing is fetched until you open a number,
                                    // so there is no count to show yet, and a
                                    // zero would be a lie.
                                    "Tap to load"
                                } else {
                                    plural(row.total, "message") +
                                        if (row.unread > 0) "  ·  ${row.unread} unread" else ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (row.opened && row.unread > 0) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                            )
                        }
                        Text(
                            if (row.lastAt > 0) {
                                When.row(row.lastAt, now, time.tz, time.locale, time.use24Hour)
                            } else {
                                ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

internal fun plural(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    container: Container,
    did: String,
    onBack: () -> Unit,
    onOpen: (Long, String) -> Unit,
    onNew: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val time = rememberTimeStyle()
    val now = System.currentTimeMillis() / 1000
    var rows by remember { mutableStateOf<List<ConvRow>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var older by remember { mutableStateOf(LookBack(days = 0, more = false)) }
    var loadingOlder by remember { mutableStateOf(false) }

    suspend fun reload() {
        rows = withContext(Dispatchers.IO) { container.store.conversations(did) }
    }

    suspend fun refreshOlder() {
        older = withContext(Dispatchers.IO) { container.sync.lookBackPlan(did) }
    }

    // Opening a number enrols it and pulls its first window. A number you never
    // open costs nothing, which is what keeps the first launch quick.
    LaunchedEffect(did) {
        val firstTime = withContext(Dispatchers.IO) { container.store.windowEnd(did) == 0L }
        withContext(Dispatchers.IO) { container.store.markOpened(did) }
        if (firstTime) {
            refreshing = true
            runCatching { container.sync.refreshDid(did) }
            refreshing = false
        }
        reload()
        refreshOlder()
    }

    // Foreground cadence: poll only the number you are looking at, and only while
    // this screen is in front of you. The setting is read on each tick, so a change
    // takes effect without leaving the screen.
    val resumed = isResumed()
    LaunchedEffect(did, resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            val seconds = container.settings.foregroundSeconds()
            if (seconds <= 0) break
            delay(seconds * 1000L)
            val started = System.currentTimeMillis()
            val added = runCatching { container.sync.refreshDid(did) }.getOrDefault(0)
            reload()
            refreshOlder()
            if (added > 0) {
                status = "Refreshed, $added new in " +
                    durationText(System.currentTimeMillis() - started)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(did) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        floatingActionButton = {
            // Compose lives here, not in the top-level list: you are always
            // sending *from* a specific number.
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = onNew,
                content = { Text("New message") },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    val started = System.currentTimeMillis()
                    try {
                        val added = container.sync.refreshDid(did)
                        reload()
                        status = "Refreshed, $added new in " +
                            durationText(System.currentTimeMillis() - started)
                    } catch (e: Exception) {
                        status = e.message ?: "refresh failed"
                    }
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { SyncStatus(container, status) }
                if (rows.isEmpty()) {
                    item {
                        Text(
                            "No conversations on this number yet. Pull down to check, " +
                                "or start one below.",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(rows, key = { it.id }) { row ->
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onOpen(row.id, row.contact) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(row.contact, style = MaterialTheme.typography.titleMedium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (row.lastAt > 0) {
                                    Text(
                                        When.row(
                                            row.lastAt,
                                            now,
                                            time.tz,
                                            time.locale,
                                            time.use24Hour,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (row.unread > 0) {
                                    Text(
                                        "${row.unread}",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                        if (row.preview.isNotBlank()) {
                            Text(
                                row.preview,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                if (older.days > 0) {
                    // Says exactly how much the tap will pull, and disappears
                    // once the two year floor is reached.
                    item {
                        TextButton(
                            enabled = !loadingOlder,
                            onClick = {
                                scope.launch {
                                    loadingOlder = true
                                    status = try {
                                        "${container.sync.lookBack(did)} older"
                                    } catch (e: Exception) {
                                        e.message ?: "could not load older"
                                    }
                                    reload()
                                    refreshOlder()
                                    loadingOlder = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    loadingOlder -> "Loading…"
                                    older.more -> "Load ${older.days} more days"
                                    else -> "Load the last ${older.days} days"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The thread screen, and the composer with it: you always type in a text box at
 * the bottom while the previous messages stay visible above (the standard SMS
 * shape). A new message is the same screen with an empty thread and a "To" field
 * at the top, so nothing is lost when you start writing before choosing a
 * recipient.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    container: Container,
    did: String,
    conversationId: Long,
    contact: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val time = rememberTimeStyle()
    val now = System.currentTimeMillis() / 1000
    var to by remember { mutableStateOf(contact) }
    var resolvedContact by remember { mutableStateOf(contact) }
    var resolvedId by remember { mutableStateOf(conversationId) }
    var messages by remember { mutableStateOf<List<MsgRow>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    suspend fun reload() {
        messages = if (resolvedId == 0L) emptyList() else withContext(Dispatchers.IO) {
            container.store.markConversationRead(resolvedId)
            container.store.messages(resolvedId)
        }
        // Reading it here is what makes its notification pointless.
        if (resolvedContact.isNotEmpty()) container.notifier.cancel(did, resolvedContact)
    }

    // One refresh action, used by the pull at the top and by the pull up at the end. Two
    // copies of this would drift apart the first time either grew a status message.
    val refreshNow: () -> Unit = {
        if (!refreshing) {
            scope.launch {
                refreshing = true
                try {
                    container.sync.refreshDid(did)
                } catch (e: Exception) {
                    error = e.message
                }
                reload()
                refreshing = false
            }
        }
    }

    // While this thread is on screen its own arrivals stay quiet, so reading is
    // not interrupted by a notification for the thing already in front of you.
    DisposableEffect(did, resolvedContact) {
        val key = VisibleThread.of(did, resolvedContact)
        VisibleThread.key = key
        onDispose { if (VisibleThread.key == key) VisibleThread.key = null }
    }

    // A thread opens at its newest message. Keyed on the newest id rather than
    // on the list size, so rows that land above the fold never pull the view
    // away from where you are reading.
    val newestId = messages.lastOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null) listState.scrollToItem(messages.lastIndex)
    }

    // Same foreground cadence as the conversation list, for when you are reading a
    // thread rather than scanning it.
    val resumed = isResumed()
    LaunchedEffect(did, resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            val seconds = container.settings.foregroundSeconds()
            if (seconds <= 0) break
            delay(seconds * 1000L)
            runCatching { container.sync.refreshDid(did) }
            reload()
        }
    }

    // Typing a number that already has history should reveal it, not wait for send.
    // A notification tap arrives with the contact but no conversation id, so the
    // same lookup is what fills the thread in that case too.
    LaunchedEffect(to) {
        if (resolvedId != 0L) {
            reload()
            return@LaunchedEffect
        }
        val normalized = VoipMs.normalizePhone(to)
        if (normalized.length >= 7) {
            val existing = withContext(Dispatchers.IO) {
                container.store.conversationId(did, normalized)
            }
            if (existing != 0L) {
                resolvedId = existing
                resolvedContact = normalized
            }
        }
        reload()
    }

    val sendableContact = VoipMs.normalizePhone(to)
    val canSend = sendableContact.length >= 7 && draft.isNotBlank()

    fun send() {
        if (!canSend || sending) return
        val body = draft.trim()
        val toContact = sendableContact
        scope.launch {
            sending = true
            error = null
            draft = ""
            resolvedContact = toContact
            val now = System.currentTimeMillis() / 1000
            val pendingId = withContext(Dispatchers.IO) {
                container.store.dropStalePending(now - STALE_PENDING_SECONDS)
                container.store.insertPending(did, toContact, body, now)
            }
            resolvedId = withContext(Dispatchers.IO) {
                container.store.ensureConversation(did, toContact)
            }
            // The bubble is in the thread before the request goes out.
            reload()
            try {
                container.sync.deliver(did, toContact, body, pendingId)
            } catch (e: Exception) {
                error = e.message ?: "send failed"
                draft = body
            } finally {
                reload()
                sending = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (resolvedContact.isEmpty()) "New message" else resolvedContact)
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (resolvedContact.isEmpty()) {
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it },
                    label = { Text("To") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    "From $did",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = refreshNow,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                // Pull up at the end of a long thread to refresh as well. Reaching the top of
                // a conversation hundreds of messages deep just to drag it down again is a
                // long way to travel for one request, so the same action is offered where the
                // eye already is. The gesture only starts once the list is at its end, which
                // is what keeps it from firing during ordinary scrolling.
                val pullUpThreshold = with(LocalDensity.current) { PULL_UP_TO_REFRESH.dp.toPx() }
                val pullUp = remember(listState) {
                    var pulled = 0f
                    object : NestedScrollConnection {
                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource,
                        ): Offset {
                            if (listState.canScrollForward || available.y >= 0f) {
                                pulled = 0f
                                return Offset.Zero
                            }
                            pulled += -available.y
                            if (pulled >= pullUpThreshold) {
                                pulled = 0f
                                refreshNow()
                            }
                            return Offset.Zero
                        }

                        override suspend fun onPreFling(available: Velocity): Velocity {
                            pulled = 0f
                            return Velocity.Zero
                        }
                    }
                }
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(pullUp)
                        .padding(horizontal = 12.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                        // The day is stated once, above the first message of that day,
                        // so each bubble only has to carry a clock.
                        val day = When.day(message.sentAt, now, time.tz, time.locale)
                        val newDay = index == 0 ||
                            day != When.day(
                                messages[index - 1].sentAt,
                                now,
                                time.tz,
                                time.locale,
                            )
                        if (newDay) {
                            Text(
                                day,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (message.incoming)
                                Arrangement.Start else Arrangement.End,
                        ) {
                            Surface(
                                color = if (message.incoming)
                                    MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth(0.8f),
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    if (message.body.isNotBlank()) {
                                        MessageBody(
                                            text = message.body,
                                            linkColor = MaterialTheme.colorScheme.primary,
                                        )
                                    } else if (message.media.isEmpty()) {
                                        // A message that arrived with no text and no attachment is
                                        // still a message, and saying so is itself the test: an
                                        // empty bubble cannot distinguish "the API sent nothing"
                                        // from "we failed to draw it".
                                        Text(
                                            "(empty message body)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    message.media.forEach { url ->
                                        AttachmentLine(container, url)
                                    }
                                    val status = message.carrierStatus.trim().takeIf {
                                        // Only sent messages have a meaningful status. The
                                        // vendor doc only ever attaches the field to type 0,
                                        // and on a received message "undelivered" reads as a
                                        // lie about a message you are looking at.
                                        !message.incoming &&
                                            it.isNotEmpty() &&
                                            !it.equals("Information not available", ignoreCase = true)
                                    }
                                    Text(
                                        buildString {
                                            append(
                                                if (message.pending) "Sending…"
                                                else When.clock(
                                                    message.sentAt,
                                                    time.tz,
                                                    time.locale,
                                                    time.use24Hour,
                                                )
                                            )
                                            if (status != null) append("  ·  ").append(status)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (status != null &&
                                            (status.contains("undeliver", true) ||
                                                status.contains("fail", true))
                                        ) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            Color.Unspecified
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Message") },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    )
                    Button(onClick = { send() }, enabled = canSend) {
                        if (sending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Sending")
                        } else {
                            Text("Send")
                        }
                    }
                }

                val analysis = Sms.analyze(draft)
                Text(
                    buildString {
                        append(analysis.units).append(" / ").append(analysis.budget)
                        if (analysis.segments > 1) append("  ·  ").append(analysis.segments).append(" parts")
                        if (analysis.encoding == SmsEncoding.UCS2) append("  ·  Unicode")
                        if (analysis.hardBreak) append("  ·  splits inside a long word")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
                if (analysis.segments > 1) {
                    val parts = Sms.parts(draft, analysis)
                    parts.dropLast(1).take(3).forEachIndexed { index, part ->
                        Text(
                            "${index + 1}→${index + 2}: …" +
                                part.takeLast(12).trim() + "  |  " +
                                parts[index + 1].take(12).trim() + "…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A message body, with its links tappable and its text selectable.
 *
 * Selection is the outer container on purpose: a long press starts a selection, so
 * the handles, Copy and Select all come from the platform. A tap still reaches a
 * link inside it, because Compose resolves the link annotation before the gesture
 * becomes a selection.
 */
@Composable
private fun MessageBody(text: String, linkColor: Color) {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
    )
    val annotated = remember(text, linkStyles) {
        buildAnnotatedString {
            append(text)
            TextLinks.find(text).forEach { range ->
                val url = TextLinks.toUrl(text.substring(range.first, range.last + 1))
                addLink(
                    LinkAnnotation.Url(url = url, styles = linkStyles),
                    range.first,
                    range.last + 1,
                )
            }
        }
    }
    SelectionContainer { Text(annotated) }
}

/** Zone, locale and the 24-hour habit, gathered once per screen. */
private data class TimeStyle(val tz: TimeZone, val locale: Locale, val use24Hour: Boolean)

@Composable
private fun rememberTimeStyle(): TimeStyle {
    val context = LocalContext.current
    return remember(context) {
        TimeStyle(
            tz = TimeZone.getDefault(),
            locale = Locale.getDefault(),
            use24Hour = AndroidDateFormat.is24HourFormat(context),
        )
    }
}

/** A label and a row of mutually exclusive choices, used for the sync cadence. */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<Pair<String, Int>>,
    selected: Int,
    onPick: (Int) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (text, value) ->
                val chosen = value == selected
                TextButton(onClick = { onPick(value) }) {
                    Text(
                        text,
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                        color = if (chosen) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** The activity hosting this composition. Null in a preview, where there is none. */
private fun Context.hostActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        val next = context.baseContext
        if (next === context) return null
        context = next
    }
    return context as? Activity
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: Container, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(container.settings.email()) }
    var password by remember { mutableStateOf("") }
    // Both fields hold something worth keeping off a shoulder or a screenshot, so
    // they start hidden and each has its own reveal.
    var revealEmail by remember { mutableStateOf(false) }
    var revealPassword by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<Int?>(null) }
    val viewFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    // A screenshot, a screen recording or the recents card of this screen would show
    // which account is configured. Cleared on the way out, so threads stay shareable.
    DisposableEffect(context) {
        val window = context.hostActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var foreground by remember { mutableStateOf(container.settings.foregroundSeconds()) }
    var background by remember { mutableStateOf(container.settings.backgroundMinutes()) }
    var endpoint by remember { mutableStateOf(container.settings.endpoint()) }
    // What the app is actually pointed at. It only changes when an edit is finished, so
    // a poll cannot fire at a half-typed URL.
    var activeEndpoint by remember { mutableStateOf(container.settings.endpoint()) }
    var showEndpoint by remember { mutableStateOf(false) }
    var notifyOn by remember { mutableStateOf(container.settings.notificationsEnabled()) }
    var notifyAllowed by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notifyAllowed = it }

    fun askToNotify() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // When numbers turn up, hand focus to the button that goes to see them. That
    // also drops it from the credential fields, and the keyboard closes.
    LaunchedEffect(found) { if (found != null) viewFocus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("VoIP.ms API credentials", style = MaterialTheme.typography.titleMedium)
            Text(
                "Main Menu → SOAP & REST/JSON API. Enable the API, set an API password, " +
                    "and allow this device's IP if your account restricts it.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("API username (email)") },
                singleLine = true,
                visualTransformation = if (revealEmail) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                trailingIcon = {
                    TextButton(onClick = { revealEmail = !revealEmail }) {
                        Text(if (revealEmail) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("API password") },
                singleLine = true,
                visualTransformation = if (revealPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { revealPassword = !revealPassword }) {
                        Text(if (revealPassword) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = email.isNotBlank() && password.isNotBlank() && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        status = null
                        found = null
                        try {
                            // commit() writes synchronously, so it belongs off the main
                            // thread. Sealing can fail when the device has no usable
                            // Keystore, so this shares the try with the network call.
                            withContext(Dispatchers.IO) {
                                container.settings.save(email, password)
                            }
                            val count = container.sync.discoverDids()
                            found = count
                            status = if (count == 1) "Found 1 number." else "Found $count numbers."
                            keyboard?.hide()
                        } catch (e: Exception) {
                            status = e.message ?: "could not reach VoIP.ms"
                        }
                        busy = false
                    }
                },
            ) {
                Text(if (busy) "Checking…" else "Save and find numbers")
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            found?.let { count ->
                Button(
                    onClick = onDone,
                    modifier = Modifier.focusRequester(viewFocus),
                ) {
                    Text(if (count == 1) "View the number" else "View $count numbers")
                }
            }

            HorizontalDivider()
            Text(
                "Sync",
                style = MaterialTheme.typography.titleMedium,
                // Hidden on purpose: long-press to reveal the endpoint override, so
                // this stays out of the way of ordinary use but is reachable when
                // pointing the app at the mock server.
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onLongPress = { showEndpoint = !showEndpoint })
                },
            )
            if (showEndpoint) {
                val focus = LocalFocusManager.current
                // The store normalizes what it is given: it trims, and an empty or
                // default value clears the override. Showing what is in force rather
                // than what was typed keeps that honest.
                fun commitEndpoint() {
                    if (endpoint == activeEndpoint) return
                    container.settings.setEndpoint(endpoint)
                    activeEndpoint = container.settings.endpoint()
                    endpoint = activeEndpoint
                }
                // Back or leaving the screen can happen without the field losing focus
                // first, so the edit is written on the way out too. The state below has
                // to be the latest value, not the one from the composition that created
                // this effect.
                val latest = rememberUpdatedState(endpoint)
                DisposableEffect(Unit) {
                    onDispose { container.settings.setEndpoint(latest.value) }
                }
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("API endpoint") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) commitEndpoint() },
                )
                Text(
                    if (activeEndpoint == Settings.DEFAULT_ENDPOINT) "In force: VoIP.ms"
                    else "In force: $activeEndpoint",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Focus loss alone is not enough: tapping plain text does not take focus
                // away from a text field, so an edit would sit uncommitted while the app
                // kept using the old endpoint. The button is the deliberate commit.
                TextButton(
                    onClick = {
                        commitEndpoint()
                        focus.clearFocus()
                    },
                    enabled = endpoint != activeEndpoint,
                ) {
                    Text("Use this endpoint")
                }
                Text(
                    "For the mock server, use http://10.0.2.2:8765/rest.php on an " +
                        "emulator. Only a debug build talks cleartext, and only to that " +
                        "host. Clearing the field puts it back to VoIP.ms. Credentials " +
                        "are sent to whatever is in here, so treat it as a development " +
                        "control.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Polling costs one request per number per tick, so these are the two " +
                    "knobs that decide how much the app talks to VoIP.ms.",
                style = MaterialTheme.typography.bodySmall,
            )
            ChoiceRow(
                label = "While a number is open",
                options = listOf("30 s" to 30, "60 s" to 60, "2 min" to 120, "off" to 0),
                selected = foreground,
                onPick = {
                    foreground = it
                    container.settings.setForegroundSeconds(it)
                },
            )
            ChoiceRow(
                label = "In the background",
                options = listOf("15 min" to 15, "30 min" to 30, "60 min" to 60),
                selected = background,
                onPick = {
                    background = it
                    container.settings.setBackgroundMinutes(it)
                    // Re-arm so the new interval applies now, not after the old one.
                    SyncWorker.schedule(context, rearm = true)
                },
            )

            HorizontalDivider()
            Text("Notifications", style = MaterialTheme.typography.titleMedium)
            Text(
                "One notification per conversation, replaced as more messages arrive. " +
                    "The thread you are reading never notifies, and opening a thread " +
                    "clears its notification.",
                style = MaterialTheme.typography.bodySmall,
            )
            ChoiceRow(
                label = "When a poll finds new messages",
                options = listOf("on" to 1, "off" to 0),
                selected = if (notifyOn) 1 else 0,
                onPick = {
                    notifyOn = it == 1
                    container.settings.setNotificationsEnabled(notifyOn)
                    if (notifyOn) askToNotify()
                },
            )
            if (notifyOn && !notifyAllowed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Android is blocking notifications for this app.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { askToNotify() }) { Text("Allow") }
                }
            }
        }
    }
}
