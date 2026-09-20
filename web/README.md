# Bundled MBF page

The in-headset Android WebView cannot expose WebUSB. MBF Tools launches a local
ADB WebSocket bridge, so its MBF page must use the bridge-aware frontend. The
official MBF deployment has the AXML fix but currently does not recognize that
bridge; the deployed fork recognizes the bridge but carries an older agent.

The Pages workflow builds the bridge-aware frontend from
[`DanTheMan827/ModsBeforeFriday` commit `a9722024aef1552986d00d6f89e454cb049211ee`](https://github.com/DanTheMan827/ModsBeforeFriday/tree/a9722024aef1552986d00d6f89e454cb049211ee)
and includes the AXML-fixed agent currently published by
[`Lauriethefish/ModsBeforeFriday`](https://github.com/Lauriethefish/ModsBeforeFriday).
The agent SHA-1 and size are pinned in `agent_manifest.ts`; the deployment also
checks its SHA-256 and fails if the downloaded binary changes. Both MBF repositories provide the corresponding
AGPL-3.0 source. Update these pins deliberately when upstream changes.
