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

3. `MOBILE_AGENT_AND_CORE_OVERVIEW.md`

   Read this for the full architecture. It explains how the Android adapter,
   shared core, local agent, and optional integrations work together.

4. `MONITORING_SECURITY_CLAIMS.md`

   Read this for the monitoring and privacy boundary. It explains what MBA
   collects, what it does not collect, and where app-provided crash text can
   still contain sensitive values.

5. `ARCHITECTURE.md`

   Read this for the full repository architecture across SDK modules, server,
   Notion, GitHub, and the demo pipeline.

6. `KOOG_AGENT_ROADMAP.md`

   Read this for the Koog crash-analysis and auto-fix roadmap.

## Current Scope

- Fatal JVM/Kotlin crash capture
- Explicit non-fatal error reporting
- Coroutine exception helper
- Android ANR exit detection on Android 11/API 30+ after app restart
- SDKOnly local analysis and raw fallback
- Local duplicate grouping
- App-layer callbacks and JSON payloads
- Optional Notion and GitHub delivery modules
- Optional hosted backend upload

Native crash capture is not part of the current public scope.
