# OTP Forwarder Android

Android companion app for [OTP Forwarder Desktop](https://github.com/cslavenc/otp-forwarder-desktop). It picks up
one-time passwords from incoming **SMS** of senders you allow, encrypts them end to end (AES-256-GCM), and sends them
through the ntfy.sh relay to your Windows PC, which types them into the focused app (Citrix Workspace, browser logins,
…).

```
SMS ──▶ this app ──AES-GCM encrypted──▶ ntfy.sh relay ──▶ desktop app ──▶ focused app (Citrix, …)
```

> **Status:** v0.1, feature complete. Sideloaded APK only: Google Play does not allow `RECEIVE_SMS` for this use case.

## How it works

1. **Pair once.** The desktop app shows a QR code with the relay URL, a random topic and a random 256-bit key. This app
   scans it with the Google code scanner (no camera permission needed), imports the key into the **Android Keystore**
   (it can never be read back out) and stores relay + topic.
2. **An SMS arrives.** Android wakes the app's `SMS_RECEIVED` receiver, even when the app is closed. The receiver:
   1. drops the SMS unless the sender is on your **allowed senders** list (an empty list forwards nothing);
   2. drops it if the SMS is older than 15 minutes (it was delayed by the network or by Android), so a stale code is
      never typed hours later;
   3. finds the code: it needs a keyword such as *code, OTP, TAN, PIN, passcode, Bestätigungscode, code de sécurité,
      codice, …* and ignores amounts, dates, times, phone numbers, IBANs and masked card numbers;
   4. builds `{"v":1,"id":<random>,"code":…,"sender":…,"ts":<receipt time>}`, encrypts it with the Keystore key
      (fresh random nonce, topic bound as authenticated data) and queues it for sending.
3. **Send.** A WorkManager job POSTs the ciphertext to `https://ntfy.sh/<topic>` as soon as there is network. It
   retries the **identical** message (same id, so the desktop types it at most once) for up to 110 s; the desktop
   rejects codes older than 2 minutes anyway.
4. **Desktop.** Decrypts, validates, and types the code into the focused window (or copies it to the clipboard). See
   the desktop README.

The exact message format, limits and threat model are in [`docs/wire-contract.md`](docs/wire-contract.md) (a mirror of
the desktop repo's authoritative copy).

### Privacy and security

- The relay only ever sees ciphertext. Confidentiality and authenticity come from AES-256-GCM, not from the topic.
- The key lives in the Android Keystore and is never exported, logged or shown.
- Codes are never logged, never shown in notifications and never written to disk in plaintext. The in-app log stores
  sender, time and status only.
- SMS from senders that are not on the list are ignored and not logged.
- No backups (`allowBackup=false`, all data excluded from cloud backup and device transfer).
- Permissions: `RECEIVE_SMS`, `INTERNET`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, plus what WorkManager needs
  (network state, wake lock, boot completed, foreground service on Android 8–11). No `READ_SMS`, camera or contacts.
- **Accepted risk:** SMS sender IDs can be spoofed. A spoofed SMS from an allowed sender can at most make the desktop
  type ≤ 10 letters/digits (Enter is off by default on the desktop).

## Install

Requirements: Android 8.0+ with Google Play services (for the QR scanner), and the desktop app installed and running.

### Quick start

With the phone connected (USB or wireless debugging, see [step 2](#2-put-it-on-the-phone)), one command builds the
release APK and installs it:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-install.ps1
```

Without a connected phone it only builds and copies the APK to `release\otp-forwarder.apk`; transfer it by hand
(option C). The steps below explain what the script does.

### 1. Build the APK (on the PC)

Prerequisites: JDK 17 (Android Studio's bundled JBR works) and the Android SDK (Android Studio installs it; set
`sdk.dir` in `local.properties` or `ANDROID_HOME`). Then in PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease
```

The APK is `app\build\outputs\apk\release\app-release.apk`. It is signed with your local debug key
(`%USERPROFILE%\.android\debug.keystore`): **back that file up**. An APK signed with a different key cannot update the
installed app; you would have to uninstall, reinstall and pair again.

Use the release APK on your phone. The debug APK is for the emulator (it can be paired from the PC, see
[Development](#development)).

### 2. Put it on the phone

**Option A — wireless debugging (recommended, Android 11+, no USB driver):**

1. On the phone: Settings › About phone › tap *Build number* 7× to enable Developer options.
2. Settings › System › Developer options › **Wireless debugging** → on → *Pair device with pairing code*.
3. On the PC (same Wi-Fi):
   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb pair <ip>:<pairing-port>        # enter the 6-digit code shown on the phone
   & $adb connect <ip>:<port>             # the port shown on the Wireless debugging screen
   & $adb install -r app\build\outputs\apk\release\app-release.apk
   ```

**Option B — USB debugging:** enable *USB debugging* in Developer options, connect the cable, then
`& $adb install -r app\build\outputs\apk\release\app-release.apk`.

**Option C — copy the file:** copy the APK to the phone and open it in the Files app. Allow "install unknown apps" for
the Files app when asked. Google Play Protect may warn about an unknown developer; choose *Install anyway*. Apps
installed this way hit Android's **restricted settings** (see setup step 3).

Installing through `adb` (options A and B) avoids the restricted-settings block on most phones.

## First setup

Open **OTP Forwarder** and go through the screen top to bottom:

1. **Pairing → Scan QR.** On the PC, open the desktop app's *Pairing* tab and click *Show QR code*. The first scan may
   say "Downloading the scanner. Try again in a moment": Google Play services fetches the scanner once. After a
   successful scan the card shows *Paired via https://ntfy.sh*.
2. **Setup → SMS permission → Grant.**
3. If Android says **"Restricted setting"** (Android 13+, apps installed from a file): tap *Open app info*, then the
   ⋮ menu → **Allow restricted settings**, confirm, go back and tap *Grant* again.
4. **Battery optimization off → Allow.** Without this, Android may delay the forward by minutes when the phone is idle.
   On Xiaomi, Huawei, Samsung, OnePlus etc. also follow [dontkillmyapp.com](https://dontkillmyapp.com) for your model
   (e.g. MIUI *Autostart*).
5. **"Pause app activity if unused" off → Open** (Android 11+). Otherwise Android revokes the SMS permission after a
   few months in which you never opened the app.
6. **Allowed senders → Add** each sender **exactly as your Messages app shows it**:
   - alphanumeric names like `PostFinance`, `UBS`, `Google` (case does not matter);
   - phone numbers like `+41 79 123 45 67` (spaces, dashes, `(0)` and `0041` are fine; `079 123 45 67` also matches
     `+4179…`);
   - short codes like `723`.
7. **Recent forwards → Send test code.** The app shows a random 6-digit code and sends it with sender *Test* through
   the same encrypted path (it skips the sender list and code detection). The desktop should show/type the same code
   within a few seconds and the log entry should turn to *Sent*.
8. Finally, trigger a real OTP (log in somewhere that texts you a code). To test without a bank, add your own number
   and text yourself `Your code is 123456` from another phone.

Re-scanning a QR replaces the pairing; **Unpair** deletes the key. Both cancel anything still queued for the old
pairing.

## Recent forwards (log)

| Status | Meaning |
|---|---|
| Queued | Encrypted and waiting to be sent (e.g. no network yet). |
| Sent | The relay accepted it. Whether the desktop typed it is shown on the desktop. |
| Failed (`http 4xx`, `expired …`, `keystore`, `internal`, `enqueue`) | Not delivered. `expired` = no network/relay within 110 s; `http 4xx` = relay rejected it; `keystore` = re-pair. |
| Not delivered | Was queued but never sent in time (no network, app killed). |
| No code found | Allowed sender, but no code next to a keyword. |
| Too old (`N min old`) | The SMS arrived more than 15 minutes after it was sent. |
| Not paired | An allowed SMS arrived while the app was not paired. |

Senders not on the list never appear in the log.

## Limitations

- **2-minute window.** A code that cannot be delivered within ~110 s (no network, phone in deep sleep) is dropped,
  never sent late.
- **Android 17 holds back OTP SMS for 3 hours** for apps that target API 37; this app targets API 36 to keep receiving
  them. Android 17 also holds back *WebOTP* (`@domain #123456`) and *SMS Retriever* format SMS for **all** apps; those
  arrive too late and show up as *Too old*.
- **RCS / chat messages** ("business messages" in Google Messages) are not SMS and are not seen.
- Nothing is forwarded after a reboot until the phone is unlocked once, or after you **force-stop** the app until you
  open it again.
- Codes made only of letters (`ABCDEF`) are not detected. The code must be 4–10 letters/digits with at least one digit.
- A wrong guess is possible for unusual SMS formats; the desktop does not press Enter by default, so you can fix it.
- One desktop per phone.

## Troubleshooting

| Symptom | Check |
|---|---|
| Nothing in the log when the SMS arrives | Sender not on the list or written differently (compare with the Messages app); SMS permission missing; restricted settings; app force-stopped; battery killer. |
| *No code found* | The SMS has no keyword the app knows. Open an issue with the SMS text (code changed). |
| *Too old* for fresh SMS | The carrier's SMS timestamps are wrong (rare, e.g. roaming). |
| *Failed (expired …)* / *Not delivered* | No network or the relay was unreachable for 110 s. Check battery optimization. |
| *Failed (http 4xx)* | The relay rejected the message; re-pair. |
| Desktop says *stale* or *from the future* | Turn on automatic date & time on both the phone and the PC. |
| *Scanner unavailable* | Update Google Play services; the scanner needs it. |
| *Not paired* / test button disabled | Scan the desktop QR again. |

## Development

| Task | Command (PowerShell, with `$env:JAVA_HOME` set to a JDK 17) |
|---|---|
| Unit tests | `.\gradlew.bat testDebugUnitTest` |
| Lint (must pass, warnings are errors) | `.\gradlew.bat lintDebug` |
| Debug / release APK | `.\gradlew.bat assembleDebug` / `.\gradlew.bat assembleRelease` |
| All checks | `.\gradlew.bat check` |

Stack: Kotlin, Jetpack Compose (Material 3), one `app` module, minSdk 26, compileSdk 37, **targetSdk 36 (pinned, see
Limitations)**, AGP 9.5 with built-in Kotlin, Gradle 9.9. Platform APIs over libraries: `javax.crypto`,
`HttpURLConnection`, `org.json`, `SharedPreferences`; dependencies are only Compose, WorkManager and the Google code
scanner. All protocol and security logic is pure Kotlin in `core/` and unit-tested on the JVM, including the desktop's
test vector byte for byte. See [`CLAUDE.md`](CLAUDE.md) for the architecture and conventions.

### End-to-end test on the emulator

The emulator has no camera, so the **debug** build can import a pairing file through `run-as` (only works for
debuggable builds; the release build ignores it). This uses a throwaway pairing and checks the result directly on
ntfy:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk

# 1. Throwaway pairing (random topic + key)
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
function New-B64Url([int]$n) { $b = New-Object byte[] $n; $rng.GetBytes($b); [Convert]::ToBase64String($b).TrimEnd('=').Replace('+','-').Replace('/','_') }
$topic = New-B64Url 16; $key = New-B64Url 32
$json = "{`"v`":1,`"relay`":`"https://ntfy.sh`",`"topic`":`"$topic`",`"key`":`"$key`"}"

# 2. Drop it into the app; it is imported (and the file deleted) when the app comes to the foreground
$json | & $adb exec-in "run-as ch.cslavenc.otpforwarder sh -c 'cat > files/debug-pairing.json'"
& $adb shell am start -n ch.cslavenc.otpforwarder/.MainActivity

# 3. In the app: grant SMS, add sender +41790000000. Then fake an SMS:
& $adb emu sms send +41790000000 "Ihr Code lautet 482913"

# 4. The ciphertext arrived on the relay (decrypting it needs the key, e.g. Python `cryptography` AESGCM with
#    nonce = first 12 bytes, AAD = "otpfwd/v1/<topic>")
curl.exe -s "https://ntfy.sh/$topic/json?poll=1&since=10m"
```

To test against the real desktop app instead, put the desktop's pairing JSON into `$json` (decode the QR **locally**,
never with an online decoder: it contains the key) and watch the desktop's Codes tab.

## Contributing with AI agents

See [`CLAUDE.md`](CLAUDE.md) for conventions and the subagent workflow; agent definitions live in
[`.claude/agents/`](.claude/agents/), and the always-on ponytail skill in [`.claude/skills/`](.claude/skills/).
