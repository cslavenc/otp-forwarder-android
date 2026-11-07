# CLAUDE.md

Speak efficiently in user-facing messages to save tokens, but use clear, standard English for planning, code, structured
data, tool calls, and any other internal or technical outputs

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**OTP Forwarder Android** — a small Android app that picks up one-time passwords from incoming **SMS**, encrypts them,
and forwards them to the user's Windows PC, where the companion desktop app **auto-types them into the focused
application** (Citrix Workspace, browser login pages, etc.). It is a personal tool: favor simplicity and security over
features and scale.

```
SMS ──▶ this Android app ──AES-GCM encrypted──▶ ntfy.sh relay ──▶ desktop app ──SendInput──▶ focused app (Citrix, …)
```

- The **desktop app** (Tauri v2 / Rust + Svelte) lives in a separate repository,
  [`otp-forwarder-desktop`](https://github.com/cslavenc/otp-forwarder-desktop). It is implemented (v0.1) and owns the
  wire contract.
- The relay only ever sees ciphertext. The shared key is exchanged once: the desktop shows a QR code, this app scans it.
- Once paired, the app works with no UI: an SMS from an allowed sender is forwarded in the background.
- Distributed as a **sideloaded APK** (personal use). Google Play does not allow `RECEIVE_SMS` for this use case.

> Status: not implemented yet. Build the structure below.

## Ponytail (always on)

All work in this repo — planning, implementation, tests, and reviews, including every subagent — follows the
**ponytail** skill at level **full** ([`.claude/skills/ponytail/SKILL.md`](.claude/skills/ponytail/SKILL.md)): the
smallest change that fully solves the task, platform APIs before libraries, no speculative abstractions, and never cut
validation at trust boundaries, security, or error handling that prevents data loss. Subagents: read that file before
starting. End user-facing replies with what was skipped or not checked.

## Tech & patterns (quick reference)

| Area             | Choice                                                                                          |
|------------------|-------------------------------------------------------------------------------------------------|
| Language         | **Kotlin** 2.x, JVM target 17                                                                    |
| UI               | **Jetpack Compose** + **Material 3**, single `MainActivity`, no navigation library               |
| Min / target SDK | **minSdk 26** (Android 8.0); compile/target the latest stable SDK (35+)                          |
| Build            | Gradle (Kotlin DSL) + version catalog `gradle/libs.versions.toml`, single `app` module, wrapper committed |
| SMS input        | Manifest `BroadcastReceiver` for `SMS_RECEIVED` + `RECEIVE_SMS` permission (wakes the app, no foreground service) |
| Delivery         | **WorkManager** expedited one-time work, network constraint, retries until the 2-minute freshness window ends |
| HTTP             | Platform `HttpURLConnection` (no OkHttp/Retrofit)                                                 |
| Encryption       | Platform `javax.crypto` **AES/GCM/NoPadding**, 256-bit key, random 96-bit nonce per message      |
| Secret storage   | Key imported into **Android Keystore** (non-exportable); topic/relay/settings in `SharedPreferences` |
| QR scanning      | **Google code scanner** (`play-services-code-scanner`): no camera permission, no camera code      |
| JSON             | Platform `org.json` (tests add the `org.json:json` artifact, because `android.jar` stubs it)     |
| Tests            | JUnit 4 local JVM tests for `core/`; no instrumented/UI tests yet                                |
| Not in scope     | Play Store release, notification-listener OTPs, multiple desktops, own backend, LAN-direct delivery |

Patterns:

- **Business logic is pure Kotlin** in `core/` (crypto, pairing parsing, OTP extraction, sender filter, payload) with
  no `android.*` imports except `org.json`, so it is unit-testable on the JVM. Android glue (receiver, worker, stores,
  UI) stays thin.
- **Dependencies stay minimal**: prefer the platform (`javax.crypto`, `java.util.Base64`, `HttpURLConnection`,
  `org.json`, `SharedPreferences`) over libraries. Every new dependency needs **senior-architect** sign-off.
- **Least privilege**: only `RECEIVE_SMS`, `INTERNET`, `POST_NOTIFICATIONS` (if failures are notified). No
  `READ_SMS`, no camera, no contacts. `android:allowBackup="false"`; exported components only where Android requires
  it (the SMS receiver, protected by `android.permission.BROADCAST_SMS`).
- **UI is presentation only**: pairing, status/recent forwards, settings. All security decisions live in `core/` and
  the receiver/worker.

## Subagent workflow

For any non-trivial task, drive the work through subagents in this order. Steps in _(parentheses)_ are conditional.
**Feedback always loops back to the specific agent that owns the thing being changed and is never patched inline by
whichever agent happens to be running.** If sparring-partner (or anyone else) finds a gap in the plan or design,
re-invoke **planner** (and **senior-architect** too, if the gap is architectural) with that feedback — don't silently
work around it. If a reviewer finds an implementation problem, re-invoke **senior-software-engineer**; if it finds a
test-coverage gap, re-invoke **senior-test-engineer**. Keep looping a step until its reviewer/critic is satisfied before
moving on.

```
planner
  → (senior-architect)         ── fold its design back into the plan
  → sparring-partner           ── stress-test the plan
  → planner / senior-architect ── revise the plan/design if critique found gaps
  → senior-software-engineer   ── implement
  → code-reviewer              ── review the implementation
  → senior-software-engineer   ── address findings (loop until clean)
  → senior-test-engineer       ── add / strengthen tests
  → code-reviewer              ── 2nd pass: are any test types missing?
  → senior-test-engineer       ── address findings (loop until clean)
  → (final-code-reviewer)      ── last gate for high-risk changes
```

1. **planner** — turn the task into a concrete, sequenced plan.
2. **senior-architect** _(if it crosses package boundaries, touches the wire/pairing contract, adds a dependency or
   permission, or has competing designs)_ — feed its output back into the plan.
3. **sparring-partner** — challenge assumptions, edge cases, failure modes, second-order effects, simpler alternatives.
4. **planner and/or senior-architect** _(whenever sparring surfaced gaps)_ — revise the plan/design; re-run
   sparring-partner if the plan is substantially different.
5. **senior-software-engineer** — implement the agreed plan.
6. **code-reviewer** — correctness, security, reliability, design.
7. **senior-software-engineer** _(whenever code-reviewer found issues)_ — address the findings; re-run code-reviewer if
   the fix is substantial.
8. **senior-test-engineer** — cover the new/changed code.
9. **code-reviewer** — second pass focused on test coverage: are unit / integration / edge-case / regression tests
   missing?
10. **senior-test-engineer** _(whenever this pass found gaps)_ — address the findings.
11. **final-code-reviewer** _(high-risk changes only: crypto/key handling, pairing, the wire format, SMS sender
    filtering/OTP extraction, manifest permissions or exported components, new dependencies, or large diffs)_.

## Implementing many user stories at once

When you are provided with many user stories, always do the following for the subagent workflow when creating plans and
implementations:

1. First, look at all user stories and ask any relevant questions you might have to improve the final plan
2. Then, create an overview of all user stories to implement so that progress can be tracked
3. Then, create a plan for the first user story doing the usual subagent workflow
4. Once the plan is fully ready, start the implementation of the first user story and at the same time, begin planning
   the next user story. You can plan two new user stories at most.
5. Do not implement different user stories at the same time! This can lead to editing the same files and code conflicts!
   Instead, once the plans of the next user stories are fully ready, wait until the first user story is fully
   implemented before starting the implementation of the next user story.
6. When the next user story is being implemented, you can start planning the user story after that
7. After the current user story has finished the engineering and review part and continues to the test part, you can
   start implementing the next user story already, since no changes to the business logic are expected at that point.
8. Once a user story is fully implemented, create a short and precise commit (see git log for examples) and push it

## Repository shape (target)

```
otp-forwarder-android/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml         permissions, SmsReceiver, MainActivity
│       │   ├── java/ch/cslavenc/otpforwarder/
│       │   │   ├── MainActivity.kt         Compose host
│       │   │   ├── core/                   pure Kotlin, JVM-testable
│       │   │   │   ├── Crypto.kt           AES-256-GCM seal, base64url
│       │   │   │   ├── Pairing.kt          parse + validate the pairing QR JSON
│       │   │   │   ├── OtpExtractor.kt     find the code in an SMS body
│       │   │   │   ├── SenderFilter.kt     allowed-sender matching
│       │   │   │   └── Payload.kt          plaintext JSON {v, id, code, sender, ts}
│       │   │   ├── data/                   PairingStore (Keystore + prefs), SettingsStore, ForwardLog
│       │   │   ├── sms/SmsReceiver.kt      SMS_RECEIVED → filter → extract → encrypt → enqueue
│       │   │   ├── relay/ForwardWorker.kt  WorkManager: POST identical body, retry within the window
│       │   │   └── ui/                     Compose screens: Pairing, Status, Settings
│       │   └── res/
│       └── test/                           JVM unit tests for core/ (incl. desktop test vector)
├── gradle/libs.versions.toml
├── docs/wire-contract.md                   mirror of the desktop contract (desktop copy is authoritative)
├── .claude/agents/                         subagent definitions
├── .claude/skills/ponytail/                ponytail skill (always on)
├── build.gradle.kts, settings.gradle.kts, gradle.properties
└── gradlew, gradlew.bat, gradle/wrapper/
```

## Commands

| Task                         | Command                                                              |
|------------------------------|----------------------------------------------------------------------|
| Build debug APK              | `.\gradlew.bat assembleDebug` → `app\build\outputs\apk\debug\app-debug.apk` |
| Unit tests                   | `.\gradlew.bat testDebugUnitTest`                                    |
| Lint (must pass)             | `.\gradlew.bat lintDebug`                                            |
| All checks                   | `.\gradlew.bat check`                                                |
| Install on a device/emulator | `adb install -r app\build\outputs\apk\debug\app-debug.apk`           |
| Fake an SMS (emulator only)  | `adb emu sms send +41000000000 "Your code is 123456"`                |

Prerequisites: JDK 17 (Android Studio's bundled JBR works), Android SDK (platform + build-tools for the compile SDK,
platform-tools for `adb`) with `ANDROID_HOME` or `local.properties` `sdk.dir` set. The desktop app is needed for
end-to-end tests; its "Pairing" tab shows the QR code to scan.

## Commit message convention

Write commit messages in **imperative present tense**, short and to the point.
Start with a capital letter, no period at the end.

Examples:

- ✅ `Add SMS receiver`
- ✅ `Fix nonce reuse in AES-GCM encryption`
- ✅ `Move OTP extraction into core`
- ✅ `Update libs.versions.toml with WorkManager`
- ❌ `added sms receiver` (lowercase)
- ❌ `Adds SMS receiver.` (wrong tense, period)
- ❌ `chore: Add SMS receiver` (prefix tag not used)

This keeps the git log readable and uniform when many agents touch the repo.

## Code conventions

- Kotlin official code style (`kotlin.code.style=official`); Kotlin compiler warnings are errors
  (`allWarningsAsErrors`); Android lint must pass with no errors or warnings except intentionally disabled
  "newer version available" checks.
- No `!!` outside tests; model failures with sealed types or `Result` in `core/`, and map them to UI text at the edge.
- Constants over magic values (freshness window, retry deadline, limits); keep them next to the code that owns them.
- Coroutines only where Android hands you a lifecycle (Compose, `CoroutineWorker`); `BroadcastReceiver.onReceive`
  does only quick, synchronous work (filter, extract, encrypt, enqueue) and never network I/O.
- Secrets: the key lives in the Android Keystore and is never exported, logged, or shown after pairing. OTP codes are
  never logged (`Log.*`), never put in notifications or exception messages, and never written to disk in plaintext.
  The forward log stores sender, time and status only.
- WorkManager input data holds the **ciphertext body** only, never the plaintext code.

### Architecture & conventions

- Data flow: `SmsReceiver` gets the PDUs → joins multipart bodies per sender → `SenderFilter` (drop unless allowed) →
  `OtpExtractor` (drop if no code) → `Payload` builds JSON with a fresh id and `ts` = receipt time → `Crypto` seals it
  with the Keystore key → enqueue `ForwardWorker` with the body → worker POSTs `{relay}/{topic}` with `X-Firebase: no`,
  retrying the identical body on network errors/429/5xx until `ts` + ~2 min, then gives up → `ForwardLog` records the
  outcome.
- Only forward SMS from senders the user explicitly allowed. An empty allowlist forwards nothing.
- Pairing: scanning the desktop QR validates the JSON per the contract, imports the key into the Keystore, and stores
  relay and topic. Re-scanning replaces the old pairing; "Unpair" deletes it.
- Known Android limitations: OEM battery savers (Xiaomi, Huawei, Samsung, …) can delay or kill background work; the
  app should link to battery-optimization settings. Recent Android versions may require "Allow restricted settings" in
  App info before a sideloaded app can be granted the SMS permission.

## Cross-cutting contract (desktop ↔ Android)

Wire contract version 1 — the authoritative spec is `docs/wire-contract.md` **in the desktop repo**; this repo keeps
a mirror at [`docs/wire-contract.md`](docs/wire-contract.md) (limits, test vector, threat model). Any change goes
through **senior-architect**, must land in both repos and bump `v`.

- **Pairing QR payload** (JSON, shown by desktop, scanned by phone):
  `{ "v": 1, "relay": "https://ntfy.sh", "topic": "<random 128-bit, base64url>", "key": "<32 bytes, base64url>" }`
- **Message** (published by phone to `POST {relay}/{topic}`): body is `base64url(nonce[12] || ciphertext || tag)`,
  AES-256-GCM, AAD = `"otpfwd/v1/" + topic`, header `X-Firebase: no`.
- **Plaintext** (JSON): `{ "v": 1, "id": "<16–64 [A-Za-z0-9_-]>", "code": "<4–10 alphanumerics>", "sender": "+41…", "ts": <unix ms> }`
- The desktop rejects messages that fail decryption, have an unknown `v`, are older than 2 minutes (or > 30 s in the
  future), or reuse an `id` seen in the last 10 minutes. Retries must resend the identical body (same id).
- The topic name is a secret-ish identifier, not a security boundary; confidentiality and authenticity come from
  AES-GCM.
