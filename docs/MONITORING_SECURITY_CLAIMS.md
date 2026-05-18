# Monitoring and Privacy Boundary

Mobile Bug Agent monitors crash and error diagnostics. It is not a user
behavior analytics SDK, session replay tool, screen recorder, or network
inspection tool.

This document describes what MBA collects, what it does not collect, and the
security boundary teams should understand before enabling SDKOnly analysis,
hosted upload, Notion, or GitHub delivery.

## Summary

MBA captures technical failure context so developers can debug crashes, ANRs,
and explicitly reported non-fatal errors.

MBA does not intentionally collect private data sources such as contacts,
location, photos, databases, cookies, auth tokens, network payloads, rendered UI
text, screenshots, or screen recordings.

There is one important caveat: crash context is text supplied by the runtime and
the host app. Sensitive values can appear in a crash report if the app includes
them in exception messages, stack traces, breadcrumbs, screen names, ANR traces,
or custom metadata. MBA includes redaction guardrails, but no crash SDK can
honestly guarantee that app-provided crash text never contains sensitive data.

## Failure Signals MBA Handles

MBA currently handles:

- fatal uncaught JVM/Kotlin exceptions
- non-fatal errors explicitly reported with `MBA.logError(...)`
- coroutine exceptions routed through the MBA exception helper
- ANR process exits on Android 11/API 30+ after the app restarts

Native crash capture is not currently included.

## Crash Context Collected

MBA may record:

- crash id
- timestamp
- exception type
- exception message
- stack trace
- thread name
- fatal or non-fatal flag
- ANR exit metadata when Android provides it
- package name or app id
- app version
- build type or environment
- OS version
- Android SDK version
- device manufacturer and model
- screen name, only if the app sets it
- breadcrumbs, only if the app adds them
- custom metadata, only if the app provides it
- routing flags such as SDKOnly/hosted mode or optional delivery settings
- grouping metadata such as fingerprint, bug group id, occurrence count, first
  seen, and last seen
- external ticket URLs or ids when Notion/GitHub delivery is enabled

## Data MBA Does Not Intentionally Collect

MBA does not intentionally collect:

- screenshots
- screen recordings
- rendered UI text
- tap coordinates
- full navigation history
- product analytics event streams
- user behavior sessions
- network requests or responses
- request/response headers
- cookies
- auth tokens
- app database contents
- arbitrary app files
- contacts
- photos or media library content
- microphone audio
- camera images or video
- Bluetooth data
- sensor streams
- GPS/location
- phone number
- IMEI
- Android ID
- advertising ID
- account email
- payment data

MBA also does not require a user id to group crashes. Grouping is based on crash
fingerprint, app id, and environment.

## Sensitive Data Risk

The main risk is app-provided crash text.

Avoid this:

```kotlin
MBA.setScreen("Checkout/user=jane@example.com")
MBA.addBreadcrumb("Bearer token: eyJhbGciOi...")
MBA.logError(error, metadata = mapOf("phone" to "+1 555 123 4567"))
IllegalStateException("Payment failed for card 4242 4242 4242 4242")
```

Prefer this:

```kotlin
MBA.setScreen("CheckoutScreen")
MBA.addBreadcrumb("Tapped Pay")
MBA.logError(error, metadata = mapOf("flow" to "checkout"))
IllegalStateException("Payment token missing")
```

Treat crash context like logs. Do not place secrets, raw personal data, payment
data, auth tokens, or private content in exception text, breadcrumbs, screen
names, or custom metadata.

## Redaction Guardrails

`mba-core` includes `PIISanitizer`, a regex-based scrubber for common patterns.

It can redact patterns such as:

- email addresses
- phone-like numbers
- payment-card-like numbers
- bearer tokens
- IPv4 addresses
- IPv6 addresses
- app-provided custom regex patterns

Redaction is a guardrail, not a guarantee. Regex redaction cannot prove that all
sensitive values have been removed. The safest approach is to avoid collecting
sensitive values in crash context at the source.

## LLM and Agent Analysis Boundary

In SDKOnly agent mode, Koog/LLM analysis receives crash-context data. It does
not inspect arbitrary app data.

It may analyze:

- exception type and message
- sanitized stack trace
- breadcrumbs added by the app
- screen name set by the app
- app/device/build context
- parsed crash location

It does not analyze:

- screenshots
- contacts
- location
- databases
- network payloads
- private files
- live screen contents
- user accounts

If crash context contains sensitive values because the app provided them, those
values may be included in the data sent to the configured LLM provider. In
SDKOnly mode, the host app owns that provider configuration and key.

## Data Destination by Mode

### SDKOnly Callback-Only

Crash reports are processed locally and emitted to app callbacks or flows. The
host app decides whether to store, upload, or discard the payload.

### SDKOnly With Agent Analysis

Crash context is sent to the app-configured LLM provider, model, endpoint, or
local model runtime for analysis. The processed result is returned to the app
and optional local sinks.

### SDKOnly With Notion or GitHub

The app sends processed or raw-fallback bug data to the registered Notion or
GitHub module. Provider credentials are owned by the app.

### Hosted Backend

The app sends raw crash reports to the configured MBA backend. Backend-owned
services handle analysis, grouping, and external ticket sync.

## Suggested Public Description

Use this wording when describing the SDK:

> Mobile Bug Agent captures technical crash and error diagnostics, groups
> repeated failures, and can analyze crash context into developer-ready bug
> reports. It does not intentionally collect screenshots, contacts, location,
> network payloads, databases, media, cookies, auth tokens, or user behavior
> sessions. Crash reports may still contain sensitive values if the host app
> puts them in exception messages, stack traces, breadcrumbs, screen names, ANR
> traces, or custom metadata, so teams should treat crash context like logs.

## Security Review Answer

If asked whether MBA monitors sensitive information, the accurate answer is:

> MBA monitors crash and error diagnostics, not user behavior. By default it
> records exception data, stack traces, app/device diagnostics, and app-provided
> context such as screen names, breadcrumbs, and custom metadata. It does not
> intentionally collect screenshots, contacts, location, network payloads,
> cookies, databases, media, or advertising identifiers. Sensitive values can
> still appear if the host app places them in crash text or custom context. MBA
> provides redaction guardrails, and app teams should avoid adding secrets or
> raw personal data to crash context.

## Design Principle For New Diagnostic Fields

New fields should follow this rule:

```text
Collect the minimum technical context needed to debug or group the failure.
Do not collect private user data sources unless the app explicitly provides a
sanitized, purpose-specific value.
```

Before adding a field, evaluate:

- Is it necessary to debug or group crashes?
- Can it identify a person?
- Can it reveal a secret?
- Can it be represented as a sanitized category instead of a raw value?
- Should it be opt-in?
- Should it be redacted before storage, LLM analysis, backend upload, or
  external ticket delivery?

If the answer is unclear, do not collect the field by default.
