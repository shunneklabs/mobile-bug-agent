# Notion Integration Guide

This guide explains how an app team connects Mobile Bug Agent SDKOnly mode to
their own Notion workspace.

MBA does not require access to an MBA-owned Notion database. The app owner
creates or chooses a Notion database, grants an integration access to it, and
passes the database id to the SDK.

## What The Notion Module Does

`mba-notion` implements `TicketBackend`.

In SDKOnly mode it can:

- create one parent bug page for a new crash group
- update the parent page when the same crash happens again
- write Koog fields such as severity, confidence, steps to reproduce, and
  possible cause when the Notion database has matching properties
- still create a useful page body when the database only has a title property

## 1. Add The Dependency

Published SDK:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-android:<version>")
    implementation("dev.sunnat629.mba:mba-notion:<version>")
}
```

Repository build:

```kotlin
dependencies {
    implementation(project(":mba-android"))
    implementation(project(":mba-notion"))
}
```

## 2. Create A Notion Integration Token

1. Create an internal Notion integration in your Notion workspace.
2. Copy the integration secret/token.
3. Store it outside source control, for example in `local.properties`,
   CI secrets, encrypted remote config, or your app's own secret system.

Sample `local.properties`:

```properties
NOTION_TOKEN=ntn_your_integration_token
```

Do not commit the token.

## 3. Create Or Choose A Bug Database

Create a Notion database for grouped bugs. The minimum supported database only
needs a title property. MBA will write the full crash report into the page body
even when structured properties are missing.

Recommended title property:

| Property | Type | Required |
|---|---|---|
| `Name` | Title | Yes |

Recommended rich schema:

| Property | Type | Purpose |
|---|---|---|
| `Name` | Title | Bug title |
| `Severity` | Select | `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` |
| `Fingerprint` | Text | Stable crash grouping key |
| `Description` | Text | Koog or fallback summary |
| `Affected Screen` | Text | Current screen, when available |
| `Device Matrix` | Text | Device or device matrix |
| `AI Confidence` | Number | Koog confidence, for example `0.86` |
| `App Version` | Text | App version from the SDK |
| `Occurred At` | Date | First or latest occurrence time |
| `OS Version` | Text | Android version/API |
| `Device Model` | Text | Manufacturer/model |
| `Occurrences` | Number | Number of grouped occurrences |
| `Unique Devices` | Number | Unique device count |
| `Bug Type` | Select | `Bug Group` |
| `Status` | Status | `New`, `Triaged`, `In Progress`, etc. |
| `External Sync State` | Select | `Notion Created`, `GitHub Created`, `Both Created` |
| `First Seen` | Date | First occurrence time |
| `Last Seen` | Date | Latest occurrence time |
| `Device ID Hash` | Text | Stable per-device grouping hash |
| `Possible Cause` | Text | Koog/fallback cause hypothesis |
| `Steps to Reproduce` | Text | Koog/fallback reproduction steps |
| `GitHub Issue URL` | URL | Optional GitHub issue URL |
| `Notion Ticket URL` | URL | Optional self-reference |

MBA checks the Notion database schema. Unknown or missing properties are
omitted automatically. That means a minimal database works, but the rich schema
gives better filtering and dashboard views.

## 4. Share The Database With The Integration

The integration token can only write to pages/databases that have been shared
with it.

For the Notion database you use:

1. Open the database in Notion.
2. Share or connect the database/page with your Notion integration.
3. Confirm the integration has permission to edit the database.

If the integration is not shared with the database, Notion API calls fail even
when the token is valid.

## 5. Get The Database ID

Open the Notion database in a browser and copy the database URL. Extract the
database id from the URL and pass that id to `NotionTicketBackend`.

The sample app uses:

```properties
NOTION_TICKET_DB_ID_OR_URL=your_bug_database_id_or_url
```

Despite the sample property name, pass the database id for the current SDK.

## 6. Wire The SDK

Create the backend in your app layer:

```kotlin
val notionBackend = NotionTicketBackend(
    apiKey = BuildConfig.NOTION_API_KEY,
    bugTicketDbId = BuildConfig.NOTION_TICKET_DB_ID,
)
```

Register it after `MBA.configure(...)` and before calling
`MBAAndroid.flushPendingCrashes(...)`:

```kotlin
MBAAndroid.setTicketBackends(
    notionBackend = notionBackend,
)
```

A complete SDKOnly setup:

```kotlin
class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        MBAAndroid.install(this)

        val llmConfig = LLM.gemini(BuildConfig.GEMINI_API_KEY)

        MBA.configure(
            MBAConfig.Builder().apply {
                mode = MBAMode.SdkOnly(llm = llmConfig)
                debug = BuildConfig.DEBUG
            }.build(),
        )

        MBAAndroid.setTicketBackends(
            notionBackend = NotionTicketBackend(
                apiKey = BuildConfig.NOTION_API_KEY,
                bugTicketDbId = BuildConfig.NOTION_TICKET_DB_ID,
            ),
        )

        MBAAndroid.saveConfig(
            context = this,
            llm = llmConfig,
            debug = BuildConfig.DEBUG,
        )

        MBAAndroid.flushPendingCrashes(this)
    }
}
```

## 7. Duplicate Behavior

MBA groups crashes by fingerprint.

For a new group:

- Notion creates one parent bug page.

For a duplicate:

- Notion updates the existing parent page when the stored page id exists.
- Occurrence count, unique devices, last seen, device details, and richer Koog
  fields can be patched on the parent page.

It should not create a second parent page for the same `appId + environment +
fingerprint`.

## Troubleshooting

`401 Unauthorized`:

- Token is missing, wrong, revoked, or not loaded into the app.

`404 Not Found`:

- Database id is wrong, or the integration is not shared with the database.

`400 validation_error`:

- The database does not have one or more rich properties. MBA retries with only
  compatible properties and still writes the full report body when possible.

`AI Confidence = 0`:

- Koog analysis failed and MBA used raw fallback. Check callback JSON for
  `analysisSource = RAW_FALLBACK` and `analysisError`.

No Notion page is created:

- Confirm `MBAAndroid.setTicketBackends(notionBackend = ...)` runs before
  `MBAAndroid.flushPendingCrashes(...)`.
- Confirm the sample/app delivery mode is not callback-only.
