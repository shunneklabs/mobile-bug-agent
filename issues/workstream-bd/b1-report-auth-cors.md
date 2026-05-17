# B1 — Harden `/report` auth + booth-safe CORS/rate-limits

Tracks: #38
Parent branch: `workstream-bd-parent` (PR #43)

## Scope
- Apply `X-MBA-API-Key` auth **only** to `POST /report`.
- Leave `/`, `/health`, `/version`, `/stats`, `/events` open.
- Tighten CORS to `localhost`, booth laptop, deployed demo domain.
- Keep rate-limit on `/report` only; remove accidental TV page throttling.
- Log structured warning on rejected auth.

## Files (planned)
- `mba-server/src/main/kotlin/dev/sunnat629/mba/server/Application.kt`
- `mba-server/src/main/kotlin/dev/sunnat629/mba/server/plugins/*`
- `mba-server/src/test/...` (route auth tests)

## Acceptance
- TV page reachable without API key.
- `POST /report` without key → `401`.
- `OPTIONS` from non-allowed origin → rejected.
- Rate-limit triggers only on `/report`.
