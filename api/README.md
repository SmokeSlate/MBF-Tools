# MBF Tools Logs API

This folder contains the Google Apps Script backend for the `Share Debug Logs` button in `/app`.

The Android app uploads one JSON payload to the base Apps Script web-app URL. The backend stores that payload, generates a short share code like `2a3rf`, and returns:

- a code
- a bot-friendly command like `!s 2a3rf`
- a short summary URL
- a message URL for piping to a bot
- a full browser viewer URL

## What the app uploads

The payload includes:

- app version and package info
- headset model/build/runtime/network info
- current setup step if the user is in the guide
- developer mode and Wireless Debugging state
- pairing port and wireless debug port values
- current ADB device state
- Beat Saber installed state/version
- current detected mods
- persistent app logs and warning/error counts

## Backend shape

`src/Code.js` exposes four actions off the same deployed web-app URL.

Base upload URL:

```text
POST https://script.google.com/macros/s/DEPLOYMENT_ID/exec
```

Returned raw GAS URLs:

- viewer: `?action=view&code=abc12`
- summary: `?action=summary&code=abc12`
- message: `?action=message&code=abc12`
- data: `?action=data&code=abc12`

Recommended public routes in front of GAS:

- viewer: `https://logs.sm0ke.org/abc12`
- summary: `https://logs.sm0ke.org/summary/abc12`
- message: `https://logs.sm0ke.org/message/abc12`
- data: `https://logs.sm0ke.org/data/abc12`

## Endpoint contract

### 1. Upload

Method:

```text
POST /
Content-Type: application/json
```

Response:

```json
{
  "ok": true,
  "code": "abc12",
  "command": "!s abc12",
  "summary": "Wireless Debugging is turned off. No authorized ADB headset connection is active.",
  "viewerUrl": "https://.../exec?action=view&code=abc12",
  "summaryUrl": "https://.../exec?action=summary&code=abc12",
  "messageUrl": "https://.../exec?action=message&code=abc12",
  "dataUrl": "https://.../exec?action=data&code=abc12"
}
```

### 2. Summary

Method:

```text
GET ?action=summary&code=abc12
```

Response:

```json
{
  "ok": true,
  "code": "abc12",
  "summary": "Wireless Debugging is turned off.",
  "issues": [
    "Wireless Debugging is turned off."
  ],
  "currentGuideStep": "Turn on Wireless Debugging",
  "createdAt": "2026-04-09T23:11:22.000Z"
}
```

Plain text summary is also supported:

```text
GET ?action=summary&code=abc12&format=text
```

### 3. Message

Method:

```text
GET ?action=message&code=abc12
```

Response type:

```text
text/plain
```

Example body:

```text
!s abc12
Summary: Wireless Debugging is turned off. No authorized ADB headset connection is active.
Created: 2026-04-09T23:11:22.000Z
Current step: Turn on Wireless Debugging
ADB: Not connected
Developer mode: On
Wireless Debugging: Off
Beat Saber: com.beatgames.beatsaber 1.40.0
Mods (3): SongCore, BS Utils, Qosmetics
Logs: 1 error(s), 4 warning(s), 137 line(s)
Viewer: https://.../exec?action=view&code=abc12
Summary API: https://.../exec?action=summary&code=abc12
```

This is the endpoint your bot bridge should fetch and relay into chat.

Public route form:

```text
GET https://logs.sm0ke.org/message/abc12
```

Direct GAS form:

```text
GET https://script.google.com/macros/s/AKfycbyS2gK65EMJxFi5_yzOZtBNpXRF-AOqfVIeo-aoMRNseZ62oSDuJkyBfWulY_dDoAs60Q/exec?action=message&code=abc12
```

### 4. Viewer

Method:

```text
GET ?action=view&code=abc12
```

Response type:

```text
text/html
```

The viewer renders:

- short summary
- inferred issue list
- current setup step
- Beat Saber and mod state
- recent warning/error lines
- full raw app logs
- full raw uploaded JSON

### 5. Data

Method:

```text
GET ?action=data&code=abc12
```

Response type:

```json
{
  "ok": true,
  "code": "abc12",
  "createdAt": "...",
  "summary": "...",
  "issues": [],
  "payload": {}
}
```

Use this when you want your own tooling to inspect the full stored bundle.

Public route form:

```text
GET https://logs.sm0ke.org/data/abc12
```

Direct GAS form:

```text
GET https://script.google.com/macros/s/AKfycbyS2gK65EMJxFi5_yzOZtBNpXRF-AOqfVIeo-aoMRNseZ62oSDuJkyBfWulY_dDoAs60Q/exec?action=data&code=abc12
```

## Storage model

The script stores uploads in a Google Drive folder named:

```text
MBF Tools Shared Logs
```

Each upload is written as one JSON file named:

```text
<code>.json
```

The script also stores a code-to-file-ID index in Apps Script `ScriptProperties` for faster lookup.

## Setup with clasp

### 1. Install dependencies

From `/api`:

```powershell
npm install
```

### 2. Log in

```powershell
npm run login
```

### 3. Create the Apps Script project

If you do not already have a standalone script:

```powershell
npx clasp create --type standalone --title "MBF Tools Logs API" --rootDir src
```

That command will create or replace `.clasp.json`.

If you already have a script, keep this repo’s structure and edit `/api/.clasp.json` manually:

```json
{
  "scriptId": "YOUR_REAL_SCRIPT_ID",
  "rootDir": "src"
}
```

### 4. Push the code

```powershell
npm run push
```

### 5. Deploy as a web app

In Apps Script or with clasp:

```powershell
npm run deploy
```

Recommended web-app settings:

- Execute as: `Me`
- Who has access: `Anyone`

The Quest app is posting directly to the web app, so the deployment must be reachable without the user signing into your Google account.

### 6. Android app

The current Android app build is hardcoded to the deployed web-app `/exec` URL. You do not need to paste the URL into the app anymore.

## How the Android app uses it

The Android app posts to the base web-app URL only. It does not append `action=` on upload.

Expected flow:

1. User opens setup, home, or advanced.
2. User presses `Share Debug Logs`.
3. App uploads diagnostics JSON to the hardcoded `/exec` URL.
4. Backend returns `code`, `command`, `viewerUrl`, `summaryUrl`, and `messageUrl`.
5. App shows the short support message, for example `!s abc12`.

## Bot bridge notes

If you are writing a bot that takes `!s abc12` and posts details:

1. Extract the code.
2. Fetch:

```text
GET https://script.google.com/macros/s/AKfycbyS2gK65EMJxFi5_yzOZtBNpXRF-AOqfVIeo-aoMRNseZ62oSDuJkyBfWulY_dDoAs60Q/exec?action=message&code=abc12
```

3. Relay that body directly into chat.

If you only need a short answer:

```text
GET https://script.google.com/macros/s/AKfycbyS2gK65EMJxFi5_yzOZtBNpXRF-AOqfVIeo-aoMRNseZ62oSDuJkyBfWulY_dDoAs60Q/exec?action=summary&code=abc12
```

If you need the full payload:

```text
GET https://script.google.com/macros/s/AKfycbyS2gK65EMJxFi5_yzOZtBNpXRF-AOqfVIeo-aoMRNseZ62oSDuJkyBfWulY_dDoAs60Q/exec?action=data&code=abc12
```

## Bot script example

A ready-to-adapt script for the helper environment you described is in:

[logs_lookup.py](C:/Users/jackp/Documents/GitHub/QuestDevSettings/api/examples/logs_lookup.py)

Recommended trigger config:

- event: `message`
- regex: `^!s\\s+([A-Za-z0-9]{4,16})$`
- channels: support channels only

What the script does:

- reacts with a lookup emoji
- fetches the GAS `?action=message&code=<code>` endpoint
- replies with the formatted support message
- falls back to the summary endpoint if the message endpoint fails
- links the viewer route if the code is missing or broken

If you want to paste the script directly into your bot system, use this:

```python
LOOKUP_EMOJI = "🔎"
SUCCESS_EMOJI = "✅"
FAILURE_EMOJI = "❌"
MESSAGE_LIMIT = 1800
GAS_BASE_URL = "https://script.google.com/macros/s/AKfycbyS2gK65EMJxFi5_yzOZtBNpXRF-AOqfVIeo-aoMRNseZ62oSDuJkyBfWulY_dDoAs60Q/exec"

def _status(response):
    if isinstance(response, dict):
        return int(response.get("status") or response.get("status_code") or 0)
    return int(getattr(response, "status", getattr(response, "status_code", 0)) or 0)

def _body(response):
    if isinstance(response, dict):
        return (
            response.get("body")
            or response.get("text")
            or response.get("content")
            or ""
        )
    return (
        getattr(response, "body", None)
        or getattr(response, "text", None)
        or str(response)
    )

def _send_long_reply(text):
    remaining = (text or "").strip()
    while remaining:
        chunk = remaining[:MESSAGE_LIMIT]
        if len(remaining) > MESSAGE_LIMIT:
            split_at = chunk.rfind("\\n")
            if split_at > 400:
                chunk = chunk[:split_at]
        reply(chunk)
        remaining = remaining[len(chunk):].lstrip()

def _looks_missing(text):
    normalized = (text or "").strip().lower()
    if not normalized:
        return True
    return (
        "no shared log was found" in normalized
        or "\"ok\":false" in normalized
        or "\"ok\": false" in normalized
        or normalized == "null"
    )

def _request_succeeded(status, body):
    normalized_body = (body or "").strip()
    if not normalized_body:
        return False
    if _looks_missing(normalized_body):
        return False
    return status == 0 or 200 <= status < 300

async def _maybe_await(value):
    if hasattr(value, "__await__"):
        return await value
    return value

async def _main():
    code = (match.group(1) if match else "").lower().strip()
    if not code:
        reply("Usage: `!s <code>`")
        return

    react(LOOKUP_EMOJI)
    try:
        message_response = await _maybe_await(
            http_request(
                f"{GAS_BASE_URL}?action=message&code={code}",
                timeout=15,
            )
        )
        message_status = _status(message_response)
        message_body = _body(message_response).strip()

        if _request_succeeded(message_status, message_body):
            _send_long_reply(message_body)
            react(SUCCESS_EMOJI)
            return

        summary_response = await _maybe_await(
            http_request(
                f"{GAS_BASE_URL}?action=summary&code={code}&format=text",
                timeout=15,
            )
        )
        summary_status = _status(summary_response)
        summary_body = _body(summary_response).strip()

        if _request_succeeded(summary_status, summary_body):
            reply(
                f"Could not fetch the full message view for `{code}`.\\n\\n"
                f"{summary_body}\\n\\n"
                f"Viewer: https://logs.sm0ke.org/{code}"
            )
        else:
            reply(f"Could not find shared logs for `{code}`.\\n\\nViewer: https://logs.sm0ke.org/{code}")
        react(FAILURE_EMOJI)
    finally:
        remove_reaction(LOOKUP_EMOJI)

try:
    asyncio.get_running_loop().create_task(_main())
except RuntimeError:
    asyncio.run(_main())
```

## Local testing

After deploying, test with PowerShell:

```powershell
$url = "https://script.google.com/macros/s/DEPLOYMENT_ID/exec"
$payload = @{
  app = @{
    packageName = "org.sm0ke.mbftools"
    versionName = "1.0"
    setupComplete = $false
  }
  setup = @{
    currentGuideStep = "Turn on Wireless Debugging"
    developerModeEnabled = $true
    wirelessDebuggingEnabled = $false
    connectedDevice = ""
    pairingPort = "41619"
    debugPort = ""
  }
  beatSaber = @{
    packageName = "com.beatgames.beatsaber"
    installed = $true
    versionName = "1.40.0"
  }
  mods = @{
    count = 0
    items = @()
  }
  logStats = @{
    lineCount = 10
    warnCount = 1
    errorCount = 0
    recentProblems = @("12:00:00 [WARN/Guide] Wireless debug port missing.")
  }
  logs = @("12:00:00 [INFO/Guide] Showing step 6/8: Turn on Wireless Debugging")
  logsText = "12:00:00 [INFO/Guide] Showing step 6/8: Turn on Wireless Debugging"
} | ConvertTo-Json -Depth 8

Invoke-RestMethod -Uri $url -Method Post -ContentType "application/json" -Body $payload
```

Then open the returned viewer URL.

## Operational notes

- Apps Script does not give you normal HTTP status control for `doGet` and `doPost`, so this backend always returns JSON with `ok: true/false`.
- The current summary is heuristic. If you want different failure wording, edit `analyzePayload_()` in `src/Code.js`.
- All storage is in your Google Drive account. If you want retention controls, add a cleanup function that deletes older files from the `MBF Tools Shared Logs` folder.
- The best extension points are `analyzePayload_()` for diagnosis and `buildMessageText_()` for the bot output format.
