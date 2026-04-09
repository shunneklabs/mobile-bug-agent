# MBA Sample app

## Where to put keys
Create a `local.properties` file at the **repo root** (same folder as `settings.gradle.kts`).

Example:
```properties
NOTION_TOKEN=ntn_secret_xxx
NOTION_CRASH_DB_ID_OR_URL=<paste Crash Reports DB url or id>
NOTION_TICKET_DB_ID_OR_URL=<paste Bug Tickets DB url or id>
GEMINI_API_KEY=AIzaSy...
```

The sample app reads these values into `BuildConfig` fields and uses them in `SampleApplication`.

## Run
- Select the `mba-sample` run configuration and install on device/emulator.
- Tap a crash button.
- Re-open the app.
- Check Notion:
  - a Crash Report row is created/updated
  - a Bug Ticket is created and linked
