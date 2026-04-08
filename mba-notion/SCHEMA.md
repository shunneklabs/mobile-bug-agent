# Notion schema (MBA MVP)

MBA MVP uses **two Notion databases**:

1) **Crash Reports DB** (dedup + counting)
2) **Bug Tickets DB** (developer-facing tickets)

The code assumes the following property names.

## Crash Reports DB
Required properties:
- **Title** (Title)
- **Fingerprint** (Text)
- **Exception Type** (Select)
- **Severity** (Select)
- **Occurrence Count** (Number)
- **Status** (Status)
- **Stack Trace** (Text)
- **Crash File** (Text)
- **Crash Line** (Number)
- **App Version** (Text)
- **Affected Devices** (Text) — newline-separated list (MVP)
- **OS Versions** (Text) — newline-separated list (MVP)
- **Bug Ticket** (Relation → Bug Tickets DB, limit 1)

## Bug Tickets DB
Required properties:
- **Name** (Title)
- **Severity** (Select)
- **Status** (Status)
- **Description** (Text)
- **Steps to Reproduce** (Text)
- **Possible Cause** (Text)
- **Fingerprint** (Text)
- **Crash Report** (Relation → Crash Reports DB, limit 1)

## Database IDs
For configuration, you may provide either:
- the raw Notion database UUID (with or without dashes), OR
- the full Notion database URL.

`NotionConfig` will normalize/extract the id automatically.
