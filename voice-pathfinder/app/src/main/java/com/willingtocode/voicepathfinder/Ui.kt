package com.willingtocode.voicepathfinder

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@Composable
fun PathfinderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/** One titled block. Everything on this screen is evidence, so every block is labelled. */
@Composable
private fun Section(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
fun PathfinderScreen(pathfinder: Pathfinder) {
    val context = LocalContext.current
    val state by pathfinder.state.collectAsState()
    val stats by pathfinder.stats.collectAsState()
    val answers by pathfinder.answers.collectAsState()
    val accounts by pathfinder.accounts.collectAsState()
    val accountState by pathfinder.accountState.collectAsState()
    val provision by pathfinder.provision.collectAsState()
    val log by pathfinder.log.collectAsState()
    val calls by pathfinder.calls.collectAsState()

    val settings = pathfinder.apiSettings
    var number by remember { mutableStateOf(settings.lastNumber) }
    var mode by remember {
        mutableStateOf(OfferMode.entries.firstOrNull { it.name == settings.offerMode } ?: OfferMode.PCMU)
    }
    var selectedId by remember { mutableStateOf(settings.selectedAccountId) }

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pathfinder.dial(number, mode)
        } else {
            pathfinder.warn("The microphone permission was not granted.")
        }
    }
    fun startCall() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) pathfinder.dial(number, mode) else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Android 15 and later lay this activity out edge to edge whether the
                // theme asks for it or not, so the title would sit under the clock.
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Voice Pathfinder", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Phase 0 of notes/VOICE-CALL-PLAN.md. It registers a VoIP.ms sub-account over SIP, " +
                    "places one call, and reports what the provider negotiated. Not a product: " +
                    "no service, no store, no notifications.",
                style = MaterialTheme.typography.bodySmall,
            )

            ApiSection(pathfinder)

            SubAccountsSection(
                pathfinder = pathfinder,
                accounts = accounts,
                accountState = accountState,
                selectedId = selectedId,
                onSelect = { id ->
                    selectedId = id
                    pathfinder.prepare(id)
                },
            )

            ProvisionSection(
                pathfinder = pathfinder,
                provision = provision,
                onReprepare = { selectedId.takeIf { it.isNotBlank() }?.let(pathfinder::prepare) },
            )

            RegistrationSection(pathfinder, state)

            CallSection(
                pathfinder = pathfinder,
                state = state,
                stats = stats,
                number = number,
                onNumber = { number = it },
                mode = mode,
                onMode = {
                    mode = it
                    settings.offerMode = it.name
                },
                onDial = ::startCall,
            )

            EvidenceSection(state, answers, stats)

            CallRecordsSection(pathfinder, calls)

            LogSection(
                log = log,
                report = { pathfinder.report() },
                onExport = { pathfinder.export() },
                context = context,
            )
        }
    }
}

@Composable
private fun ApiSection(pathfinder: Pathfinder) {
    val settings = pathfinder.apiSettings
    var saved by remember { mutableStateOf(settings.apiEmail()) }
    var email by remember { mutableStateOf(settings.apiEmail()) }
    var password by remember { mutableStateOf("") }

    Section(
        title = "1  VoIP.ms API account",
        subtitle = "The portal email and the API password created for it. Sealed with the " +
            "Android Keystore; the sub-account's SIP password is never written to disk.",
    ) {
        if (saved.isNotBlank()) {
            Text("Saved for $saved", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("API email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if (saved.isBlank()) "API password" else "API password (type to replace)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    pathfinder.saveApi(email.trim(), password)
                    saved = email.trim()
                    password = ""
                },
                enabled = email.isNotBlank() && password.isNotEmpty(),
            ) { Text("Save") }
            TextButton(
                onClick = {
                    pathfinder.clearApi()
                    saved = ""
                    email = ""
                    password = ""
                },
                enabled = saved.isNotBlank(),
            ) { Text("Remove") }
            TextButton(
                onClick = { pathfinder.loadAccounts() },
                enabled = saved.isNotBlank(),
            ) { Text("Load sub-accounts") }
        }
        Text(
            "Saving replaces the stored pair. Nothing is echoed back after this.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SubAccountsSection(
    pathfinder: Pathfinder,
    accounts: List<SubAccount>,
    accountState: AccountListState,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Section(
        title = "2  Sub-account",
        subtitle = "From getSubAccounts. Picking one prepares its SIP credentials; " +
            "the SIP username is the `account` field, not the description.",
    ) {
        when (accountState) {
            AccountListState.Idle -> Text(
                "Save the API credentials, then load the list.",
                style = MaterialTheme.typography.bodySmall,
            )
            AccountListState.Loading -> Text("Reading the sub-accounts…")
            is AccountListState.Failed -> Text(
                "Could not read them: ${accountState.reason}",
                color = MaterialTheme.colorScheme.error,
            )
            AccountListState.Loaded -> {
                if (accounts.isEmpty()) {
                    Text("This VoIP.ms account has no sub-accounts.")
                }
                for (sub in accounts) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = sub.id == selectedId,
                                role = Role.RadioButton,
                                onClick = { onSelect(sub.id) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sub.id == selectedId, onClick = null)
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(sub.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${sub.account} · ${sub.allowedCodecs.ifBlank { "no codecs" }} · " +
                                    "caller ${sub.callerId.ifBlank { "none" }} · " +
                                    "dialling ${Provisioning.dialingModeLabel(sub.dialingMode)} · " +
                                    "sip_traffic ${if (sub.sipTraffic) "on" else "off"} · " +
                                    "expiry ${sub.maxExpiry} s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        TextButton(onClick = { pathfinder.loadAccounts() }) { Text("Reload the list") }
    }
}

/** One label and one value, the shape of everything in the evidence blocks. */
@Composable
private fun Detail(label: String, value: String, error: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProvisionSection(
    pathfinder: Pathfinder,
    provision: ProvisionState,
    onReprepare: () -> Unit,
) {
    val settings = pathfinder.apiSettings
    var serverText by remember { mutableStateOf(settings.serverOverride) }
    var tls by remember { mutableStateOf(settings.useTls) }
    var portText by remember { mutableStateOf(settings.portFor(settings.useTls).toString()) }

    Section(
        title = "3  SIP connection",
        subtitle = "Read from the API, not typed: the password and the server come from the " +
            "sub-account and the provider's own registration status.",
    ) {
        // The most frequent action in the whole app, so it leads the section and looks like
        // what it is. It used to sit between the codec buttons, below every row of
        // read-only detail, as the third TextButton in a row.
        Button(
            onClick = {
                settings.serverOverride = serverText.trim()
                settings.useTls = tls
                portText.toIntOrNull()?.let { settings.serverPort = it }
                onReprepare()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Prepare") }
        when (provision) {
            ProvisionState.Idle -> Text(
                "Pick a sub-account above.",
                style = MaterialTheme.typography.bodySmall,
            )
            is ProvisionState.Working -> Text(provision.step, style = MaterialTheme.typography.bodyMedium)
            is ProvisionState.Failed -> Text(
                provision.reason,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            is ProvisionState.Ready -> {
                val credentials = provision.credentials
                val detail = provision.detail
                Detail("server", "${credentials.host}:${credentials.port}")
                Detail("signalling", credentials.transport)
                Detail("server source", detail.serverSource)
                Detail("username", credentials.username)
                Detail(
                    "password",
                    if (detail.generatedPassword) {
                        "generated and written back (${Provisioning.PASSWORD_LENGTH} chars, not shown)"
                    } else {
                        "read from the API (not stored, not shown)"
                    },
                )
                Detail("codecs", detail.codecs.ifBlank { "-" })
                Detail("requested expiry", "${credentials.expiresSeconds} s")
                Detail(
                    "provider registration",
                    if (detail.registered) "registered ${detail.registeredOn}" else "not registered elsewhere",
                )
                Detail("provider transport", detail.transport)
                // The one setting that decides SRTP or clear media. It belongs to the
                // sub-account, so every device on it changes too, which the switch says.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = detail.sipTraffic,
                        onCheckedChange = { pathfinder.setEncryptedSipTraffic(it) },
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("Encrypted SIP Traffic", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (detail.sipTraffic) {
                                "On: TLS and SRTP are both mandatory, and a plain RTP offer " +
                                    "is refused with 488."
                            } else {
                                "Off: media is in the clear, and even an encrypted offer is " +
                                    "answered with plain RTP. Nothing is protected."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (detail.generatedPassword) {
                    Text(
                        "This app changed the sub-account's SIP password, because the API returned " +
                            "none. The provider now holds the generated one for every device that uses " +
                            "this sub-account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Text(
            "Server override. Leave blank to use the provider's answer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = tls,
                onCheckedChange = { on ->
                    tls = on
                    settings.useTls = on
                    // Moving between transports moves the port with it, unless a port
                    // was typed that is not either default.
                    val current = portText.toIntOrNull()
                    val otherDefault = if (on) 5060 else 5061
                    if (current == null || current == otherDefault) {
                        portText = if (on) "5061" else "5060"
                    }
                },
            )
            Text(
                "TLS signalling",
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "TLS listens on 5061 and plain UDP on 5060. The port field below is what is used.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = serverText,
                onValueChange = { serverText = it },
                label = { Text("SIP server") },
                placeholder = { Text("atlanta.voip.ms") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(96.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { pathfinder.enableG722() }) { Text("Allow G.722 on this sub-account") }
            TextButton(onClick = { pathfinder.preferG722() }) { Text("Put G.722 first") }
        }
        Text(
            "These three buttons write the sub-account, which every device on it shares. The " +
                "codec order used to look impossible to set, and it was not: the writes were " +
                "being rejected for a missing field. Now that they land, the order is worth " +
                "asking for again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Which codec this probe asks for is a per-call choice, not an account setting. " +
                "It lives in section 5 under Offer mode. The switch above turns encryption off " +
                "from here, then Prepare so the new setting is read.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RegistrationSection(pathfinder: Pathfinder, state: PathState) {
    val provision by pathfinder.provision.collectAsState()
    val ready = (provision as? ProvisionState.Ready)?.credentials
    val busy = state is PathState.Dialing || state is PathState.Ringing || state is PathState.Connected

    Section(
        title = "4  Register",
        subtitle = "No foreground service in this phase, so the registration lives in the process. " +
            "Keep the screen on, as it is held for you.",
    ) {
        Text(stateText(state), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { ready?.let { pathfinder.register(it) } },
                enabled = ready != null && !busy,
            ) { Text("Register") }
            OutlinedButton(
                onClick = { pathfinder.unregister() },
                enabled = state is PathState.Registered,
            ) { Text("Unregister") }
            TextButton(onClick = { pathfinder.release() }) { Text("Release") }
        }
        // Open question 3: how long the provider keeps believing a client that has gone
        // quiet. The client can ask for a short Expires and the registrar grants the lesser
        // of that and its own ceiling, so this needs no account change at all.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { ready?.let { pathfinder.register(it, 60) } },
                enabled = ready != null && !busy,
            ) { Text("Register for 60 s") }
            TextButton(
                onClick = { pathfinder.measureLapse() },
                enabled = state is PathState.Registered,
            ) { Text("Stop refreshing and watch") }
        }
        Text(
            "Register for 60 s first, which asks the registrar for a minute and makes it grant " +
                "that. Then stop refreshing and the lapse lands in about a minute, asked every " +
                "5 s. Redoing the full 3600 s grant needs an idle hour. Capping max_expiry " +
                "through the API was tried and the provider kept 3600, so the request goes in " +
                "the REGISTER instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CallSection(
    pathfinder: Pathfinder,
    state: PathState,
    stats: MediaStats,
    number: String,
    onNumber: (String) -> Unit,
    mode: OfferMode,
    onMode: (OfferMode) -> Unit,
    onDial: () -> Unit,
) {
    val connected = state is PathState.Connected
    val ringing = state is PathState.Dialing || state is PathState.Ringing

    Section(
        title = "5  Call",
        subtitle = "Every call is encrypted except the unencrypted control, which needs " +
            "Encrypted SIP Traffic off on the sub-account. That control is the only mode " +
            "that can carry audio in this build.",
    ) {
        OutlinedTextField(
            value = number,
            onValueChange = onNumber,
            label = { Text("Number to dial") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Digits are sent; spaces, dashes and brackets are dropped. A leading + is kept, " +
                "which is what an E164 dialing mode needs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Offer mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (option in OfferMode.entries) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = option == mode,
                        role = Role.RadioButton,
                        onClick = { onMode(option) },
                    ),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = option == mode, onClick = null)
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        option.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onDial,
                enabled = !connected && !ringing,
                modifier = Modifier.weight(1f),
            ) {
                Text("Dial: ${mode.label}")
            }
            OutlinedButton(
                onClick = { pathfinder.hangUp() },
                enabled = connected || ringing,
            ) { Text("Hang up") }
        }
        // Its own row on purpose: beside the other two the label was left a column about
        // one character wide, so "Use speaker" stacked vertically into unreadable letters.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { pathfinder.setSpeaker(!stats.speaker) },
                enabled = connected,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (stats.speaker) "Use earpiece" else "Use speaker")
            }
        }
        Text(
            "DTMF is not sent in this phase. Calling a phone you control is the clearest test.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EvidenceSection(state: PathState, answers: ProbeAnswers, stats: MediaStats) {
    Section(
        title = "6  What the provider answered",
        subtitle = "The three open questions this app exists for, answered from the dialog.",
    ) {
        Detail("offered", answers.offered.ifEmpty { "-" })
        Detail("media protocol", answers.protocol.ifEmpty { "-" })
        Detail(
            "media encrypted",
            when {
                answers.protocol.isEmpty() -> "unknown, no answer yet"
                answers.encrypted -> "YES (${answers.cryptoLines} crypto line(s))"
                else -> "no, RTP is in the clear"
            },
            error = answers.encrypted,
        )
        Detail(
            "negotiated codec",
            if (answers.negotiated.isEmpty()) "-" else {
                "${answers.negotiated} (payloads ${answers.payloadTypes.ifEmpty { "-" }}, " +
                    "ptime ${answers.ptime ?: "-"})"
            },
        )
        Detail("remote media", answers.remoteMedia.ifEmpty { "-" })
        Detail(
            "INVITE to 200 OK",
            if (answers.inviteMillis >= 0) "${answers.inviteMillis} ms" else "-",
        )
        Detail("incoming INVITEs", answers.incomingInvites.toString())
        Detail("call state", stateText(state))

        Text("Media", style = MaterialTheme.typography.bodyMedium)
        LevelBar("out", stats.sendLevel)
        LevelBar("in", stats.receiveLevel)
        Detail(
            "frames",
            "${stats.framesSent} sent, ${stats.framesReceived} received, ${stats.lost} lost, " +
                "${stats.late} late, ${stats.duplicates} duplicated",
        )
        Detail("RTP port", if (stats.localRtpPort == 0) "-" else stats.localRtpPort.toString())
        Detail("echo canceller", if (stats.echoCancelled) "on" else "off")
    }
}

@Composable
private fun LevelBar(label: String, level: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(24.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(level.coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun CallRecordsSection(pathfinder: Pathfinder, calls: CallsView) {
    Section(
        title = "7  Calls the provider logged",
        subtitle = "getCDR for the last day, with every disposition. The log lines are the " +
            "provider's own account of what happened, which is the only witness after a call " +
            "it accepted and then failed.",
    ) {
        Button(onClick = { pathfinder.loadCalls() }, enabled = !calls.loading) {
            Text(if (calls.loading) "Reading…" else "Read today's calls")
        }
        calls.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (!calls.loading && calls.error == null && calls.records.isEmpty()) {
            Text("Nothing read yet, or nothing recorded.", style = MaterialTheme.typography.bodySmall)
        }
        for (record in calls.records) {
            Text(
                "${record.date}  ${record.disposition}  ${record.description}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (record.logs.isNotBlank()) {
                Text(
                    record.logs,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LogSection(
    log: List<String>,
    report: () -> String,
    onExport: () -> String?,
    context: Context,
) {
    val scroll = rememberScrollState()
    // Follow the tail. A SIP exchange is many lines, and the newest message is the one
    // being read; a fixed-height box without this shows the oldest lines and clips the
    // rest, which is the opposite of useful.
    LaunchedEffect(log.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Section(
        title = "8  Log",
        subtitle = "SIP messages with digest responses redacted. Newest at the bottom. " +
            "Save to files writes both the report and this log where adb can pull them.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { copyToClipboard(context, "Report", report()) }) { Text("Copy report") }
            TextButton(
                onClick = { copyToClipboard(context, "Log", log.joinToString("\n")) },
                enabled = log.isNotEmpty(),
            ) { Text("Copy log") }
            TextButton(
                onClick = {
                    val path = onExport()
                    Toast.makeText(
                        context,
                        if (path != null) "Saved to $path" else "Could not save to files",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                enabled = log.isNotEmpty(),
            ) { Text("Save to files") }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 360.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        ) {
            if (log.isEmpty()) {
                Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll),
                ) {
                    SelectionContainer {
                        Text(
                            text = log.takeLast(LOG_LINES_SHOWN).joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color.Unspecified,
                        )
                    }
                }
            }
        }
    }
}

private fun stateText(state: PathState): String = when (state) {
    PathState.Unregistered -> "Not registered"
    is PathState.Registering -> "Registering…"
    is PathState.Registered -> "Registered, granted ${state.grantedSeconds} s"
    is PathState.RegistrationFailed -> "Registration failed: ${state.reason}"
    is PathState.Dialing -> "Dialing ${state.number}"
    is PathState.Ringing -> "Ringing ${state.number}"
    is PathState.Connected ->
        if (state.audio) "Connected, audio running" else "Connected without audio (${state.codec})"
    is PathState.Ended -> "Idle: ${state.reason}"
    is PathState.Failed -> "Failed: ${state.reason}"
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

private const val LOG_LINES_SHOWN = 30
