# QuestDevSettings
Single-app Quest integration for [mbf-launcher](https://github.com/DanTheMan827/mbf-launcher).

This app now combines:

- Quest settings shortcuts for `Developer Settings`, `Wireless Debugging`, and Settings app info.
- On-device ADB pairing and wireless debug connection.
- The upstream MBF native bridge binaries.
- An in-app WebView that loads the live MBF web app through the bridge.

Update behavior:

- The MBF web UI still comes from the upstream hosted app URL, so MBF site updates continue flowing into this app automatically.
- Native launcher pieces can be refreshed from upstream with `.\sync-mbf-launcher.ps1`.

Build and install:

```powershell
.\build-and-push.ps1
```
