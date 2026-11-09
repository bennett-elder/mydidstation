# Voice Pathfinder

A throwaway probe for **Phase 0** of [`notes/VOICE-CALL-PLAN.md`](../notes/VOICE-CALL-PLAN.md).
It answers the section 11 questions that can only be answered on a real account and a real
handset: whether media is encrypted and how, whether the server honors our codec order, and
how long a registration survives without a refresh. Answers with dates are in
`notes/STATUS.md`, and the Phase A consequences are decision D29 in `notes/DECISIONS.md`.

It is **not** the product app. There is no foreground service, no database, no
notification, no call log, and no attempt to handle a call in the background. It exists
so the SIP layer, the codecs, and the audio path can be proved before any of that is
designed into `android/`.

## What it does

1. Signs in to the VoIP.ms REST API with the account email and API password.
2. Lists the sub-accounts with `getSubAccounts`, and lets one be picked.
3. Builds SIP credentials from that sub-account: the SIP username is its `account`
   field, the password is the one the API returns, and the server comes from
   `getRegistrationStatus` (the POP another device is already using) or from the POP
   `getServersInfo` marks recommended.
4. Registers over SIP with digest authentication, over UDP or TLS, keeps the registration
   alive at 80% of whatever was granted, and reports what that was.
5. Places one call and carries G.711u audio in both directions through `AudioRecord` and
   `AudioTrack`, with echo cancellation where the device offers it, **encrypted with SRTP**
   when the account demands it, which a VoIP.ms account with Encrypted SIP Traffic on does.
6. Writes a report and a log to its own files directory, and the same text to logcat under
   the tag `VoicePathfinder`, because a secondary Android user's files cannot be read over
   adb and logcat can.

Incoming INVITEs are answered `486 Busy Here` on purpose. Seeing one is proof that the
registration is live and reachable, which is more than a status line can show.

**What a call can be asked for** is the offer mode, in section 5:

| Mode | Offers | Answers |
|---|---|---|
| `PCMU only` | `RTP/SAVP 0 101` | the control, and the mode that carries audio |
| `G.722 offered first` | `RTP/SAVP 9 0 101` | whether the server honors our codec order |
| `G.722 only` | `RTP/SAVP 9 101` | whether G.722 is reachable at all, since withholding G.711 is the only way to make it pick G.722 |
| `PCMU, unencrypted` | `RTP/AVP 0 101` | the audio path without crypto, only while encryption is off on the sub-account |

## How the credentials are handled

- **The API email and API password are the stored secret**, one blob, sealed with
  AES-256-GCM under a key held by the Android Keystore. The same `SecretBox` code the
  product app uses.
- **The sub-account's SIP password is never stored.** The API returns it in the clear,
  so this app receives it, holds it in memory for the session, and drops it with the
  process. It is not written to preferences, not put in the report, and not logged.
- **The app only resets a password when the API returns none.** VoIP.ms does return it
  today, so this path should never run. When it does, a 24-character alphanumeric
  password is generated and written back with `setSubAccount`, then read back and
  compared before it is used. A failed verification stops the flow instead of
  registering with an unknown password. The generated password is shown as "set", never
  displayed, and kept by the provider for every device that uses that sub-account.
- **The other writes are buttons, not defaults**, because they change the sub-account that
  every device on it shares: `allowed_codecs` (add `g722`, or move it first),
  `sip_traffic` (the Encrypted SIP Traffic switch), and the codec list again for the
  order. Every write sends every parameter the API marks required, built from the record
  that was just read, because sending a subset is how an update clears a setting it did
  not name. If a required value is missing from the API response, the app refuses to write
  and says which one.
- **`canada_routing` is required in practice although the API calls it optional.** Leaving
  it out makes the provider answer `invalid_canada_routing` and discard the whole request,
  so for a while every write here failed while looking like a policy decision. The field
  is now carried through and is part of the required set.
- **A write that times out is not a failed write.** Both kinds of attempt happen. The app
  reads the sub-account back three times, a second and a half apart, and reports what the
  provider actually kept, naming every field it compared.

## What is deliberately missing

| Missing | Why |
|---|---|
| G.722 decoding | Offered so the ordering question can be answered from the SDP. A call that negotiates it is silent by design. The codec itself is Phase C. |
| GSM 06.10 | Phase D. It needs a vendored native library. |
| DTMF | Phase A. Not needed for a voice-path test, and a menu that wants a keypress cannot be navigated. |
| Answered incoming calls | Phase B. Needs the `phoneCall`/`microphone` promotion and a service. |
| SRTCP | No RTCP is sent, so there is nothing to protect. Incoming RTCP is counted and set aside. |
| A store, a service, a UI worth keeping | All Phase A and later. |

## Build and install

```bash
cd voice-pathfinder
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

JDK 17. The toolchain versions are pinned to `android/build.gradle.kts` on purpose: a
finding from here has to describe the same compiler and SDK the product app uses.

`local.properties` holds the SDK path and is gitignored, as are build outputs.

## Running it

1. Enter the portal email and an API password, then **Save**. Create an API password in
   the portal if there is not one; the portal login password is not it.
2. **Load sub-accounts**, pick one. **Prepare**, which is the first button in section 3,
   reads the password and resolves a server.
3. **Register**. The log shows `REGISTER`, the `401` challenge, and the `200 OK` with the
   granted expiry. With Encrypted SIP Traffic on, use TLS on 5061: plain UDP will not do.
4. Dial a number you control, or `4443` for VoIP.ms's echo test, and speak. Then read the
   answers block rather than listening for the codec experiments, which are silent.
5. **Copy report**, or read logcat: `adb logcat -s VoicePathfinder`.

A useful extra test that costs nothing: call the DID of the sub-account from another
phone while the app is registered. The app should reply `486` and the incoming counter
should go to one. That proves reachability, and it is the first half of Phase B.

**Measuring the registration lapse** takes about two minutes rather than an hour, because
the REGISTER asks for a short expiry and the registrar grants the lesser of that and its
own ceiling: press **Register for 60 s**, then **Stop refreshing and watch**. The log
reports how far past the granted expiry the provider dropped the binding, which on this
account was zero.

## What to watch for

- The `INVITE to 200 OK` line includes the digest challenge round trip, so it is longer
  than a bare call setup.
- A granted expiry very different from the sub-account's `max_expiry` is the number behind
  open question 3. The refresh interval shown is 80% of what was granted.
- Audio in one direction only usually means a NAT or SDP address problem. The log names
  the local interface and the RTP port that were chosen.
- `rtp datagrams: 0` with packets sent means the far end never sent media at all, which
  says nothing about our encryption. Datagrams above zero with frames received at zero
  means we are refusing its packets, and `srtp rejected` counts them.
