# My DID Station, the app

Kotlin and Compose. Polling only, one language, no relay required (design
decisions D4 and D12). Package `com.willingtocode.mydidstation`, base domain
`willingtocode.com` (D19).

Design decisions D1 through D21 live in `notes/DECISIONS.md`, which is kept out
of version control on purpose. See `.gitignore`.

## Layout

```
app/build.gradle.kts        the module: compileSdk 37, minSdk 24, Compose BOM 2026.09.00
app/src/main/java/com/willingtocode/mydidstation/
  App.kt          application and container (settings, store, api, sync)
  MainActivity.kt single activity, sets Compose content
  Store.kt        raw SQLite, schema, forward-only migrations, queries
  VoipMs.kt       the one REST endpoint: getDIDsInfo, getMMS, sendSMS
  Sync.kt         polling engine: per-DID cursor, 24 h overlap, upsert
  SyncWorker.kt   WorkManager, every 15 minutes
  Sms.kt          GSM-7 and UCS-2 segmentation with word-safe split points
  Settings.kt     EncryptedSharedPreferences with a Keystore-backed key
  Ui.kt           theme, nav, all four screens
app/src/test/java/.../SmsTest.kt   8 tests for the segmentation math
```

## Build and install

```bash
cd ~/mydidstation/android
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # segmentation tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Build facts that cost time to discover

- **AGP 9 has Kotlin built in.** Do **not** apply `org.jetbrains.kotlin.android`.
  AGP rejects that combination with "Failed to apply plugin". Only the Compose
  compiler plugin is applied (`org.jetbrains.kotlin.plugin.compose`, 2.3.10).
- **Compose BOM 2026.09.00 needs compileSdk 37, not 36.** With 36 the build fails
  in `checkDebugAarMetadata`.
- Toolchain that works here: JDK 17, Gradle wrapper 9.8.2, AGP 9.5.0, Kotlin 2.3.10.
- Emulator AVDs on this machine: `visual_pixel7`, `test_device`.

## UI rules (deliberate, do not "simplify" these away)

1. The top level is **numbers, not conversations**. Each row shows message count
   and unread count. You are always in the context of one DID.
2. Compose appears **only inside a number** (FAB), because you are sending *from*
   that number.
3. **The composer is part of the thread.** No dialog and no separate compose
   screen. You type in a box at the bottom while earlier messages stay visible
   above. A new message is the same screen with an empty thread and a "To" field.
4. Pull-to-refresh inside a number syncs **only that DID**.
5. The composer shows units and budget, part count, the split points, `Unicode`
   when UCS-2 applies, and a warning when one word is so long it must be cut.

## Not implemented yet

MMS rendering (URLs only), notifications, push/UnifiedPush, contacts, optimistic
send. See the local `notes/STATUS.md` (not part of this repo).
