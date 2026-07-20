# Changelog

## Unreleased

- Added profile import, subscription parsing, multipart QR, GZIP bundle and mirror primitives.
- Added VPN lifecycle, native session rollback, routing presets and per-app routing storage.
- Added diagnostics redaction and local diagnostic log storage.
- Added connection session persistence and a basic statistics screen.
- Added GitHub release parsing and ABI-specific update asset selection primitives.
- Fixed Android CI issues around URI parsing, minSdk-compatible URL decoding, foreground service type, optional camera feature and package visibility lint.

## 1.3.5 — 2026-07-20

- Refined the full client UI with edge-to-edge layouts, a calmer wordmark, consistent cards and a centered four-item bottom navigation.
- Reworked connection selection into a vertical list: selecting a profile never reconnects an active VPN, and the primary action explicitly switches to a different profile.
- Added a compact “test all” action with parallel checks for standard profiles, sequential carrier checks and real per-card latency/unavailable states.
- Added independent System, Neutral, Bronze, Black and Monochrome palettes, accent colors, a soft connection glow slider and Clean/Glow/Drift atmosphere controls.
- Improved statistics, subscription loading/error states, app selection spacing and adaptive/notification icons.

## 1.3.3 — 2026-07-20

- Replaced horizontal connection cards with a compact vertical list and corrected bottom-navigation sizing, labels and optical icon alignment.
- Added real parallel latency checks for up to four standard profiles, with sequential checks for olcRTC carriers and live results in each card.
- Prevented profile taps from reconnecting an active VPN automatically and kept the connected-session ping as a separate action.

## 1.0.0

Initial public release target. Not released yet.
