# Publishing a beta

- Build from `beta`. Use version names such as `3.6.4-beta.1` and a strictly
  increasing Android `versionCode` for every published APK (first beta: 47).
- Run `build-release.ps1` on the Windows account with the existing protected
  signing configuration. Do not rotate keys again or publish debug-signed APKs.
- Verify the APK package, version name/code, old-to-new signing lineage and
  checksum before uploading. Existing stable installations must be able to
  install it without losing data.
- Publish a GitHub **prerelease**, tagged exactly like the APK version name, with
  the signed APK and its SHA-256 checksum. Keep it a draft until all assets have
  uploaded; do not mark it as the latest stable release.
- Dispatch the existing Pages workflow with ref `beta` after publication. The
  workflow builds the stable main site's root and MBF web app, then overlays
  only `docs/beta/` from the beta branch at `/beta/`.
- Verify `https://mbf.tools/beta/`, the download URL, and the APK metadata after
  deployment. Run `node --test scripts/test-beta-page.cjs` for page selection
  tests and the release unit tests/lint for the updater.

The Pages workflow's beta overlay should also be brought into `main` so that
future stable-site deployments keep `/beta/`. Until then, dispatching the old
main workflow would remove the beta page; redispatch the beta workflow afterward.

Beta apps select the highest published `-beta` prerelease containing an APK not labeled debug or unsigned. Stable apps continue using GitHub's latest stable release endpoint. A beta
counter increase is a real update even when the numeric core stays the same.
Use an actual headset to validate beta.1 to beta.2 installation with the same
signing lineage and increasing versionCode. Do not advertise device validation
as complete based only on unit tests.
