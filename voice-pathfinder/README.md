# Voice Pathfinder

A SIP dialer for VoIP.ms, in its own app beside the product app. It is Phase A of
`notes/VOICE-CALL-PLAN.md`: a phone that places calls from a
number you choose, carries the audio encrypted, and keeps a history of what the provider
recorded.

It is not the product app. `android/` is untouched, the `applicationId` is its own, and nothing
here is published. Answers with dates live in `notes/STATUS.md`, and the decisions this code
encodes are D31 to D36 in `notes/DECISIONS.md`.

## What works, on a handset

- **Several numbers at once.** Each chosen number routes to a sub-account, and each sub-account
  gets a SIP registration over TLS, kept alive at 80% of whatever the registrar granted.
- **Outbound calls from a chosen number.** The dialer has a line picker, and the call presents
  that sub-account, which is what the far end sees.
- **Encrypted media.** SRTP with SDES keys when the account has Encrypted SIP Traffic on,
  negotiated from the SDP in both directions, G.711u in and out.
- **Two-way audio** through `AudioRecord` and `AudioTrack`, with echo cancellation where the
  device offers it and a switch between speaker and earpiece.
- **DTMF**, sent as RFC 4733 events and also played locally, so a press is audible before an
  IVR confirms it.
- **Hang up**, with a BYE the provider accepts and answers.
- **Call history**, read from `getCDR`, filterable by line, with each call folded from the
  provider's per-leg rows and inbound calls shown by caller rather than by your own number.

## What does not work yet

- **Incoming calls.** A registration is live and reachable, but a `Line` drops an INVITE. The
  app cannot ring, answer or decline.
- **Android call integration.** No `PhoneAccount`, no `ConnectionService`, so no audio focus,
  no Bluetooth routing and no lock-screen answering. That is the fork in section 7 of the plan
  and the next decision.
- **Early media.** A `183 Session Progress` carrying SDP is logged and ignored. While the far
  end rings, the app plays a locally generated ringback tone instead of what the network sends.
- **G.722 and GSM.** G.711u only.

## How it is put together

| File | What it owns |
|---|---|
| `Line.kt` | One sub-account's registration: socket, binding, refresh timer. No media. |
| `Call.kt` | One outbound call: its own SIP transport, the digest challenge, ACK, CANCEL, BYE, and one `MediaSession`. |
| `MediaSession.kt` | A call's socket, SRTP, jitter buffer, both audio directions and counters. No SIP types. |
| `CallAudio.kt` | `AudioRecord` and `AudioTrack`, behind `CallAudioPort` so tests can replace it. |
| `SipMessage`, `Sdp`, `Rtp`, `Srtp`, `G711`, `Dtmf`, `Ringback` | Parsing and codecs. Pure Kotlin, JVM tested. |
| `Pathfinder.kt` | The account, the saved lists, the chosen numbers, readiness, and no media at all. |
| `PathfinderService.kt` | A `specialUse` foreground service that holds the registration when the screen is gone, re-reads the account every 15 minutes, and reconciles the lines when a number's routing moves. |

A call opens its own transport rather than borrowing a line's, because a line's socket carries
its registration and a call that closed it would take the registration down too.

## Screens

- **Dial**: the number, a menu for which number to call from, and the call button. While a call
  is up this becomes the call screen: duration, mute, speaker, a keypad, and hang up.
- **Calls**: the provider's record for today and yesterday, with a line filter and pull to
  refresh. Tapping a row fills the dialer with that number and that line and places nothing.
- **Settings**: the account, the numbers this phone answers, and the line selection.

The numbers and the call list are both saved on every successful read and drawn from disk
before the provider answers. Each carries the time it was read, so a screen can say how old it
is, and a screen never claims to know something it has not read.

## Build and test

```bash
cd voice-pathfinder
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 133 tests, all green
```

JDK 17. `compileSdk 37`, required by the Compose BOM. AGP 9 has Kotlin built in, so
`org.jetbrains.kotlin.android` must not be applied.

The media path is tested on the JVM against loopback sockets with a fake audio device, so a
change to RTP, SRTP or the audio loops can be checked without a phone. What cannot be checked
that way is anything the provider has an opinion about, which is why the log matters.

## Reading the log

`adb logcat -s VoicePathfinder` prints the same lines the app keeps. The ones worth knowing:

| Line | Means |
|---|---|
| `dialing N as <sub-account>` | An INVITE is going out on that line |
| `ringing (180)` or `(183)` | The far end is ringing; the tone you hear is generated here |
| `SRTP ... both keys from SDP` | Media is encrypted and the keys exchanged |
| `call is up; speak` | The call connected |
| `media totals: N sent, N received, ... 0 srtp rejected` | What actually flowed, logged when the call ends |
| `BYE answered 481` | The provider rejected the hang-up, which should not happen |
| `hung up` | The call ended |

## Secrets

The API password is the only stored secret, sealed with a key this device's Keystore holds. The
sub-account's SIP password is never written down: it is fetched when a line starts and used to
build the credentials for that registration.
