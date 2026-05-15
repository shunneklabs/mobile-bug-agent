# Booth fallback assets (Workstream E3)

This directory stores local fallback media used during live demos.

## Expected files

- `demo-90s.mp4` — primary silent fallback loop (1920x1080)
- `demo-30s.mp4` — short fallback loop for quick resets
- `demo-90s.srt` — optional subtitle/caption track

## Usage

If live networking fails during booth operation, play `demo-90s.mp4` full-screen on the booth TV.
If queue pressure is high and a quick reset is needed, play `demo-30s.mp4` once and then return to live mode.

The actual `.mp4` files are intentionally not committed to keep repository size small.