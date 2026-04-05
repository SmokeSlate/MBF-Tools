# MBF Tools and Setup
Single-app Quest setup, support, and MBF integration for [mbf-launcher](https://github.com/DanTheMan827/mbf-launcher).

This app now combines:

- A guided on-headset setup flow for Android dev mode, Wireless Debugging, pairing, and launching MBF.
- A built-in support hub with the live FAQ and fix form.
- On-device ADB pairing and wireless debug connection.
- The upstream MBF native bridge binaries.
- An in-app WebView that loads the live MBF web app through the bridge.


Build and install:

```powershell
.\build-and-push.ps1
```

Build release APK:

```powershell
.\build-release.ps1
```
