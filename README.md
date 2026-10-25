# Vaani

**Vaani** is a voice-first remote operator for a Windows laptop. A native Android app sends typed
or spoken commands to a small Python agent on the laptop, which carries out a short, allowlisted
set of actions — open Calculator, Notepad or File Explorer, search approved folders, report status —
and sends the result back to the phone, optionally spoken aloud.

Natural-language requests (in English, Hindi, Telugu or a mix) can be interpreted by an AI planner,
but the laptop agent, not the AI, decides what is allowed: every plan is validated against a strict
schema and allowlist before anything runs, and no model output ever reaches a shell.

```
 Android phone                                    Windows laptop
┌────────────────────────────┐  HTTP over home    ┌─────────────────────────────────────────────┐
│ Vaani app (Kotlin/Compose) │  Wi-Fi, or over    │ Vaani agent (Python/FastAPI)                │
│ • text + tap-to-talk voice │  Tailscale         │ 1. client network filter + bearer token     │
│ • status, results, replies │  (encrypted)       │ 2. rule-based parser → optional Groq planner│
│ • token in Android Keystore│ ─────────────────► │ 3. strict schema + allowlist validation     │
│                            │ ◄───────────────── │ 4. fixed executables / restricted search    │
└────────────────────────────┘                    │ 5. Sarvam STT/TTS (API key stays here)      │
                                                  └─────────────────────────────────────────────┘
```

## Features

- **Allowlisted actions only:** `open_calculator`, `open_notepad`, `open_explorer`, `find_file`,
  `get_status`. Programs are started from fixed absolute paths with `shell=False`.
- **Restricted file search:** only configured folders (Documents, Desktop and Downloads by default,
  resolved through Windows Known Folders so OneDrive-redirected folders work). Symlinks and
  junctions are not followed; depth, result count and time are capped; only names and metadata
  (size, modified date) are returned, never file contents.
- **Works without AI:** a rule-based parser handles common commands. When a Groq key is configured,
  `qwen/qwen3.8-27b` interprets free-form requests and asks a clarifying question when a request
  is ambiguous or out of scope.
- **Voice:** tap the mic to record, tap again to send. The laptop transcribes with Sarvam
  Saaras v4 and speaks replies with Sarvam Bulbul v4 Flash; the phone falls back to Android
  TextToSpeech if spoken replies are unavailable. Replies can be muted.
- **Secure by default:** bearer-token authentication on every endpoint except a minimal `/health`;
  requests from outside loopback, private LAN and Tailscale ranges are refused; API keys stay on the
  laptop and never ship in the APK; the phone stores its token encrypted with an Android Keystore key.
- **Remote access:** works away from home over [Tailscale](https://tailscale.com/).
- **Runs unattended:** a supervisor starts the agent at Windows login, restarts it after crashes or
  network changes, and the app reconnects by itself after the laptop wakes.

## Repository layout

```
Vaani/
├── laptop-agent/            Python agent for Windows
│   ├── app/
│   │   ├── main.py          FastAPI app factory and server entry point (python -m app)
│   │   ├── supervisor.py    background runner (pythonw -m app.supervisor)
│   │   ├── config.py        settings from environment / .env
│   │   ├── api/             /commands, /status and /voice endpoints
│   │   ├── models/          request/response and strict action-plan schemas
│   │   ├── security/        token auth and client-network filter
│   │   └── services/        parser, AI planner, executor, file search, Sarvam client
│   ├── scripts/vaani.ps1    start/stop/status/logs and autostart management
│   ├── tests/               pytest suite (+ opt-in live planner evaluation)
│   ├── requirements.txt
│   └── .env.example
├── android-app/             Kotlin + Jetpack Compose app (package com.vaani.app)
│   └── app/src/
│       ├── main/java/com/vaani/app/
│       │   ├── data/        repository interfaces, settings and Keystore token storage
│       │   ├── network/     OkHttp client and address validation
│       │   ├── speech/      WAV recorder and reply playback
│       │   ├── ui/          Compose screens
│       │   └── viewmodel/   screen state and logic
│       └── test/            JVM unit tests
└── .vscode/                 build/run/test tasks and workspace settings
```

## Prerequisites

**Laptop**
- Windows 10 or 11
- Python 3.12 or newer (developed with 3.13)
- Optional: a [Groq](https://console.groq.com/) API key for AI planning
- Optional: a [Sarvam AI](https://www.sarvam.ai/) API key for voice
- Optional: [Tailscale](https://tailscale.com/) for access away from home

**Android build** (Android Studio is not required)
- JDK 17 or newer (developed with JDK 25)
- Android SDK command-line tools with platform-tools and the Android 37 platform
  (Gradle downloads missing build tools once the SDK licenses are accepted)
- An Android 8.0+ phone (minSdk 26) with USB or wireless debugging enabled

## Setup

### 1. Laptop agent

```powershell
cd laptop-agent
py -3.13 -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt
copy .env.example .env
```

Edit `.env`:

- Set `VAANI_AUTH_TOKEN` to a random secret of at least 32 characters:
  ```powershell
  .venv\Scripts\python.exe -c "import secrets; print(secrets.token_urlsafe(32))"
  ```
- Set `VAANI_HOST` to the addresses the phone will use (see [Configuration](#configuration)).
- Optionally add `GROQ_API_KEY` and `SARVAM_API_KEY`.

Start the agent in a terminal:

```powershell
.venv\Scripts\python.exe -m app
```

or in the background (see [Running in the background](#running-in-the-background)).

### 2. Android app

Point Gradle at your SDK by creating `android-app/local.properties` (git-ignored):

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

Build and install:

```powershell
cd android-app
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

The APK is written to `android-app/app/build/outputs/apk/debug/app-debug.apk`.

In VS Code, **Terminal → Run Task** offers the same steps ("Android: build debug APK",
"Android: install on phone", "Android: connect over Wi-Fi", "Android: list devices"). The
workspace settings in `.vscode/settings.json` contain example JDK and SDK paths; adjust them to
your machine.

### 3. Connect the phone

1. Open Vaani; the **Laptop connection** screen appears on first launch (later via ⚙).
2. Enter the laptop address and port, e.g. `192.168.x.x:8765` on home Wi-Fi or the laptop's
   Tailscale address (`100.x.y.z:8765`), and paste `VAANI_AUTH_TOKEN`.
3. Tap **Test connection**, then **Save**.

Plain HTTP is accepted only for private, Tailscale and loopback addresses; any other address must
use `https://`.

## Usage

Type a command, tap a quick-action chip, or tap 🎤, speak, and tap ■ to send. Examples:

| Request | Result |
|---|---|
| `Open Calculator` | Calculator opens on the laptop |
| `open notepad, please` | Notepad opens |
| `Find my resume in Desktop` | Matching files and folders from Desktop, with size and date |
| `find the latest pdf in downloads` | PDFs in Downloads, newest first |
| `Is my laptop connected?` | Agent status |
| `calculator kholo` / `कैलकुलेटर खोलो` / `కాలిక్యులేటర్ తెరువు` | Calculator opens (AI planner) |
| `delete all files in Documents` | Refused with an explanation of what Vaani can do |

Replies to spoken commands are read aloud unless muted with the speaker icon. The status pill shows
**Connected**, **Disconnected** or **Not set up**; tap it to check again.

### API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/health` | none | Liveness only |
| `GET` | `/status` | Bearer | Agent version, hostname, AI/voice availability, approved folders |
| `POST` | `/commands` | Bearer | `{"command": "open calculator"}` |
| `POST` | `/voice/transcribe` | Bearer | Multipart `file` (WAV, ≤ 2 MB) and optional `language` |
| `POST` | `/voice/speak` | Bearer | `{"text": "...", "language": "en-IN"}` → `audio/wav` |

`/commands` returns `{request_id, status, action, message, data, planner}` where `status` is one of
`success`, `not_found`, `rejected`, `error` or `needs_clarification`.

## Configuration

All agent settings are read from environment variables or `laptop-agent/.env`
(see [`.env.example`](laptop-agent/.env.example)).

| Variable | Default | Description |
|---|---|---|
| `VAANI_AUTH_TOKEN` | — (required) | Shared secret, ≥ 32 characters, sent by the phone as `Authorization: Bearer …` |
| `VAANI_HOST` | `127.0.0.1` | Comma-separated IPs to listen on, e.g. `127.0.0.1,192.168.0.10,100.x.y.z`; addresses not present at start-up are skipped |
| `VAANI_PORT` | `8765` | Listening port |
| `VAANI_ALLOWED_CLIENTS` | loopback, private LAN, Tailscale | Comma-separated CIDRs allowed to reach the agent at all |
| `VAANI_APPROVED_FOLDERS` | `Documents,Desktop,Downloads` | Known folder names or `Alias=C:\absolute\path` entries |
| `VAANI_ENABLE_DOCS` | off | Set to `1` to expose `/docs` (development only) |
| `GROQ_API_KEY` | — | Enables the AI planner |
| `GROQ_MODEL` | `qwen/qwen3.8-27b` | Groq model ID |
| `SARVAM_API_KEY` | — | Enables voice endpoints |
| `SARVAM_STT_MODEL` | `saaras:v4` | Speech-to-text model |
| `SARVAM_TTS_MODEL` | `bulbul:v4-flash` | Text-to-speech model |

Windows Firewall must allow inbound TCP on `VAANI_PORT` for the Python interpreter running the
agent, ideally limited to the local subnet and `100.64.0.0/10`.

### Remote access with Tailscale

1. Install Tailscale on the laptop and the phone and sign in with the same account.
2. Add the laptop's Tailscale IP (`tailscale ip -4`) to `VAANI_HOST` and restart the agent.
3. Use that address in the app. It works on home Wi-Fi and mobile data and does not change when
   the router assigns new local addresses.
4. Consider disabling key expiry for the laptop in the Tailscale admin console.

### Running in the background

```powershell
cd laptop-agent
.\scripts\vaani.ps1 enable-autostart   # start at Windows login (current-user Startup shortcut)
.\scripts\vaani.ps1 start              # also: stop, restart, status, logs
.\scripts\vaani.ps1 disable-autostart
```

The supervisor restarts the agent if it exits, fails three health checks, or the set of available
`VAANI_HOST` addresses changes (for example Tailscale connecting after login or the laptop waking
from sleep). Logs are written to `%LOCALAPPDATA%\Vaani\logs`.

## Testing

```powershell
# Laptop agent
cd laptop-agent
.venv\Scripts\python.exe -m pytest

# Android app (JVM unit tests)
cd android-app
.\gradlew.bat testDebugUnitTest
```

The agent suite covers authentication, the network filter, command parsing, plan validation
(including adversarial and malformed AI output), file-search restrictions, voice endpoints and the
supervisor. The Android suite covers view-model logic, voice flow, reconnection, the HTTP client
(against a mock server) and formatting helpers.

An opt-in live evaluation runs 33 real requests — English, Hindi, Telugu, ambiguous, malicious and
malformed — through the full pipeline against the Groq model without executing anything. It uses
API quota:

```powershell
$env:VAANI_LIVE_TESTS = '1'
.venv\Scripts\python.exe -m pytest tests/test_planner_live.py -v
```

## Security model

- The AI planner only *suggests* a plan; the agent validates it in strict mode (exact action names,
  no extra keys, no type coercion) and executes only allowlisted actions.
- Search queries may contain letters in any script but never path separators, drive colons,
  wildcards, `..` or control characters; folders must match an approved alias.
- Secrets live only in `laptop-agent/.env` (git-ignored) or environment variables. They are never
  logged; the audit log records request ID, action and outcome only — not command text or transcripts.
- Do not expose the agent to the public internet. Use home Wi-Fi for testing and Tailscale for
  remote use; traffic on home Wi-Fi is plain HTTP.

## Known limitations

- The phone cannot wake a sleeping laptop; the app shows **Disconnected** and reconnects
  automatically once the laptop is back. Wake-on-LAN is not implemented.
- The agent starts at user login, not at boot.
- Windows opened while the desktop is locked appear behind the lock screen.
- Groq's free tier allows roughly 8,000 tokens per minute, so bursts of AI-planned commands can
  briefly report "rate-limited"; rule-based commands are unaffected.
- Voice requires the laptop (Sarvam is called from it); there is no phone-only speech-recognition
  fallback yet. Replies are spoken in English (en-IN). Recording works only while the app is open,
  up to 30 seconds; there is no wake word or locked-screen listening.
- Only the actions listed above exist. Opening, sending or deleting files is intentionally not
  supported.

## Contributing

- Keep both test suites passing and add tests with every change, especially to validation,
  authentication and file-search code.
- New laptop actions must be added explicitly to the plan schema, the executor and the planner
  prompt, and must never pass user or model text to a shell.
- Never commit `.env`, `local.properties`, API keys or tokens.

## License

This repository does not currently include a license file.
