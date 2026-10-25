# Vaani

Voice-first AI operator: an Android app (Kotlin + Compose) that sends commands to a
Python agent on a Windows laptop. The agent only runs allowlisted actions.

## Laptop agent (`laptop-agent/`)

```powershell
cd laptop-agent
py -3.13 -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt
copy .env.example .env    # then set VAANI_AUTH_TOKEN (and optional GROQ/SARVAM keys)
.venv\Scripts\python.exe -m app          # serves on 127.0.0.1:8765
.venv\Scripts\python.exe -m pytest       # tests
```

### API
| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/health` | none | liveness only |
| GET | `/status` | Bearer token | agent status, approved folders |
| POST | `/commands` | Bearer token | `{"command": "open calculator"}` |

Response: `{request_id, status, action, message, data, planner}` where `status` is
`success | not_found | rejected | error | needs_clarification`.

### Pipeline
1. Bearer token checked (constant-time compare).
2. Rule-based parser tries to match the text (works with no AI key).
3. If no rule matches and `GROQ_API_KEY` is set, Groq (`qwen/qwen3.8-27b`) suggests a JSON plan.
4. Every plan is validated against a strict Pydantic schema (unknown actions/fields rejected).
5. Executor runs fixed, absolute executables (`calc.exe`, `notepad.exe`, `explorer.exe`) with
   `shell=False`; no user or model text ever reaches a command line.
6. `find_file` searches only `VAANI_APPROVED_FOLDERS` (names like `Desktop` resolve through Windows'
   Known Folders, so OneDrive-redirected folders are found), does not follow symlinks/junctions,
   returns names and metadata only, and is capped by depth, count and time.

Allowlisted actions: `open_calculator`, `open_notepad`, `open_explorer`, `find_file`, `get_status`.

### Security notes
- `.env` is git-ignored. Keys live only on the laptop, never in the APK.
- Default bind is `127.0.0.1`. For phone testing set `VAANI_HOST` to the laptop's Tailscale
  (preferred) or Wi-Fi IP, and restrict port 8765 in Windows Firewall.
- Audit log records request id, action and outcome only.

## Android app (`android-app/`)
Kotlin + Jetpack Compose, AGP 9.4.1, Gradle 9.8.2 (wrapper), compileSdk 37, targetSdk 36, minSdk 26.

Toolchain (no Android Studio): SDK at `E:\Android\Sdk`, Gradle cache at `E:\Android\.gradle`,
JDK 25. `android-app/local.properties` (git-ignored) points Gradle at the SDK.

In VS Code: **Terminal → Run Task** →
- `Android: build debug APK` (also Ctrl+Shift+B)
- `Android: install on phone` (USB debugging on, phone shown by `Android: list devices`)
- `Agent: run server` / `Agent: run tests`

APK output: `android-app/app/build/outputs/apk/debug/app-debug.apk`.

### Connecting phone to laptop (same Wi-Fi)
1. In `laptop-agent/.env` set `VAANI_HOST` to the laptop's Wi-Fi IP (e.g. `192.168.0.7`) and start the agent.
2. Windows Firewall must allow inbound TCP 8765 for the agent's Python
   (`C:\ProgramData\anaconda3\python.exe`), ideally only from `LocalSubnet`.
3. In the app: ⚙ → address `192.168.0.7:8765`, token = `VAANI_AUTH_TOKEN` → Test connection → Save.

### Away from home (Tailscale)
1. Install Tailscale on the laptop and phone and sign in with the same account.
2. Add the laptop's Tailscale IP (`tailscale ip -4`, e.g. `100.106.183.88`) to `VAANI_HOST`,
   e.g. `VAANI_HOST=127.0.0.1,192.168.0.7,100.106.183.88`. Unavailable addresses are skipped.
3. In the app, set the address to the Tailscale IP. It works on home Wi-Fi and mobile data and
   does not change when the router hands out new Wi-Fi addresses.
4. In the Tailscale admin console, disable key expiry for the laptop.

The agent only answers clients on loopback, private LAN and Tailscale ranges
(`VAANI_ALLOWED_CLIENTS`), before checking the token.

The token is stored encrypted with an Android Keystore key. Plain http is accepted only for
private/LAN, Tailscale (100.64.0.0/10) and loopback addresses; others require https.
If the laptop's IP changes (DHCP), update both `VAANI_HOST` and the app setting.

## Known limitations
- Laptop must be awake, online, and running the agent.
- GUI actions fail if the desktop is locked.
- Locked-screen / background voice is out of scope for v1.
