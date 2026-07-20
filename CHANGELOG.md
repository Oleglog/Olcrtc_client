# Changelog

## Unreleased

- Added profile import, subscription parsing, multipart QR, GZIP bundle and mirror primitives.
- Added VPN lifecycle, native session rollback, routing presets and per-app routing storage.
- Added diagnostics redaction and local diagnostic log storage.
- Added connection session persistence and a basic statistics screen.
- Added GitHub release parsing and ABI-specific update asset selection primitives.
- Fixed Android CI issues around URI parsing, minSdk-compatible URL decoding, foreground service type, optional camera feature and package visibility lint.

## 1.3.3 — 2026-07-20

- Replaced horizontal connection cards with a compact vertical list and corrected bottom-navigation sizing, labels and optical icon alignment.
- Added real parallel latency checks for up to four standard profiles, with sequential checks for olcRTC carriers and live results in each card.
- Prevented profile taps from reconnecting an active VPN automatically and kept the connected-session ping as a separate action.

## 1.0.0

Initial public release target. Not released yet.
