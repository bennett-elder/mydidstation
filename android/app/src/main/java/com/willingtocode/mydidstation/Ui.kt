package com.willingtocode.mydidstation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MyDidStationTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

sealed interface Screen {
    data object Dids : Screen
    data class Convs(val did: String) : Screen
    data class Msgs(val did: String, val convId: Long, val contact: String) : Screen
    data object Settings : Screen
}

@Composable
fun Root(container: Container) {
    var screen by remember { mutableStateOf<Screen>(Screen.Dids) }
    when (val current = screen) {
        Screen.Dids -> {
            BackHandler(enabled = false) {}
            DidListScreen(
                container = container,
                onOpen = { screen = Screen.Convs(it) },
                onSettings = { screen = Screen.Settings },
            )
        }

        is Screen.Convs -> {
            BackHandler { screen = Screen.Dids }
            ConversationsScreen(
                container = container,
                did = current.did,
                onBack = { screen = Screen.Dids },
                onOpen = { convId, contact -> screen = Screen.Msgs(current.did, convId, contact) },
                onNew = { screen = Screen.Msgs(current.did, 0L, "") },
            )
        }

        is Screen.Msgs -> {
            BackHandler { screen = Screen.Convs(current.did) }
            MessagesScreen(
                container = container,
                did = current.did,
                conversationId = current.convId,
                contact = current.contact,
                onBack = { screen = Screen.Convs(current.did) },
            )
        }

        Screen.Settings -> {
            BackHandler { screen = Screen.Dids }
            SettingsScreen(container = container, onDone = { screen = Screen.Dids })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DidListScreen(container: Container, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<DidRow>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val configured = remember { container.settings.credentials() != null }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Numbers")
                        Text(
                            "SMS & MMS · VoIP.ms",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = { TextButton(onClick = onSettings) { Text("Settings") } },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    status = try {
                        val added = container.sync.refreshAll()
                        "refreshed ${rows.size} number(s), $added new"
                    } catch (e: Exception) {
                        e.message ?: "refresh failed"
                    }
                    reload()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
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
                            if (row.lastAt > 0) shortDate(row.lastAt) else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                }
                status?.let { message ->
                    item { Text(message, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

internal fun plural(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"

internal fun shortDate(epochSeconds: Long): String =
    SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochSeconds * 1000))

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
                    status = try {
                        val added = container.sync.refreshDid(did)
                        "$added new"
                    } catch (e: Exception) {
                        e.message ?: "refresh failed"
                    }
                    reload()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
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
                        ) {
                            Text(row.contact, style = MaterialTheme.typography.titleMedium)
                            if (row.unread > 0) {
                                Text(
                                    "${row.unread}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
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
                status?.let { item { Text(it, modifier = Modifier.padding(16.dp)) } }
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
    }

    // A thread opens at its newest message. Keyed on the newest id rather than
    // on the list size, so rows that land above the fold never pull the view
    // away from where you are reading.
    val newestId = messages.lastOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null) listState.scrollToItem(messages.lastIndex)
    }

    // Typing a number that already has history should reveal it, not wait for send.
    LaunchedEffect(to) {
        val normalized = VoipMs.normalizePhone(to)
        if (resolvedContact.isEmpty() && normalized.length >= 7) {
            val existing = withContext(Dispatchers.IO) {
                container.store.conversationId(did, normalized)
            }
            if (existing != 0L) {
                resolvedId = existing
                resolvedContact = normalized
                reload()
            }
        } else {
            reload()
        }
    }

    val sendableContact = VoipMs.normalizePhone(to)
    val canSend = sendableContact.length >= 7 && draft.isNotBlank() && !sending

    fun send() {
        if (!canSend) return
        scope.launch {
            sending = true
            error = null
            try {
                container.sync.send(did, sendableContact, draft.trim())
                resolvedContact = sendableContact
                resolvedId = withContext(Dispatchers.IO) {
                    container.store.ensureConversation(did, sendableContact)
                }
                draft = ""
                reload()
            } catch (e: Exception) {
                error = e.message ?: "send failed"
            } finally {
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
                onRefresh = {
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
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
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
                                    if (message.body.isNotBlank()) Text(message.body)
                                    message.media.forEach { url ->
                                        // MMS rendering comes next; the URL is real and clickable later.
                                        Text(
                                            "[attachment] $url",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    Text(
                                        shortDate(message.sentAt),
                                        style = MaterialTheme.typography.labelSmall,
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
                        Text(if (sending) "…" else "Send")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: Container, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(container.settings.email()) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().padding(16.dp),
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("API password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = email.isNotBlank() && password.isNotBlank() && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        status = null
                        container.settings.save(email, password)
                        status = try {
                            "Found ${container.sync.discoverDids()} number(s)."
                        } catch (e: Exception) {
                            e.message ?: "could not reach VoIP.ms"
                        }
                        busy = false
                    }
                },
            ) {
                Text(if (busy) "Checking…" else "Save and find numbers")
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
