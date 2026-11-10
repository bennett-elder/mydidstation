package com.willingtocode.voicepathfinder

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

/** The two places the app has. Numbers is setup, not a destination. */
private enum class Page(val label: String) {
    Dial("Dial"),
    Calls("Calls"),
    Settings("Settings"),
}

/**
 * The product shell.
 *
 * Onboarding first, because a phone app that opens on a wall of diagnostics is a tool for
 * its author. Then the numbers, which is the only decision the app really asks: a call can
 * only arrive at a DID whose routing points at a sub-account this app is registered on.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ProductRoot(pathfinder: Pathfinder) {
    var hasCredentials by remember { mutableStateOf(pathfinder.apiSettings.credentials() != null) }
    var page by remember { mutableStateOf(Page.Dial) }
    // What the dialer is holding, kept here so a history row can prefill it.
    var dialNumber by remember { mutableStateOf(pathfinder.apiSettings.lastNumber) }
    var dialDid by remember { mutableStateOf<String?>(null) }
    var confirmed by remember { mutableStateOf(pathfinder.apiSettings.numbersConfirmed) }

    // The account is loaded from saved credentials without asking, which is the point of
    // having saved them. Onboarding is not a flag, it is the presence of a sealed blob.
    LaunchedEffect(hasCredentials) {
        if (hasCredentials) {
            // The cache fills the screen, and registration is restored from it. The account
            // is re-read on demand or by the service, not on every launch.
            pathfinder.startUp()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        when {
            !hasCredentials -> ConnectPage(pathfinder) { hasCredentials = true }
            !confirmed -> OnboardingScreen(pathfinder) { confirmed = true }
            else -> Scaffold(
                topBar = {
                    Column {
                        TopAppBar(title = { Text("Voice") })
                        PrimaryTabRow(selectedTabIndex = page.ordinal) {
                            for (option in Page.entries) {
                                Tab(
                                    selected = option == page,
                                    onClick = { page = option },
                                    text = { Text(option.label) },

                                )
                            }
                        }
                    }
                },
            ) { inner ->
                Column(
                    modifier = Modifier
                        .padding(inner)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 16.dp, bottom = 32.dp),
                ) {
                    when (page) {
                        Page.Dial -> DialPage(
                            pathfinder = pathfinder,
                            number = dialNumber,
                            onNumber = { dialNumber = it },
                            fromDid = dialDid,
                            onFromDid = { dialDid = it },
                        )
                        Page.Calls -> HistoryPage(
                            pathfinder = pathfinder,
                            onUseInDialer = { dial, account ->
                                // Prefill only. A tap on history is not consent to call.
                                dialNumber = dial
                                dialDid = pathfinder.didForAccount(account)
                                page = Page.Dial
                            },
                        )
                        Page.Settings -> SettingsPage(
                            pathfinder,
                            onSignOut = { hasCredentials = false },
                            onReset = { confirmed = false },
                        )
                    }
                }
            }
        }
    }
}

/** The front door: two fields and one button, and the provider does the rest. */
@Composable
private fun ConnectPage(pathfinder: Pathfinder, onConnected: () -> Unit) {
    val settings = pathfinder.apiSettings
    var email by remember { mutableStateOf(settings.apiEmail()) }
    var secret by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Voice", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sign in with the VoIP.ms account email and an API password. The API password is " +
                "created in the portal and is not the login password.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Account email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("API password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                settings.saveApi(email, secret)
                pathfinder.refreshAccount()
                secret = ""
                onConnected()
            },
            enabled = email.isNotBlank() && secret.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect") }
        Text(
            "The password is sealed with a key held by this device's Keystore. It is never " +
                "written in the clear and never leaves the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { pathfinder.refreshAccount() }) {
            Text("Already connected: reload the account")
        }
        val log by pathfinder.log.collectAsState()
        if (log.isNotEmpty()) {
            Text(
                log.last(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** (123) 456-7890, from whatever the API reports. */
private fun prettyNumber(raw: String): String = when {
    raw.length == 11 && raw.startsWith("1") ->
        "(${raw.substring(1, 4)}) ${raw.substring(4, 7)}-${raw.substring(7)}"
    raw.length == 10 -> "(${raw.substring(0, 3)}) ${raw.substring(3, 6)}-${raw.substring(6)}"
    else -> raw
}

/**
 * Where a number actually goes, in words a person can act on. Internal identifiers do not
 * appear here: a sub-account name like `100000_XYZ` reads as a fault, not as an answer.
 */
private fun routingLabel(did: Did): String = when {
    did.routedAccount != null -> "Answers on this phone"
    did.routing.startsWith("vm:") -> "Goes to voicemail"
    did.routing.startsWith("fwd:") -> "Forwards to ${prettyNumber(did.routing.removePrefix("fwd:"))}"
    did.routing.startsWith("sys:") -> "Answered automatically"
    did.routing.isBlank() -> "No routing set"
    else -> "Routed elsewhere"
}

/** A line's state, in the few words a list row can carry. */
private fun lineLabel(state: LineState?): String? = when (state) {
    null -> null
    LineState.Idle -> "idle"
    is LineState.Preparing -> state.step
    is LineState.Registered -> "registered, ${state.grantedSeconds} s"
    is LineState.Failed -> state.reason
    LineState.Stopped -> "stopped"
}

/** The one list that matters, shared by onboarding and by Settings. */
@Composable
private fun NumbersList(
    pathfinder: Pathfinder,
    selected: Set<String>,
    enabled: Boolean = true,
    onChange: (Set<String>) -> Unit,
) {
    val dids by pathfinder.dids.collectAsState()
    val lines by pathfinder.activeLines.collectAsState()
    val fetching by pathfinder.fetching.collectAsState()
    val fetchError by pathfinder.fetchError.collectAsState()

    fetchError?.let {
        Text(
            "Could not read the account: $it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    for (did in dids) {
        val canRing = did.routedAccount != null
        val lineState = lines.firstOrNull { it.account.equals(did.routedAccount, true) }
            ?.state?.collectAsState()?.value
        Card(
            onClick = {
                if (enabled && canRing) {
                    onChange(
                        if (selected.contains(did.number)) {
                            selected - did.number
                        } else {
                            selected + did.number
                        },
                    )
                }
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selected.contains(did.number),
                    enabled = enabled && canRing,
                    onCheckedChange = { on ->
                        if (canRing) {
                            onChange(if (on) selected + did.number else selected - did.number)
                        }
                    },
                )
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(prettyNumber(did.number), style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(
                            routingLabel(did),
                            lineLabel(lineState),
                            did.description.ifBlank { null },
                        ).joinToString(" \u00b7 "),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            !canRing || lineState is LineState.Failed ->
                                MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
    if (dids.isEmpty() && !fetching && fetchError == null) {
        Text(
            "This account has no numbers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one-time setup, full screen, with no tabs. It is a decision, not a destination: make
 * it and the app is a phone. Afterwards the same list lives in Settings for adjustments.
 */
@Composable
private fun OnboardingScreen(pathfinder: Pathfinder, onFinished: () -> Unit) {
    val provision by pathfinder.provision.collectAsState()
    val lines by pathfinder.activeLines.collectAsState()
    val settings = pathfinder.apiSettings
    var selected by remember { mutableStateOf(settings.selectedDids) }
    val fetchError by pathfinder.fetchError.collectAsState()
    val fetching by pathfinder.fetching.collectAsState()
    val working = provision as? ProvisionState.Working
    val failed = provision as? ProvisionState.Failed

    // Registering is the finish line: once every chosen line is up, the setup is over and
    // this screen is never seen again unless Settings is reset.
    val allUp = lines.isNotEmpty() && lines.all {
        it.state.collectAsState().value is LineState.Registered
    }
    LaunchedEffect(allUp) {
        if (allUp) {
            settings.numbersConfirmed = true
            onFinished()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 32.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Which numbers answer here?", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Choose the numbers this phone should pick up. You can change them later in " +
                "Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Numbers routed somewhere else are shown, but cannot be chosen until the " +
                "routing changes in the VoIP.ms portal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { pathfinder.refreshAccount() }, enabled = working == null && !fetching) { Text("Reload") }
            TextButton(
                onClick = {
                    val all = pathfinder.dids.value
                        .filter { it.routedAccount != null }
                        .map { it.number }
                        .toSet()
                    selected = all
                    pathfinder.selectDids(all)
                    pathfinder.answerOnSelected()
                },
                enabled = working == null && !fetching,
            ) { Text("Select all") }
        }
        NumbersList(pathfinder, selected, enabled = working == null && !fetching) { next ->
            selected = next
            pathfinder.selectDids(next)
        }
        if (working != null || fetching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                (working?.let { "${it.step}… " } ?: "Reading your numbers… ") +
                    "Each step asks the provider for something, so allow up to a minute.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The technical reason goes to the log. A person needs to know what to do next.
        fetchError?.let {
            Text(
                "Could not read the account. Press Reload to try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        failed?.let {
            Text(
                "Could not set this up: ${it.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = { pathfinder.answerOnSelected() },
            enabled = selected.isNotEmpty() && working == null && !fetching,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Start answering") }
    }
}

/** One sentence, because the state has to be legible from across a room. */
private fun stateLabel(state: PathState): String = when (state) {
    PathState.Unregistered -> "Not registered"
    is PathState.Registering -> "Registering"
    is PathState.Registered -> "Ready, registered for ${state.grantedSeconds} s"
    is PathState.RegistrationFailed -> "Not registered: ${state.reason}"
    is PathState.Dialing -> "Calling ${prettyNumber(state.number)}"
    is PathState.Ringing -> "Ringing"
    is PathState.Connected ->
        "In a call, ${state.codec}${if (state.encrypted) ", encrypted" else ""}"
    is PathState.Ended -> "Call ended: ${state.reason}"
    is PathState.Failed -> "Failed: ${state.reason}"
}

/**
 * What the dial page says about answering, in one line.
 *
 * A call in progress wins, because that is what the person is looking at. Otherwise this is
 * the lines' registration, which is the only thing that decides whether a call can reach
 * this phone.
 */
internal fun dialStatus(
    readiness: Pathfinder.Readiness,
    state: PathState,
    starting: Boolean = false,
): String = when {
    state is PathState.Dialing || state is PathState.Ringing || state is PathState.Connected ||
        state is PathState.Ended || state is PathState.Failed -> stateLabel(state)
    readiness.failure != null -> "Not answering: ${readiness.failure}"
    readiness.registered > 0 -> if (readiness.registered == 1) {
        "Ready. 1 line registered."
    } else {
        "Ready. ${readiness.registered} lines registered."
    }
    readiness.preparing -> "Registering…"
    starting -> "Setting up your phone…"
    readiness.lines > 0 -> "Not registered."
    else -> "No number is set up to answer. Choose one in Settings."
}

/**
 * Why a call cannot be placed, or null when it can.
 *
 * A call goes out on a registered line and presents that sub-account, so a registration that
 * is still landing or missing is the only thing that stops the button.
 */
internal fun callBlockedReason(readiness: Pathfinder.Readiness): String? = when {
    readiness.registered > 0 -> null
    readiness.preparing -> "Waiting for the registration to land."
    else -> "No line is registered, so there is nothing to call on."
}

/**
 * The call list, as the provider recorded it.
 *
 * The saved list draws before the read, which is the same rule as the Numbers card: a
 * history that blanks itself on every visit is one the user cannot read. A failed refresh
 * says so above the calls it already has.
 */
@Composable
private fun HistoryPage(
    pathfinder: Pathfinder,
    onUseInDialer: (String, String) -> Unit,
) {
    val calls by pathfinder.calls.collectAsState()
    val checkedAt by pathfinder.callsCheckedAt.collectAsState()

    LaunchedEffect(Unit) {
        // The saved list is already on screen. This replaces it when the provider answers.
        pathfinder.loadCalls()
    }

    val dids by pathfinder.dids.collectAsState()
    val filterDids by pathfinder.callableDids.collectAsState()
    var fromDid by remember { mutableStateOf<String?>(null) }
    // A DID answers on a sub-account, and the CDR row names that sub-account.
    val accountOf = remember(dids, fromDid) {
        fromDid?.let { chosen -> dids.firstOrNull { it.number == chosen }?.routedAccount }
    }
    val shown = remember(calls.records, accountOf) {
        val rows = collapseLegs(calls.records)
        if (accountOf == null) {
            rows
        } else {
            // Any leg will do: an inbound call's account is not on the outgoing leg.
            rows.filter { accountOf.lowercase() in it.accounts }
        }
    }
    // Pulling down is the gesture people try first on a list, and the reload is a read of
    // the provider, which is exactly what a pull means here.
    PullToRefreshBox(
        isRefreshing = calls.loading,
        onRefresh = { pathfinder.loadCalls() },
        modifier = Modifier.fillMaxWidth(),
    ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Calls", style = MaterialTheme.typography.headlineSmall)

        Section(
            "Recent calls",
            "Today and yesterday, as the provider recorded them. A call this phone placed " +
                "appears here once the provider has written it down.",
        ) {
            if (filterDids.size > 1) {
                NumberPicker(
                    // Not "From": this is the line a call belongs to in either direction,
                    // so an inbound, missed or failed call is included too.
                    label = "Line",
                    options = listOf(null) + filterDids,
                    selected = fromDid,
                    onSelect = { fromDid = it },
                )
            }
            when {
                calls.loading && calls.records.isEmpty() -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Reading your calls…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                calls.records.isEmpty() && calls.error != null -> Text(
                    calls.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                calls.records.isEmpty() && checkedAt <= 0L -> Text(
                    // Never read is not the same as nothing there, and the difference is
                    // the whole reason this screen looked broken while it was starting.
                    "Not read yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                calls.records.isEmpty() -> Text(
                    "No calls in the last day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text(
                        callsSummary(shown.size, checkedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (call in shown) {
                        CallRow(call) { onUseInDialer(call.destination, call.account) }
                    }
                    if (shown.isEmpty()) {
                        Text(
                            "No calls on that line in the last day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (calls.error != null) {
                        Text(
                            calls.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            TextButton(
                onClick = { pathfinder.loadCalls() },
                enabled = !calls.loading,
            ) { Text("Reload") }
        }
    }
    }
}

/** One call, after its legs have been folded together. */
internal data class CallRowModel(
    val date: String,
    val destination: String,
    val disposition: String,
    val seconds: Int,
    val direction: String,
    val account: String = "",
    /** Every sub-account named by any leg of this call, for filtering. */
    val accounts: Set<String> = emptySet(),
)

/**
 * Folds the provider's per-leg records into one row per call.
 *
 * Measured on the account: one call appears twice, as `Outbound - Customer` and
 * `Inbound - Local Caller`, with the same timestamp and duration and different UniqueIDs.
 * Listing both makes a single call look like two, so they are grouped on the timestamp and
 * the duration, and the outbound leg supplies the number that was dialled.
 */
internal fun collapseLegs(records: List<CallRecord>): List<CallRowModel> {
    val groups = LinkedHashMap<String, MutableList<CallRecord>>()
    for (record in records) {
        groups.getOrPut("${record.date}|${record.seconds}") { mutableListOf() } += record
    }
    return groups.values.map { legs ->
        val outward = legs.firstOrNull { it.description.startsWith("Outbound", ignoreCase = true) }
        val inward = legs.firstOrNull { it.description.startsWith("Inbound", ignoreCase = true) }
        val shown = outward ?: legs.first()
        val direction = when {
            outward != null -> "Outbound"
            inward != null -> "Inbound"
            else -> ""
        }
        // An inbound row's destination is our own number, so the useful thing to show is
        // the caller. It was already in the record and we were throwing it away.
        val raw = if (outward == null && inward != null) {
            callerNumber(inward.callerId).ifBlank { shown.destination }
        } else {
            shown.destination
        }
        CallRowModel(
            date = shown.date,
            destination = raw.removePrefix("1").takeIf { raw.length == 11 } ?: raw,
            disposition = shown.disposition,
            seconds = shown.seconds,
            direction = direction,
            account = shown.account,
            accounts = legs.map { it.account.lowercase() }.filter { it.isNotBlank() }.toSet(),
        )
    }
}

/** One call: who, when, which way, what the provider decided, and how long it lasted. */
@Composable
private fun CallRow(call: CallRowModel, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(prettyNumber(call.destination), style = MaterialTheme.typography.bodyLarge)
            Text(callLength(call.seconds), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            listOf(dispositionText(call.disposition), call.direction, call.date)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The state of the call list in one line: how many, and how old.
 *
 * The same contract as [numbersSummary] for the same reason, so two screens do not describe
 * freshness two different ways.
 */
internal fun callsSummary(count: Int, checkedAt: Long): String {
    val calls = if (count == 1) "1 call" else "$count calls"
    val fresh = if (checkedAt <= 0L) "not read from the provider yet" else "checked ${clockLabel(checkedAt)}"
    return "$calls, $fresh"
}

/**
 * The number inside a caller ID such as `"Jane Doe" <15551234567>`.
 *
 * Inbound rows carry the caller here and our own number in `destination`, so this is what
 * the row has to show.
 */
internal fun callerNumber(raw: String): String {
    val inBrackets = raw.substringAfter('<', "").substringBefore('>', "")
    val candidate = inBrackets.ifBlank { raw }
    return candidate.filter { it.isDigit() || it == '+' }
}

/** The provider's disposition in words rather than in capitals. */
internal fun dispositionText(raw: String): String = when (raw.trim().uppercase()) {
    "ANSWERED" -> "Answered"
    "NO ANSWER" -> "No answer"
    "BUSY" -> "Busy"
    "FAILED" -> "Failed"
    "CANCELED", "CANCELLED" -> "Canceled"
    else -> raw.trim().ifEmpty { "Unknown" }
}

/** Seconds as a person reads them, and nothing when the call never connected. */
internal fun callLength(seconds: Int): String = when {
    seconds <= 0 -> "-"
    seconds < 60 -> "$seconds s"
    else -> "${seconds / 60} m ${seconds % 60} s"
}

/**
 * One dropdown for choosing a number.
 *
 * Chips were the wrong control: three of them already ran off the edge, and an account with
 * a dozen would be a wall of pills above the list. A menu is one row whatever the count, and
 * it scrolls by itself. `null` means "all numbers", which is only offered on the history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberPicker(
    label: String,
    options: List<String?>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected?.let(::prettyNumber) ?: "All numbers",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(option?.let(::prettyNumber) ?: "All numbers") },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * The call screen, shown whenever a call is live or being placed.
 *
 * It reads a handful of values rather than the controller's internals, so the same screen
 * can be driven by Telecom's `Connection` later without being rewritten.
 */
@Composable
private fun CallScreen(
    number: String,
    state: PathState,
    muted: Boolean,
    stats: MediaStats,
    onMuted: (Boolean) -> Unit,
    onSpeaker: (Boolean) -> Unit,
    onHangUp: () -> Unit,
    onKey: (Char) -> Unit,
) {
    var elapsed by remember { mutableStateOf(0L) }
    val speaker = stats.speaker
    LaunchedEffect(state) {
        // The clock starts when the call connects, not when the number was dialled.
        val at = (state as? PathState.Connected)?.at ?: return@LaunchedEffect
        while (true) {
            elapsed = (System.currentTimeMillis() - at) / 1000
            delay(500)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Call", style = MaterialTheme.typography.headlineSmall)
        Text(prettyNumber(number), style = MaterialTheme.typography.headlineMedium)
        Text(
            when (val current = state) {
                is PathState.Connected -> callLength(elapsed.toInt()) + " · " +
                    current.codec + if (current.encrypted) ", encrypted" else ""
                is PathState.Dialing -> "Calling…"
                is PathState.Ringing -> "Ringing…"
                else -> "In a call"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("Controls", "Everything here acts on the call that is up, not on settings.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip(
                    label = if (muted) "Unmute" else "Mute",
                    on = muted,
                    onClick = { onMuted(!muted) },
                )
                ToggleChip(
                    label = if (speaker) "Earpiece" else "Speaker",
                    on = speaker,
                    onClick = { onSpeaker(!speaker) },
                )
            }
            Button(
                onClick = onHangUp,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Hang up") }
            if (state is PathState.Connected) {
                Keypad(onKey)
                Text(
                    "${stats.framesSent} sent, ${stats.framesReceived} received, ${stats.lost} lost",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The keypad, which only means anything once the call is answered: before that the far end
 * is still ringing and has nothing to listen to.
 */
@Composable
private fun Keypad(onKey: (Char) -> Unit) {
    val rows = listOf("123", "456", "789", "*0#")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (key in row) {
                    OutlinedButton(
                        onClick = { onKey(key) },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text(key.toString(), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

/** A chip that reads as a switch, for the two call controls that are on or off. */
@Composable
private fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(selected = on, onClick = onClick, label = { Text(label) })
}

@Composable
private fun DialPage(
    pathfinder: Pathfinder,
    number: String,
    onNumber: (String) -> Unit,
    fromDid: String?,
    onFromDid: (String?) -> Unit,
) {
    val state by pathfinder.state.collectAsState()
    val stats by pathfinder.stats.collectAsState()
    val readiness by pathfinder.readiness.collectAsState()
    val starting by pathfinder.starting.collectAsState()
    val settings = pathfinder.apiSettings
    val context = LocalContext.current
    val fromDids by pathfinder.callableDids.collectAsState()
    val presenting = fromDid ?: fromDids.firstOrNull()
    val connected = state is PathState.Connected
    val busy = connected || state is PathState.Dialing || state is PathState.Ringing
    val blocked = callBlockedReason(readiness)

    val microphone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pathfinder.dialOut(number, presenting)
        } else {
            pathfinder.warn("The microphone permission was not granted.")
        }
    }
    fun call() {
        settings.lastNumber = number.trim()
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) pathfinder.dialOut(number, presenting) else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    val muted by pathfinder.muted.collectAsState()
    if (busy || state is PathState.Connected) {
        CallScreen(
            number = number,
            state = state,
            muted = muted,
            stats = pathfinder.stats.collectAsState().value,
            onMuted = { pathfinder.setMuted(it) },
            onSpeaker = { pathfinder.setSpeaker(it) },
            onHangUp = { pathfinder.hangUp() },
            onKey = { pathfinder.sendDtmf(it) },
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Dial", style = MaterialTheme.typography.headlineSmall)
        Text(
            dialStatus(readiness, state, starting),
            style = MaterialTheme.typography.bodyMedium,
            color = if (readiness.failure != null || state is PathState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )

        Section(
            "Call",
            "Calls go out on the sub-account that answers your numbers, and the provider " +
                "records them the same way.",
        ) {
            if (fromDids.size > 1) {
                Text(
                    "Call from",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NumberPicker(
                    label = "Call from",
                    options = fromDids,
                    selected = presenting,
                    onSelect = onFromDid,
                )
            }
            OutlinedTextField(
                value = number,
                onValueChange = onNumber,
                label = { Text("Number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { call() },
                    enabled = !busy && number.isNotBlank() && blocked == null,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Call") }
                OutlinedButton(
                    onClick = { pathfinder.hangUp() },
                    enabled = busy,
                    modifier = Modifier.height(52.dp),
                ) { Text("Hang up") }
            }
            if (blocked != null) {
                Text(
                    blocked,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = { pathfinder.setSpeaker(!stats.speaker) },
                enabled = connected,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (stats.speaker) "Use the earpiece" else "Use the speaker") }
            if (connected) {
                Text(
                    "${stats.framesSent} sent, ${stats.framesReceived} received, ${stats.lost} lost",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


/**
 * Only the settings a person needs, in the order they need them. Everything the probe used
 * to answer questions sits behind Diagnostics rather than in the way.
 */
@Composable
private fun SettingsPage(
    pathfinder: Pathfinder,
    onSignOut: () -> Unit,
    onReset: () -> Unit,
) {
    val settings = pathfinder.apiSettings
    val provision by pathfinder.provision.collectAsState()
    val lines by pathfinder.activeLines.collectAsState()
    val dids by pathfinder.dids.collectAsState()
    val checkedAt by pathfinder.checkedAt.collectAsState()
    var selected by remember { mutableStateOf(settings.selectedDids) }
    val saved = settings.selectedDids
    val working = provision as? ProvisionState.Working
    val failed = provision as? ProvisionState.Failed
    val fetching by pathfinder.fetching.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Section(
            "Account",
            "The API password is the only stored secret, sealed with this device's Keystore.",
        ) {
            Detail("email", settings.apiEmail().ifBlank { "-" })
            Detail("password", "sealed on this device")
            TextButton(
                onClick = {
                    settings.clearApi()
                    onSignOut()
                },
            ) { Text("Sign out") }
            TextButton(
                onClick = {
                    pathfinder.selectDids(emptySet())
                    pathfinder.stopAnswering()
                    settings.numbersConfirmed = false
                    selected = emptySet()
                    onReset()
                },
            ) { Text("Reset setup") }
        }

        // The same list as onboarding, so a number can be added or dropped later without a
        // second, slightly different screen to learn.
        Section(
            "Numbers",
            "The numbers this phone answers. Everything else, the server, the transport and " +
                "the encryption, comes from the account itself.",
        ) {
            // Directly under the heading, so the state of the list reads before the list.
            when {
                // A cached list is already on screen, so a background refresh is not reason
                // to cover the section in a progress bar. It shows when there is nothing to
                // show yet, or when an operation the user started is running.
                working != null || (fetching && dids.isEmpty()) -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        (working?.let { "${it.step}… " } ?: "Reading your numbers… ") +
                            "Each step asks the provider for something, so allow up to a minute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                failed != null -> Text(
                    failed.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                fetching -> Text(
                    "Checking your account…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                dids.isNotEmpty() -> Text(
                    numbersSummary(dids.size, checkedAt, lines.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NumbersList(pathfinder, selected, enabled = working == null && !fetching) { next ->
                selected = next
                pathfinder.selectDids(next)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        val all = pathfinder.dids.value
                            .filter { it.routedAccount != null }
                            .map { it.number }
                            .toSet()
                        selected = all
                        pathfinder.selectDids(all)
                        pathfinder.answerOnSelected()
                    },
                    enabled = working == null && !fetching,
                ) { Text("Select all") }
                // The same list, used as the off switch: no numbers means nothing is
                // registered, and it stays that way across restarts.
                TextButton(
                    onClick = {
                        selected = emptySet()
                        pathfinder.selectDids(emptySet())
                        pathfinder.answerOnSelected()
                    },
                    enabled = working == null && !fetching,
                ) { Text("Select none") }
            }
            if (selected != saved) {
                Button(
                    onClick = { pathfinder.answerOnSelected() },
                    enabled = working == null && !fetching,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Apply changes") }
            }
        }
    }
}

/**
 * The state of the list in one line: how many, and how old.
 *
 * "Checked" is when the account was last read, which is the only honest answer to whether
 * the list can be trusted. It says nothing about the registration, which has its own line.
 */
internal fun numbersSummary(count: Int, checkedAt: Long, lines: Int): String {
    val numbers = if (count == 1) "1 number" else "$count numbers"
    val fresh = if (checkedAt <= 0L) "not read from the account yet" else "checked ${clockLabel(checkedAt)}"
    val registered = when (lines) {
        0 -> ""
        1 -> ". 1 line registered."
        else -> ". $lines lines registered."
    }
    return "$numbers, $fresh$registered"
}

/** The time if it was today, the date as well if it was not. */
private fun clockLabel(at: Long): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    val today = day.format(Date()) == day.format(Date(at))
    val format = if (today) "HH:mm" else "MMM d, HH:mm"
    return SimpleDateFormat(format, Locale.getDefault()).format(Date(at))
}


