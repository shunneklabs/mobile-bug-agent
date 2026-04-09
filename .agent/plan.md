# Project Plan

Mobile Bug Agent (MBA) is an automated crash analysis and bug reporting SDK. It captures crashes, analyzes them on-device or via a backend using AI, and creates bug tickets. It's built with Kotlin Multiplatform. Support for Android, iOS, and JVM is required. A Ktor server module is also needed for centralized log collection. Use `kotlin.time` for all time-related operations.

## Project Brief

# Project Brief: Mobile Bug Agent (MBA)

Mobile Bug Agent (MBA) is an automated crash analysis and bug reporting SDK designed for a true multiplatform experience. It intercepts application crashes across various environments, processes them with AI to generate actionable insights, and synchronizes reports with developer-focused backends.

### Features
*   **True Multiplatform Crash Capture**: Unified SDK support for capturing and handling crashes across Android, iOS, and JVM applications.
*   **AI-Driven Root Cause Analysis**: Leverages on-device or backend AI agents to analyze stack traces and context, providing a detailed explanation and potential fixes.
*   **Automated Ticket Lifecycle**: Automatically generates and populates bug tickets in backends like Notion, including severity, reproduction steps, and technical metadata.
*   **Centralized Ktor Backend**: Includes a dedicated Ktor server module for centralized log collection, remote AI processing, and third-party API orchestration.

### High-Level Technical Stack
*   **Kotlin Multiplatform (KMP)**: The core architecture for sharing logic across Android, iOS, and JVM.
*   **Jetpack Compose & Compose Multiplatform**: Declarative UI for the Android sample app and potentially shared UI components.
*   **Ktor (Client & Server)**: Used for both multiplatform networking and the centralized backend reporting server.
*   **Kotlin Coroutines & Flow**: For managing asynchronous crash processing and data streams across all platforms.
*   **Kotlin Time (`kotlin.time`)**: Utilized for precise, multiplatform-compatible handling of time, clocks, and timestamps.
*   **KSP (Kotlin Symbol Processing)**: Efficient code generation for serialization and other compile-time tasks.

## Implementation Steps
**Total Duration:** 1h 13m 34s

### Task_1_KMP_Core_And_Crash_Capture: Set up the multi-module KMP architecture (:mba-core, :mba-android, :mba-jvm). Implement unified crash capture interfaces, PII scrubbing logic, and SHA-256 deduplication in :mba-core. Implement platform-specific capture (UncaughtExceptionHandler) for Android and JVM, utilizing 'kotlin.time' for all timestamps.
- **Status:** COMPLETED
- **Updates:** Task 1 is completed. 
- Refactored MBA.kt to be multiplatform (String-based paths, @Volatile).
- Implemented actual object CrashWriter for Android and JVM in mba-core.
- Implemented PlatformInitializer and JVMCrashHandler.
- Aligned MBACrashHandler and AndroidContextCollector with the new models.
- Implemented PIISanitizer and SHA-256 based CrashFingerprint.
- All core models (RawCrashReport, ProcessedCrashReport, etc.) are implemented using 'kotlin.time'.
- **Acceptance Criteria:**
  - KMP modules are correctly configured in settings.gradle.kts
  - Crash capture successfully intercepts exceptions on both Android and JVM
  - PII scrubbing removes sensitive data from traces
  - All time-related logic uses 'kotlin.time' API
- **Duration:** 1h 13m 34s

### Task_2_AI_Agent_And_Ktor_Server_Backend: Implement the AI analysis logic in :mba-agent and create the :mba-server (Ktor Server) module for centralized log collection and API orchestration. Integrate the Notion API for ticket generation. Ensure the server can receive logs and trigger AI analysis for bug reports.
- **Status:** COMPLETED
- **Updates:** Task 2 is completed.
- Implemented `CrashAnalysisAgent` in `:mba-agent` with structured AI analysis pipeline (PII -> Fingerprint -> Dedup -> LLM).
- Implemented `AgentFactory` and `KoogCrashAnalysisExecutor` to interface with AI models.
- Created `:mba-server` using Ktor with a POST `/report` endpoint for centralized crash handling.
- Implemented `NotionTicketBackend` in `:mba-notion` for automated bug ticket creation.
- Successfully integrated AI analysis with Notion ticket generation in the server module.
- **Acceptance Criteria:**
  - Ktor Server module (:mba-server) successfully receives and stores crash logs
  - :mba-agent generates structured AI reports (title, severity, root cause)
  - Notion API key and Database ID are integrated; reports are successfully posted to Notion
  - Server-side orchestration for third-party APIs is functional
- **Duration:** 45m 20s

### Task_3_Sample_App_Integration_And_UI: Integrate the MBA SDK into the sample app. Implement a Material 3 UI with a vibrant, energetic color scheme and full edge-to-edge display. Create a dashboard to trigger test crashes and monitor reporting status. Generate and apply an adaptive app icon.
- **Status:** IN_PROGRESS
- **StartTime:** 2026-04-09 10:15:00 CEST

### Task_4_Run_And_Verify: Perform a final run and end-to-end verification of the multiplatform crash reporting pipeline (Android/JVM -> Ktor Server -> Notion). Instruct critic_agent to verify application stability, confirm alignment with user requirements, and report any UI issues.
- **Status:** PENDING
- **Acceptance Criteria:**
  - End-to-end flow from crash to Notion ticket works for both Android and JVM
  - Application builds successfully, all tests pass, and it does not crash during normal use
  - UI matches Material 3 and vibrant theme requirements
  - Critic agent confirms stability and requirement alignment

