# My DID Station, the app

Kotlin and Compose. Polling only, one language, no relay required (design
decisions D4 and D12). Package `com.willingtocode.mydidstation`, base domain
`willingtocode.com` (D19).

Design decisions D1 through D23 live in `notes/DECISIONS.md`, which is kept out
of version control on purpose. See `.gitignore`.

## Layout

```
app/build.gradle.kts        the module: compileSdk 37, minSdk 24, Compose BOM 2026.09.00
app/src/main/java/com/willingtocode/mydidstation/
  App.kt          application and container (settings, store, api, notifier, sync)
  MainActivity.kt single activity; also reads the intent a notification tap carries
  Store.kt        raw SQLite, schema v5, forward-only migrations, queries
  VoipMs.kt       the one REST endpoint: getDIDsInfo, getMMS, sendSMS
  Sync.kt         polling engine: per-number window, 24 h overlap, load-more, upsert
  SyncWorker.kt   WorkManager, 15 or 30 or 60 minutes
  Notify.kt       one notification per conversation, and the "do not buzz the open thread" rule
  Sms.kt          GSM-7 and UCS-2 segmentation with word-safe split points
  Settings.kt     EncryptedSharedPreferences with a Keystore-backed key
  Ui.kt           theme, nav, all four screens
app/src/test/java/.../SmsTest.kt   8 tests for the segmentation math
app/src/main/res/mipmap-*/  launcher icon: adaptive XML plus legacy PNGs
app/src/main/res/drawable/ic_stat_message.xml  status bar icon for notifications
art/ic_launcher.py          renders those PNGs; the icon source, not a build step
```

## Build and install

```bash
cd ~/mydidstation/android
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # segmentation tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Against the mock instead of VoIP.ms, run `python3 tools/mock_voipms.py`, then
long-press the "Sync" heading in Settings and enter
`http://10.0.2.2:8765/rest.php`. Cleartext is allowed for `10.0.2.2`, `localhost`
and `127.0.0.1` only, in `res/xml/network_security_config.xml`.

## Icon

`art/ic_launcher.py` is the source for every launcher PNG. It is not part of the
build. The committed PNGs are what Gradle packs.

```bash
python3 android/art/ic_launcher.py    # rewrites android/app/src/main/res/mipmap-*
```

The beams, the palette and the scrim are constants at the top of that file. It
needs Pillow and a heavy sans font, and it prints the face it found.

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
4. Opening a number **enrols it and fills the last 30 days** with that history
   marked read. Pull-to-refresh inside a number syncs that DID alone; the pull on
   the numbers list also runs discovery and then every enrolled number.
5. The composer shows units and budget, part count, the split points, `Unicode`
   when UCS-2 applies, and a warning when one word is so long it must be cut.
6. A sent message shows its carrier status, in red when it did not arrive. The
   bubble exists before the request does, and disappears again if it fails.
7. A notification never fires for the conversation on screen. It carries the
   contact and no conversation id, so the thread resolves the id itself.

## Not implemented yet

MMS rendering (URLs only), push/UnifiedPush, contact names, tapping a link inside
a message, copying a message, and selecting part of one. `notes/STATUS.md` has
the verified details and `notes/BACKLOG.md` the ranked work, neither of which is
part of this repo.
