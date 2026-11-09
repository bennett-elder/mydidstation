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

## Reporting

How a session talks to the person running it. What these prevent is a plan or a finding that
scrolls away under tool output before it can be read.

- **Say what the tools are for, then run them.** One line before the call about what it is
  about to check and why, so the output has a question behind it.
- **The durable content goes last.** Work and tool output first, the summary after, so the last
  thing on screen is the thing to read. Never open a turn with a plan and then bury it.
- **Tool output is a verdict, not a transcript.** A build result, a test count, a diff stat.
  Full logs go to a file and stay there unless something needs reading.
- **One turn, one question.** Asked for a plan, the plan is the answer and the turn ends. Act
  on "continue", not alongside "what are the options".

## Permission

Discussion is not approval. Planning, explaining, answering a question, or agreeing a
plan down to the last detail changes nothing on disk, in git, or on GitHub.

Act only on an explicit instruction in the current message. These words mean exactly
this and nothing else:

- **"ship it"** means commit the change under discussion, bump the version with
  `scripts/version.sh write`, update the version line in `README.md`, and push. It is
  the whole chain. No other phrase runs it, and it covers nothing that is not part of
  that chain, such as a release note, a repository description or a topic.
- **"commit"** means commit, and stop there.
- **"bump"** means rewrite `android/version.properties` and nothing else.
- **"push"** means push what is already committed.
- **"continue"**, **"go on"**, **"carry on"**, or silence mean keep working on what we
  were already doing. They never mean publish.

A question is a question however rhetorical it reads. A sentence ending in "right?" or
"should we?" is not an instruction. Neither is my own judgment that a piece of work is
finished.

Everything that leaves the machine needs its own instruction, named: commit, push,
release, repository description, topics, settings. If it is not clear that the message
asked for one of those, say which command you would run and what it would publish, then
wait. Do not run it to find out.

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
