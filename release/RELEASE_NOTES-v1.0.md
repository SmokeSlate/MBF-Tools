# MBF Tools and Setup v3.2

APK: `MBF-Tools-and-Setup-v1.0-release.apk`

SHA256:

```text
CCEFA6F11855AF29F04960B431A00943DE7334442AFEB981CC8A4A9C8C137103
```

## Highlights

- Integrated MBF launcher flow into one app.
- Added a guided Quest setup flow for enabling Developer Mode, Wireless Debugging, pairing, and launching MBF.
- Added post-setup dashboard with support links, FAQ access, fix form access, and MBF launch access.
- Added Wireless Debugging required screen after setup when Wireless Debugging is turned off.
- Added debug log sharing through the Cloudflare Worker backend.
- Added support bot lookup script for `!s <code>`.
- Added Beat Saber diagnostics collection for installed version, current mods, mod folders, and Beat Saber logs.
- Added advanced tools for ADB actions, device settings presets, reset flow, and scrolling logs.
- Added Advanced device performance controls for refresh rate, CPU level, GPU level, foveation, texture size, and performance presets.

## Fixes

- Fixed Beat Saber mod detection using `ModData/com.beatgames.beatsaber/Packages/<version>_<versionCode>`.
- Fixed Beat Saber log collection from `logs` and `logs2`.
- Fixed ADB shell argument handling so directory probes do not run against the wrong path.
- Fixed wireless debug reconnect behavior by re-detecting the debug port after pairing or connection loss.
- Fixed startup routing when Wireless Debugging is disabled after setup.
- Fixed several relaunch/back-stack crashes when opening the app while another app is active.
- Fixed debug log export hanging cases and added better progress/error handling.
- Fixed release lint compatibility issues for API 21+.

