# Voice Pathfinder

A SIP phone for VoIP.ms, in its own app beside the product app. It began as Phase A of
`notes/VOICE-CALL-PLAN.md` and has finished Phase B: it places and answers calls, carries the
audio encrypted, and stays reachable while the phone is idle.

It is a **temporary module**. Its purpose now is to keep proven voice work installable while
that work moves into `android/`. When the move is done this directory, its workflow and its
releases go away; the tags stay as history. Nothing in `android/` is touched by it, and the
`applicationId` is its own so both apps install and run side by side.

Dates, and the difference between measured and assumed, live in `notes/STATUS.md`. The decisions
this code encodes are D31 to D39 in `notes/DECISIONS.md`.

## What works, on a handset

- **Several numbers at once.** Each chosen number routes to a sub-account, and each sub-account
  gets one SIP registration over TLS.
- **Outbound calls from a chosen number.** The dialer has a line picker, and the call presents
  that sub-account, which is what the far end sees.
- **Incoming calls.** An INVITE rings, `180 Ringing` goes back, and answering sends a `200 OK`
  repeated until the caller's ACK arrives. Decline sends `486`, and a call nobody answers stops
  ringing after 30 seconds.
- **Android carries the call.** A self-managed `PhoneAccount` means Telecom owns audio focus and
  routing, so the call appears in the platform rather than as an app playing audio.
- **Answering from the lock screen.** `CallActivity` is declared `showWhenLocked` and
  `turnScreenOn`, so an incoming call lights the screen and shows Answer and Decline without the
  app being opened.
- **Speaker and earpiece** through `requestCallEndpointChange`, the path Bluetooth rides on. The
  legacy `setAudioRoute` is deliberately not used: it accepts the request, reports the route,
  and leaves the audio where it was.
- **Encrypted media.** SRTP with SDES keys when the account has Encrypted SIP Traffic on,
  negotiated from the SDP in both directions, G.711u in and out.
- **Hang up that ends the call on both ends**, with the BYE built inside its dialog and retried
  until the provider answers it.
- **Registration that survives Doze.** The refresh is scheduled with `AlarmManager`, and a
  screen wake checks whether a line is close to expiry and registers if it is.
- **A cold start in a quarter second.** The SIP credentials from the last successful read are
  kept sealed on disk, so a boot registers without asking the provider for anything.
- **DTMF**, sent as RFC 4733 events and played locally so a press is audible.
- **Call history**, read from `getCDR`, filterable by line, with each call folded from the
  provider's per-leg rows.
- **What the provider thinks.** Settings shows each line's own view of its registration and the
  provider's answer beside it. The two can disagree, and only the provider's decides whether
  calls arrive.

## What does not work yet

- **G.722 and GSM.** G.711u only.
- **Early media.** A `183 Session Progress` carrying SDP is logged and ignored, so the ringing
  tone is generated here rather than taken from the network.
- **Real-time message delivery.** There is no push path. A call arrives because a registration is
  held open, and nothing else does.
- **One call at a time.**
- **Choosing a specific Bluetooth device.** The platform gets the route; picking a headset is not
  implemented.
- **The re-provision after a refused password** is written and has never been exercised. It needs
  a SIP password changed in the portal while the phone holds the old one cached.
## How it is put together

| File | What it owns |
|---|---|
| `Line.kt` | One sub-account's registration: socket, binding, keepalive, refresh, and the alarm that drives it. No media. |
| `Call.kt` | One outbound call: its own SIP transport, the digest challenge, ACK, CANCEL, BYE, and one `MediaSession`. |
| `MediaSession.kt` | A call's socket, SRTP, jitter buffer, both audio directions and counters. No SIP types. |
| `CallAudio.kt` | `AudioRecord` and `AudioTrack`, behind `CallAudioPort` so tests can replace it. |
| `SipMessage`, `Sdp`, `Rtp`, `Srtp`, `G711`, `Dtmf`, `Ringback` | Parsing and codecs. Pure Kotlin, JVM tested. |
| `Pathfinder.kt` | The account, the saved lists, the chosen numbers, readiness, the sealed line cache, and no media at all. |
| `PathfinderService.kt` | A foreground service that holds the registration when the screen is gone, re-reads the account every 15 minutes, and changes its type while a call is up. |
| `RefreshAlarm.kt` | The Doze-safe refresh alarm, its receiver, and the screen-wake check. |
| `VoiceConnectionService.kt` | The Telecom connection, one per call, reporting state and owning the route through call endpoints. |
| `CallActivity.kt` | The call screen over the keyguard. |
| `IncomingAlerts.kt` | The ringing notification, with Answer, Decline and a full-screen intent. |
| `BootReceiver.kt` | Brings the line back after a restart or a profile sign-in. |
| `TelecomBridge.kt` | The account handle, `placeCall`, and how an arriving call is reported to the platform. |
| `Battery.kt` | Whether Android will let the line stay up while the phone idles, and how to ask. |

A call opens its own transport rather than borrowing a line's, because a line's socket carries
its registration and a call that closed it would take the registration down too.

## Staying registered

Three things keep a line reachable, and each exists because its absence was measured.

**The refresh is an alarm, not a timer.** A coroutine `delay` does not fire on a suspended CPU:
a refresh due at 09:51 did not happen until 10:30, twenty-seven minutes after the binding had
expired. `RefreshAlarm` asks `AlarmManager` instead, exactly when the app has been granted exact
alarms and inexactly otherwise, and the log names which. A refresh due at 12:12:11 fired at
12:12:11 inside deep Doze.

**A wake is a chance to catch up.** `WakeReceiver` listens for the screen coming on, and a line
registers then only if less than twenty minutes of grant remain. A wake that arrives early costs
nothing.

**Boot reads a cache.** The SIP credentials are sealed with the app's one Keystore key under the
`sip_lines` purpose, and written only after a successful read. Registering from them takes about
a quarter second, against sixteen to twenty-six seconds when the provider has to be asked first.
A password the provider refuses triggers one account re-read and one retry.

The battery exemption matters for all three, and Settings reports whether it is in place rather
than assuming it.

## Screens

- **Dial**: the number, which line to call from, and the call button. While a call is up this
  becomes the call screen: duration, mute, speaker, a keypad and hang up.
- **Calls**: the provider's record for today and yesterday, with a line filter and pull to
  refresh. A line under the title says whether this phone is answering.
- **Settings**: the account, the numbers this phone answers with their registration state and
  the provider's verdict, and a Battery section for the exemption.

The number list and the call list are saved on every successful read and drawn from disk before
the provider answers. Each carries the time it was read, so a screen can say how old it is, and a
screen never claims to know something it has not read.

## Build and test

```bash
cd voice-pathfinder
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 154 tests, all green
```

JDK 17. `compileSdk 37`, required by the Compose BOM. AGP 9 has Kotlin built in, so
`org.jetbrains.kotlin.android` must not be applied.

The media path is tested on the JVM against loopback sockets with a fake audio device. What
cannot be checked that way is anything the provider has an opinion about, which is why the log
matters.

## Reading the log

`adb logcat -s VoicePathfinder` prints the same lines the app keeps.

| Line | Means |
|---|---|
| `dialing N as <sub-account>` | An INVITE is going out on that line |
| `ringing (180)` | The far end is ringing; the tone you hear is generated here |
| `call from … for …` | An INVITE arrived, with the caller and the number it was for |
| `refreshed on the alarm, granted N s` | The Doze alarm fired and the binding was renewed |
| `refresh alarm set exact …` or `inexact` | Which alarm path this phone permitted |
| `N s of the grant left, so this wake needs no registration` | A wake arrived early and did nothing |
| `the socket … went quiet, so the line is registering again` | The connection died and is being rebuilt |
| `the provider says registered (TLS, host)` | The provider's own answer about the binding |
| `registering N line(s) from the saved credentials` | A cold start used the sealed cache |
| `call is up; speak` | The call connected |
| `BYE answered 481` | The provider rejected the hang-up, which should not happen |
| `hung up the incoming call` | The call ended from this side |

## Releases

The version is committed, not computed, so a commit always produces the same numbers:

```bash
scripts/version.sh --voice write     # rewrite voice-pathfinder/version.properties from the clock
scripts/version.sh --voice show      # read it
```

The scheme is the product app's, `0.<months>.<dhhmm>` with `versionCode` `months*1000000+dhhmm`,
so one script writes both version files and both are read the same way. Tags carry a `voice-`
prefix, because both apps publish from this repository and a bare `v0.1` could not be told apart
from the app's own tags.

`.github/workflows/voice-pathfinder.yml` tests, builds, and publishes a release at `voice-v<name>`
with `--latest=false`, so the repository's latest release stays the product app's. The workflow is
deleted along with this module.

## Secrets

Two secrets are stored, both sealed by one AES-GCM key held in this device's Keystore and separated
by purpose: the API password, and the cached SIP credentials. Signing out removes both. The cache
is rewritten whenever the chosen numbers change, so a number that is no longer answered does not
leave a password behind.


