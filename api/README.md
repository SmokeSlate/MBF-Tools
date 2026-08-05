# MBF Tools Logs API

Cloudflare Worker backend for diagnostic uploads from the MBF Tools Android app.
The Worker runs directly from `api/handler.js`; there is no application build step.

## Public endpoints

| Method | Route | Purpose |
| --- | --- | --- |
| `POST` | `/` | Upload a diagnostic JSON object and receive a five-character code |
| `GET` | `/<code>` | Interactive diagnostic viewer |
| `GET` | `/summary/<code>` | JSON summary (`?format=text` for plain text) |
| `GET` | `/message/<code>` | Discord-friendly plain text |
| `GET` | `/data/<code>` | Full stored diagnostic record |
| `GET` | `/aifix/<code>` | Short AI-generated fix suggestions |
| `GET` | `/diagnose/<code>` | Agentic diagnosis (requires OpenRouter) |
| `POST` | `/followup/<code>` | Follow-up diagnosis question |
| `GET` | `/admin` | Password-protected log management UI |

The legacy query form remains supported, for example
`/?action=summary&code=abc12`.

Production base URL: `https://logs.mbf.tools`

## Storage

The `LOGS` Cloudflare KV binding stores records under `log:<code>` for 30 days.
Every write includes `{ createdAt, summary }` KV metadata so the admin list does
not need to fetch every record.

## Required secrets

Admin authentication deliberately has no source-controlled fallback. Configure
both secrets before deploying:

- `ADMIN_PASSWORD_HASH`: lowercase SHA-256 hex digest of the admin password.
- `ADMIN_SESSION_SECRET`: at least 32 random characters used to sign expiring
  admin session cookies.

`OPENROUTER_API_KEY` is optional and enables `/diagnose` and `/followup`.

PowerShell setup:

```powershell
$securePassword = Read-Host "Admin password" -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $passwordBytes = [Text.Encoding]::UTF8.GetBytes($plainPassword)
    $passwordHash = [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($passwordBytes)
    ).ToLowerInvariant()
    $passwordHash | npx wrangler secret put ADMIN_PASSWORD_HASH
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    Remove-Variable plainPassword, passwordBytes, passwordHash -ErrorAction SilentlyContinue
}

$sessionSecret = [Convert]::ToHexString(
    [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
).ToLowerInvariant()
$sessionSecret | npx wrangler secret put ADMIN_SESSION_SECRET
Remove-Variable sessionSecret

npx wrangler secret put OPENROUTER_API_KEY # optional
```

Changing `ADMIN_SESSION_SECRET` immediately invalidates all existing admin
sessions. Changing `ADMIN_PASSWORD_HASH` changes the login password.

## Verify and deploy

```powershell
cd api
npm ci
npm run check
npm run deploy
```

`npm run check` validates JavaScript syntax and performs a Wrangler dry-run. It
does not modify the deployed Worker.

## Upload contract

```http
POST /
Content-Type: application/json
```

The body must be one JSON object. A successful response resembles:

```json
{
  "ok": true,
  "code": "abc12",
  "command": "!s abc12",
  "summary": "Wireless Debugging is turned off.",
  "viewerUrl": "https://logs.mbf.tools/abc12",
  "summaryUrl": "https://logs.mbf.tools/summary/abc12",
  "messageUrl": "https://logs.mbf.tools/message/abc12",
  "dataUrl": "https://logs.mbf.tools/data/abc12"
}
```

The Android client sends app/device state, ADB setup state, Beat Saber package
and mod details, and bounded diagnostic logs. Do not add secrets or user account
credentials to the payload.

## Legacy backend

`src/Code.js` is the retired Google Apps Script implementation kept only for
reference. It is not deployed and must not be used for new changes.
