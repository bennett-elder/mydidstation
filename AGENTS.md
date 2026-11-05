# Working agreement

Conventions for this repo, in a form that both people and coding agents can
follow. `AGENTS.md` is read automatically by Cline, Cursor, Codex and similar
tools, and it is plain Markdown for everyone else.

`notes/` holds the reasoning and is deliberately not committed:
`notes/DECISIONS.md` (why the system is shaped this way) and `notes/STATUS.md`
(what exists right now, verified vs assumed).

## Commits

**Under 72 characters, one line.** No body, no trailers, no `Co-Authored-By`.
History is read as a changelog.

## Language and prose

- **American English**, in code, comments and prose: `license` not `licence`,
  `defense`, `behavior`, `analyze`, `canceled`, `artifact`, `acknowledgment`.
- **Plain prose.** No semicolon-joined clauses, no em-dash chains, no "not X, but
  Y" rhetorical shapes. One idea per sentence.

## Never

- **No secrets in the repo.** No keystores, no API passwords, no tokens.
- **No third-party text redistributed.** The VoIP.ms API reference stays in
  `notes/` because that text is theirs.
- **No code copied from the Apache-2.0 prior art.** `voipms-sms-client` and
  `voipms-mms-android-client` were studied for pitfalls only. This repo is MIT and
  has to stay cleanly ours.

## Build and test

```bash
cd android
./gradlew :app:assembleDebug        # android/app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest
```

JDK 17. `compileSdk 37` is required by Compose BOM 2026.09.00. AGP 9 has Kotlin
built in, so `org.jetbrains.kotlin.android` must not be applied. Run tests before
saying a change works, and say what was verified versus what was assumed.
