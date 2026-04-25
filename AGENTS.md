# AGENTS.md — AI Context for MBF Tools and Setup

This file is intended for AI coding assistants (Claude, Copilot, Cursor, GPT, Gemini, etc.).
It describes the project layout, conventions, and key facts needed to work effectively.

---

## What this project is

**MBF Tools and Setup** is a Meta Quest on-device Android app + cloud backend for:
- Guiding users through enabling Developer Mode and Wireless Debugging
- On-device ADB pairing and connection management
- Launching the [MBF (ModsBeforeFriday)](https://mbf.bsquest.xyz) mod loader via WebView
- Uploading and sharing debug diagnostics with a 5-character shareable code

---

## Repository layout

```
/
├── app/                        Android app (Kotlin)
│   └── src/main/java/org/sm0ke/mbftools/
│       ├── MainActivity.kt         Main debug/ADB control screen
│       ├── HomeActivity.kt         Home screen
│       ├── GuideActivity.kt        Step-by-step setup wizard
│       ├── BrowserActivity.kt      WebView for MBF web app
│       ├── LauncherActivity.kt     Splash/router
│       ├── WirelessDebugRequiredActivity.kt
│       ├── AdbManager.kt           ADB communication (pairing, connect, shell)
│       ├── SetupState.kt           Detects dev mode / wireless debugging state
│       ├── AppPrefs.kt             SharedPreferences wrapper
│       ├── DiagnosticsCollector.kt Collects full diagnostic payload (820 lines)
│       ├── DiagnosticsShareClient.kt HTTP POST to the backend, parses receipt
│       ├── DebugShareHelper.kt     UI helper — shows dialog, copies code
│       ├── AppLog.kt               In-app log ring buffer (info/warn/error)
│       ├── BridgeManager.kt        Native ADB bridge binary management
│       └── FaqRepository.kt        Live FAQ data fetch
│
├── api/                        Cloudflare Worker backend
│   ├── api/handler.js              Single-file Worker — all routes
│   ├── wrangler.toml               Cloudflare config (KV binding: LOGS)
│   └── package.json                devDependency: wrangler
│
├── build-and-push.ps1          Build debug APK + adb install
├── build-release.ps1           Build signed release APK
└── README.md
```

---

## Backend — Cloudflare Worker (`api/api/handler.js`)

**Live URL:** `https://mbf-tools-logs-api.netherslayer87.workers.dev`

Single JavaScript ES module. All logic in one file, no framework, no build step.

### Routes

| Method | Path / Query | Description |
|--------|-------------|-------------|
| `POST /` | — | Upload diagnostics JSON, returns `SharedLogReceipt` |
| `GET /` | `?action=view&code=xxx` | Interactive HTML debug viewer |
| `GET /` | `?action=summary&code=xxx` | JSON summary (add `&format=text` for plain text) |
| `GET /` | `?action=message&code=xxx` | Discord-friendly plain text |
| `GET /` | `?action=data&code=xxx` | Full JSON payload |
| `GET /` | `?action=aifix&code=xxx` | AI fix suggestions via Pollinations |
| `GET /admin` | — | Browse/manage logs (password protected) |
| `POST /admin/login` | — | SHA-256 password check, sets session cookie |
| `POST /admin/delete` | — | Delete a log by code |

### Storage

Cloudflare KV namespace `LOGS` (binding name in `wrangler.toml`):
- **Key:** `log:{code}` (e.g. `log:a3x9f`)
- **Value:** JSON string of the full record
- **Metadata:** `{ createdAt, summary }` — stored alongside for efficient admin list view
- **TTL:** 30 days

### Record schema

```json
{
  "code": "a3x9f",
  "createdAt": "2026-04-25T18:22:06.134Z",
  "command": "!s a3x9f",
  "summary": "Short human-readable summary string",
  "issues": ["Array of detected issue strings"],
  "payload": { /* full Android diagnostic payload — see below */ }
}
```

### Diagnostic payload schema (from Android app)

```json
{
  "app":          { "versionName", "versionCode", "setupComplete" },
  "device":       { "manufacturer", "brand", "model", "device", "product", "sdkInt", "release", "fingerprint" },
  "runtime":      { "maxMemory", "totalMemory", "freeMemory", "processors" },
  "network":      { "hasActiveNetwork", "hasWifiTransport", "hasInternetCapability" },
  "setup":        { "developerModeEnabled", "wirelessDebuggingEnabled", "connectedDevice",
                    "currentGuideStep", "pairingPort", "debugPort" },
  "beatSaber":    { "packageName", "installed", "versionName" },
  "mods":         { "count", "items": ["mod-id-strings"] },
  "beatSaberLogs":{ "lineCount", "interestingCount", "lines": [], "interesting": [] },
  "logStats":     { "errorCount", "warnCount", "lineCount", "recentProblems": [] },
  "logs":         [ { "level", "tag", "message", "time" } ],
  "logsText":     "full concatenated log string"
}
```

### AI fix endpoint

Calls `https://text.pollinations.ai/{url-encoded-prompt}` (GET, plain text response).
The prompt includes all detected issues + setup state. No API key required.

### Admin auth

Password verified server-side: `sha256(submitted_password) === ADMIN_HASH`.
On success, sets `HttpOnly; Secure; SameSite=Strict` cookie `mbf_admin` with the hash value (24hr).

### UI style

Matches [mbf.bsquest.xyz](https://mbf.bsquest.xyz) aesthetic:
- Background `#002040`, cards `#000000f2`, no borders
- Font: Roboto (Google Fonts) + Consolas for code/logs
- Buttons: `#333` default, fade to `#1127f3` on hover via `::after` opacity transition
- Tabs: browser-tab style — rounded top corners, active tab connects flush to content panel
- Animated SVG background: floating Beat Saber notes/bombs (Web Animations API), `z-index: -100`, `filter: blur(0.5em)`. Respects `prefers-reduced-motion`.

---

## Android app conventions

- **Language:** Kotlin, targeting API 34, min API 21
- **Package:** `org.sm0ke.mbftools`
- **No Compose** — all UI is XML layouts + View binding
- **Coroutines:** `lifecycleScope.launch { withContext(Dispatchers.IO) { ... } }` pattern throughout
- **ADB:** All shell commands go through `AdbManager`, which wraps the native bridge binary
- **Logging:** Use `AppLog.info/warn/error(tag, message)` — not Android `Log.*` directly
- **Preferences:** Use `AppPrefs` — do not read `SharedPreferences` directly
- **Backend URL:** Hardcoded in `DebugShareHelper.DEFAULT_BACKEND_URL`. Change there to point to a different deployment.

### Diagnostics collection flow

1. `DebugShareHelper.share()` called from any Activity
2. Shows progress dialog
3. Launches `DiagnosticsCollector.collect()` on IO thread
4. Posts result JSON to `DiagnosticsShareClient.upload(url, json)`
5. Shows result dialog with the shareable code (`!s xxxxx`)

---

## Deploy

### Backend (Cloudflare Worker)

```bash
cd api
npm install
npx wrangler deploy
```

Requires Cloudflare account logged in via `wrangler login`.
KV namespace ID is already set in `wrangler.toml`. No other env vars needed.

### Android app

```powershell
# Debug build + install to connected device
.\build-and-push.ps1

# Signed release APK
.\build-release.ps1
```

Signing config read from `keystore/signing.properties` (gitignored).

---

## Things to know / avoid

- **Don't use Google Drive or Google Apps Script** — the old backend (`api/src/Code.js`) is kept for reference only and is not deployed
- **Don't add Node.js server code** — the Worker uses the Cloudflare Workers runtime (Web APIs only, no Node built-ins like `fs`, `path`, `crypto` module — use `globalThis.crypto.subtle` instead)
- **Don't add a build step** to the Worker — `handler.js` is deployed as-is, single file
- **Don't use `console.log` for debugging in the Worker** — use Cloudflare's dashboard log tail or add structured error returns
- **The KV `list()` API returns metadata** — when saving records, always include `metadata: { createdAt, summary }` so the admin panel can list without fetching every record
