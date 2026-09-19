# MBF Tools and Setup v3.6.2

## Fixes

- Fixed the MBF connection loop reported in mbf-launcher issue #18 by loading
  the current MBF deployment and its updated Android manifest parser.
- Fixed release lint failures caused by API 21 compatibility gaps in ADB process
  handling, active-network detection, and dynamic UI colors.
- Added compatible process timeout and termination behavior on older Android
  versions while retaining forceful termination on Android 8 and newer.
- Guarded the unknown-app-sources settings action on Android versions that
  support it.

## Backend and release hardening

- Replaced the reusable admin hash cookie with expiring HMAC-signed sessions.
- Moved the admin password hash and session signing key to Cloudflare Worker
  secrets; admin access now fails closed when they are not configured.
- Added automated admin-authentication tests and a Worker deployment dry-run.
- Updated Wrangler to the current v4 toolchain.
- Release builds now run unit tests and lint, verify the APK signature, and emit
  a versioned APK plus SHA-256 checksum.
- Rotated the exposed Android signing key to a new 4096-bit key using Android's
  proof-of-rotation lineage. Existing Quest installations can carry their app
  data forward, while the previous key has no rollback, shared-UID, signature
  permission, or authenticator capability.

## Deployment note

Before deploying the Worker, configure `ADMIN_PASSWORD_HASH` and
`ADMIN_SESSION_SECRET` as described in `api/README.md`. Rotate the admin password
when deploying this release because the previous password hash was present in
repository history.
