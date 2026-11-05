# My DID Station

SMS and MMS for [VoIP.ms](https://voip.ms), built for people who run more than
one number.

> Early development. Sending SMS, per-number polling and threaded history work.
> MMS is stored and linked but not yet rendered. There are no notifications yet.
> No tags, no APKs, no F-Droid listing.

Not affiliated with or endorsed by VoIP.ms. This is a third-party client that
talks to their public API using credentials you supply.

## Why it exists

Most VoIP.ms clients put every number into one conversation list and treat the
destination number as a detail. This app starts from the number instead. The
home screen lists your DIDs with their message and unread counts. Open one and
you get its conversations, a composer, and a pull-to-refresh that syncs that DID
alone.

## Layout

| Path | What |
|---|---|
| `android/` | Android app, Kotlin and Compose. Gradle root, module in `android/app/` |
| `relay/` | Optional push relay, four implementations of one contract. Not published here yet |
| `bench/` | Cost and latency bench for those implementations. Not published here yet |
| `deploy/` | Cloud-init, Caddy and quadlet for self-hosting the relay. Not written yet |

## Build and test

```bash
cd android
./gradlew :app:assembleDebug        # android/app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # SMS segmentation tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17. Compose BOM 2026.09.00 forces `compileSdk 37`.

## Releases

Every push builds an APK and publishes it as a prerelease.

- **Obtainium:** add this repository as an app source and allow prereleases. It
  offers each new build, which is how the app gets onto a phone.
- **By hand:** download the APK from the latest release.

The version lives in `android/version.properties`, which is committed. Nothing
computes a version during a build, so a commit always produces the same numbers:
`0.11.51514` / `11051514` here, meaning months since December 2025, then day and
time. `scripts/version.sh write` rewrites that file from the clock and refuses to
run under CI, so the file only ever changes when a person commits it.

APKs are signed with a key kept in repository secrets. With no secrets the build
is debug-signed and its filename ends in `-debugsigned`. A debug-signed install
cannot be upgraded by a signed one later, so add the secrets before entering real
credentials. `notes/DECISIONS.md` D22 has the detail.

## Polling, not push

The app works with no relay and no distributor installed. It polls VoIP.ms per
DID on an incremental cursor. Push is optional and only improves latency.
Credentials live in Keystore-backed encrypted preferences. The database is local
SQLite and is never synced anywhere.

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
