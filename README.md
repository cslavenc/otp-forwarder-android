# OTP Forwarder Android

Android companion app for [OTP Forwarder Desktop](https://github.com/cslavenc/otp-forwarder-desktop): forwards SMS
one-time passwords, end-to-end encrypted (AES-256-GCM) via the ntfy.sh relay, to your Windows PC, which types them into
the focused app.

```
SMS ──▶ this app ──AES-GCM encrypted──▶ ntfy.sh relay ──▶ desktop app ──▶ focused app (Citrix, …)
```

> **Status:** not implemented yet. The wire contract (pairing QR, message format, test vector) is mirrored in
> [`docs/wire-contract.md`](docs/wire-contract.md); the desktop repo holds the authoritative copy.

## Planned stack

Kotlin, Jetpack Compose (Material 3), minSdk 26 (Android 8.0), Gradle Kotlin DSL. SMS via a `SMS_RECEIVED` receiver,
delivery via WorkManager, AES-GCM via `javax.crypto` with the key in the Android Keystore, QR scanning via the Google
code scanner. Sideloaded APK only (Google Play does not allow `RECEIVE_SMS` for this use case).

## Contributing with AI agents

See [`CLAUDE.md`](CLAUDE.md) for conventions and the subagent workflow; agent definitions live in
[`.claude/agents/`](.claude/agents/), and the always-on ponytail skill in [`.claude/skills/`](.claude/skills/).
