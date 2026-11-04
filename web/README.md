# Bundled MBF page

The in-headset Android WebView cannot expose WebUSB. MBF Tools launches a local
ADB WebSocket bridge, so its MBF page must use the bridge-aware frontend. The
official MBF deployment has the AXML fix but currently does not recognize that
bridge; the deployed fork recognizes the bridge but carries an older agent.

The Pages workflow keeps the bridge-aware frontend pinned to
[DanTheMan827/ModsBeforeFriday a9722024aef1552986d00d6f89e454cb049211ee](https://github.com/DanTheMan827/ModsBeforeFriday/tree/a9722024aef1552986d00d6f89e454cb049211ee).
It builds the official agent from
[Lauriethefish/ModsBeforeFriday 8563ba7cdf6a237201030afbaaaa73615939220f](https://github.com/Lauriethefish/ModsBeforeFriday/tree/8563ba7cdf6a237201030afbaaaa73615939220f)
instead of downloading a mutable deployment URL.

## Integrity and reproducibility

Rust 1.99.1 and NDK r27c/API 31 match the upstream October 21 build. Cargo uses
the committed lockfile and builds offline after fetching dependencies. Embedded
native libraries are SHA-256 pinned; the OVR library URL also pins its source
commit. NDK archive length and the official published checksum are checked.

The first [validation run](https://github.com/SmokeSlate/MBF-Tools/actions/runs/41889908533)
built the agent twice from clean Android outputs. Both builds were identical and
matched the official downloaded binary:
- SHA-256: 6bc70a8870561211a0c51d86c28ae73d234a2c89e5c6abb1bc2241060a1d6882
- SHA-1: 0192c85e4b7eddf9346ac8c0d1d165cd33c6722f
- Length: 14,792,440 bytes

Deployment requires that exact SHA-256 and size, independently checks the
frontend manifest, and checks that the frontend build retained those bytes.
A source, toolchain, dependency, or output change must be reviewed and validated
before updating pins. Never replace a checksum merely to make a download pass.

Both MBF repositories provide the corresponding AGPL-3.0 source. The newer
official source includes cache-corruption recovery and certificate distrust
updates. Request/response models and request-handler source blobs are identical
to the bridge fork and September official source. The bridge frontend compiles;
the upstream Cargo test command passes but contains zero tests. A physical
headset install/modding session has not been verified.

## Deployment validation

Pull requests changing the site, web inputs, or Pages workflow build the complete
Pages artifact without deploying. Only a main-branch push or manual main run can
enter the protected deployment job. The stable site root and beta download
overlay are retained; no beta Android app code is merged into main.
