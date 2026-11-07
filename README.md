# My DID Station

SMS and MMS for [VoIP.ms](https://voip.ms), built for people who run more than
one number.

> Working, and used daily on a real phone. Sending, threaded history, delivery
> status, notifications and configurable polling all work. MMS is stored and
> linked but not yet rendered. No F-Droid listing yet; every push publishes a
> signed APK as a prerelease.

Not affiliated with or endorsed by VoIP.ms. This is a third-party client that
talks to their public API using credentials you supply.

## Why it exists

Most VoIP.ms clients put every number into one conversation list and treat the
destination number as a detail. This app starts from the number instead. The home
screen lists your DIDs with their message and unread counts. Open one and you get
its conversations, then a thread with the composer at the bottom.

Nothing is fetched for a number until you open it, so a first launch costs a
single request. Opening fills the last 30 days and marks that history read. After
that the poll fetches a window starting a day before the last one ended, which
catches late arrivals without re-reading the month. A load-more control pulls 30
days per tap down to a two-year floor.

## What it does

- **Numbers first.** One row per DID with message and unread counts. Numbers the
  account cannot text are hidden, because the API reports `sms_enabled` and there
  is no point listing a number that cannot receive anything.
- **Optimistic sending.** The bubble appears before the request goes out, is
  confirmed by the id the API returns, and is removed with the draft restored if
  the send fails.
- **Delivery status** from the carrier, shown on sent messages and in red when
  undelivered or failed.
- **Notifications.** One per conversation with the newest text. The thread you
  are reading never notifies, and opening a thread clears its notification.
- **A status strip** on both lists, showing how long the last refresh took and how
  long until the next background run.
- **Cadence you choose.** 30 s, 60 s, 2 minutes or off while a number is open, and
  15, 30 or 60 minutes in the background.
- **Credentials** in Keystore-backed encrypted preferences. The database is local
  SQLite and is never synced anywhere.

## What it does not do yet

Said plainly, because you will hit these:

- **MMS is not rendered.** A photo arrives as `[attachment] https://voip.ms/media.php?map=…`.
  The link is real and the file is on VoIP.ms, but the app never fetches it.
  Sending media is not built either.
- **Nothing inside a message reacts to touch.** Links do not open, a message
  cannot be copied, and part of one cannot be selected. This is the most obvious
  gap in daily use.
- **Numbers, not names.** There is no contact lookup or avatar.
- **No push.** Latency is bounded by the poll interval, which is between 15 and 60
  minutes in the background.

`notes/BACKLOG.md` in a working copy ranks these by value per hour, with the
implementation notes for each.

## Layout

| Path | What |
|---|---|
| `android/` | Android app, Kotlin and Compose. Gradle root, module in `android/app/` |
| `tools/mock_voipms.py` | A stand-in REST endpoint, for testing without touching real messages |
| `relay/` | Optional push relay, four implementations of one contract. Not wired to the app |
| `bench/` | Cost and latency bench for those implementations |
| `deploy/` | Cloud-init, Caddy and quadlet for self-hosting the relay. Not written yet |

## Build and test

```bash
cd android
./gradlew :app:assembleDebug        # android/app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # unit tests, Robolectric included
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17. Compose BOM 2026.09.00 forces `compileSdk 37`. AGP 9 carries
Kotlin, so `org.jetbrains.kotlin.android` must not be applied.

The tests cover SMS segmentation, link matching, time formatting, the VoIP.ms
request and response shapes, and the store. `notes/STATUS.md` lists the gaps.

### Testing without the real API

`tools/mock_voipms.py` answers `getDIDsInfo`, `getMMS`, `getSMS` and `sendSMS`
with the shapes the real API uses, logs every call with a timestamp, and can be
told to fail, delay or accept an injected inbound message. Point the app at it by
long-pressing the "Sync" heading in Settings and entering
`http://10.0.2.2:8765/rest.php` on an emulator. Clear the field to go back to
VoIP.ms. The app bar reads "not the real API" while an override is active.

## Releases

Every push builds a signed APK and publishes it as a prerelease.

- **Obtainium:** add this repository as an app source and allow prereleases. It
  offers each new build, which is how the app gets onto a phone.
- **By hand:** download the APK from the latest release.

The version lives in `android/version.properties`, which is committed. Nothing
computes a version during a build, so a commit always produces the same numbers:
`0.11.71650` / `11071650` at the time of writing, meaning months since December
2025, then day and time. `scripts/version.sh write` rewrites that file from the
clock and refuses to run under CI, so the file only ever changes when a person
commits it.

Bump it and commit **before** pushing. The release tag comes from the version, so
a push without a bump reuses the existing tag, and that tag stays pinned to the
commit that created it.

APKs are signed with a key kept in repository secrets. With no secrets the build
is debug-signed and its filename ends in `-debugsigned`. A debug-signed install
cannot be upgraded by a signed one later, so add the secrets before entering real
credentials. `notes/DECISIONS.md` D22 has the detail.

## Polling, not push

The app works with no relay and no distributor installed. It polls VoIP.ms once
per number per tick, over a window with a fixed overlap so nothing is missed at
the boundary. Push is optional and would only improve latency. Credentials live
in Keystore-backed encrypted preferences. The database is local SQLite and is
never synced anywhere.

## License

MIT. See [LICENSE](LICENSE). Everything here is new code. The prior art we
studied is Apache-2.0 and it told us what to avoid, not what to copy, so no
upstream obligation attaches. F-Droid takes `MIT` as the SPDX identifier.

## Acknowledgments

Prior art: [`voipms-sms-client`](https://github.com/michaelkourlas/voipms-sms-client)
by Michael Kourlas, and [`voipms-mms-android-client`](https://github.com/VerbalP/voipms-mms-android-client)
by VerbalP. Both map this API far enough to show where its sharp edges are. No
code was taken from either, and the design diverges where they struggle. The API
reference used during development is VoIP.ms's own documentation and is not
redistributed here.
