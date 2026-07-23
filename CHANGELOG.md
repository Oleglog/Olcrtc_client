# Changelog

## Unreleased

- Added profile import, subscription parsing, multipart QR, GZIP bundle and mirror primitives.
- Added VPN lifecycle, native session rollback, routing presets and per-app routing storage.
- Added diagnostics redaction and local diagnostic log storage.
- Added connection session persistence and a basic statistics screen.
- Added GitHub release parsing and ABI-specific update asset selection primitives.
- Fixed Android CI issues around URI parsing, minSdk-compatible URL decoding, foreground service type, optional camera feature and package visibility lint.

## 1.3.8 — 2026-07-23

- Preserved fast WBStream carrier authentication failures during native readiness checks so fatal errors stop automatic reconnect loops instead of being replaced with `mobilecore is not running`.

## 1.3.7 — 2026-07-23

- Rebuilt the bundled mobilecore AAR from official olcRTC commit `42ae4e0c6a1a`, including its isolated control-plane KCP session for current VP8 connections.
- Forced release builds to compile mobilecore from the pinned source even when a cached AAR exists.
- Added CI and release checks that verify every bundled `libgojni.so` uses the pinned official core and contains no legacy fork dependencies.

## 1.3.6 — 2026-07-22

- Updated the bundled official olcRTC core to commit `42ae4e0c6a1a` and removed the client fork replacements.
- Added a per-profile `current` / `legacy` compatibility selector for the 36-byte and 32-byte VP8 wire formats.
- Migrated existing local and subscription profiles to `legacy` while new profiles default to `current`.
- Added the compatibility mode to exported olcRTC URIs, subscription persistence and diagnostics.
- Added GitHub Actions validation for the native core, dependency graph, Android unit tests, lint, APK assembly and instrumentation tests.

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
