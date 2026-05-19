# Mobile Bug Agent Documentation

Mobile Bug Agent helps mobile teams turn crashes, ANRs, and explicitly reported
non-fatal errors into grouped bug reports. These docs explain how the SDK works,
what the local agent does, and what diagnostic data is collected.

## Recommended Reading Order

1. `SDKONLY_IMPLEMENTATION_GUIDE.md`

   Start here to add Mobile Bug Agent to an Android app in SDKOnly mode. It
   covers dependencies, initialization, callbacks, raw fallback, optional
   Notion/GitHub delivery, and release-build notes.

2. `MBA_AGENT_SDKONLY.md`

   Read this for the SDKOnly processing model and future direction.

3. `GITHUB_PACKAGES_SDK.md`

   Read this to publish or consume pre-release SDK artifacts from GitHub
   Packages before Maven Central is available.

4. `NOTION_INTEGRATION.md` and `GITHUB_INTEGRATION.md`

   Read these only if the app wants MBA to write directly to Notion or GitHub.
   Callback-only mode does not require either integration.

5. `MOBILE_AGENT_AND_CORE_OVERVIEW.md`

   Read this for the full architecture. It explains how the Android adapter,
   shared core, local agent, and optional integrations work together.

6. `MONITORING_SECURITY_CLAIMS.md`

   Read this for the monitoring and privacy boundary. It explains what MBA
   collects, what it does not collect, and where app-provided crash text can
   still contain sensitive values.

7. `ARCHITECTURE.md`

   Read this for the full repository architecture across SDK modules, server,
   Notion, GitHub, future platform scaffolds, and the demo pipeline.

8. `KOOG_AGENT_ROADMAP.md`

   Read this for the Koog-backed MBA analysis and auto-fix roadmap.

Full KMP structure planning is tracked in
[#79](https://github.com/shunneklabs/mobile-bug-agent/issues/79).

## Current Scope

- Fatal JVM/Kotlin crash capture
- Explicit non-fatal error reporting
- Coroutine exception helper
- Android ANR exit detection on Android 11/API 30+ after app restart
- SDKOnly local analysis and raw fallback
- Local duplicate grouping
- App-layer callbacks and JSON payloads
- Optional Notion and GitHub delivery modules
- Local sample app using project modules for repo development
- Optional hosted backend upload
- Placeholder iOS and Web/Wasm modules for future KMP expansion

Native crash capture, iOS crash capture, Web/Wasm crash capture, Slack
notifications, and full auto-fix PR creation are not part of the current public
SDK scope.
