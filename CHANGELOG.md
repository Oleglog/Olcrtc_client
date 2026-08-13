# Changelog

## 1.4.9 — 2026-08-14

- Core wire-format compatibility (legacy 32-byte vs current 36-byte VP8) is now auto-detected from the `core=` parameter the manager pins into each subscription URI, instead of a manual toggle in the profile editor. Pulling a QR from `Olcrtc_manager` sets `core=legacy` and heals to it on the next subscription refresh; `olcrtc-panel-lite` and bare URIs default to `current`. Removed the per-profile compatibility selector from the editor; existing stored values imported from the URI are preserved unchanged.

## 1.4.8 — 2026-08-13

- Fixed Android Jitsi staying stuck before MUC join on devices where `VpnService.protect(fd)` alone does not pick the working physical network after the TUN is up. The socket protector now also binds each carrier/Xray socket to the current underlying `Network` via `Network.bindSocket` on a duplicated fd (the original fd stays owned by Go), so config discovery, XMPP WebSocket and Colibri traffic follow the chosen physical route instead of the empty TUN route. The path is fail-closed: a failed protect, missing network or failed bind surfaces immediately as a `socket route bind failed` warning instead of the previous uninformative 30 s `config.js` timeout, and a one-shot `socket route protect+bind active` line confirms the route on the next device log.

## 1.4.7 — 2026-08-13

- Fixed Android Jitsi reconnect loops before MUC join by upgrading the official core and routing config discovery, XMPP WebSocket/BOSH, and Colibri WebSocket through the session's VPN-protected HTTP client. The Oleglog/j fork retains guest `anonymousdomain` handling and the earlier ICE-discovery URL fixes.

## 1.4.6 — 2026-08-13

- Jitsi ready timeout raised to 45 s (the same budget as WBStream) instead of the 15 s default. The Jitsi handshake is multi-stage (MUC join → Jingle session-initiate → bridge negotiation) and previously tripped false "start timed out" failures on slow rooms before the carrier reached ready.
- Rebuilt the bundled mobilecore against the Oleglog/j fork carrying two upstream ICE-disco fixes cherry-picked over the existing anonymousdomain handling: `a5b03af` normalizes ICE service URLs advertised over XEP-0215 disco, `9ac7664` rejects malformed colon ICE hosts. Wired through a `replace github.com/zarazaex69/j => github.com/Oleglog/j` in mobilecore's `go.mod` so CI `go mod tidy` resolves the fork; the anonymousdomain path for guest vhosts (e.g. `guest.meet.jit.si`) is preserved.

## 1.4.5 — 2026-07-31

- Re-importing a subscription via a bare `/open` deep link (the web "open in app" page, no mirror fields) no longer wipes the stored Yandex mirror. A repeated open used to overwrite `mirrorType/Url/Key` with null, dropping the only fallback that works when the primary server is down or blocked by allow-lists — now the stored mirror is preserved unless a fresh QR/bootstrap bundle supplies a new one.

## 1.4.2 — 2026-07-29

- Subscription refresh now races the primary host under a hard 2s deadline and fails over to the Yandex mirror within ~2s instead of waiting out the full HTTP timeouts when the primary is unreachable. Mitigated the long "stuck on primary" delay when the city-list subscription host is down.
- Lowered subscription HTTP connect/read timeouts from 15s to 5s; the primary payload is a small plain-text file.
- Bumped GitHub release retention from 5 to 15 published releases for rollback/regression access.

## Unreleased

- Added profile import, subscription parsing, multipart QR, GZIP bundle and mirror primitives.
- Added VPN lifecycle, native session rollback, routing presets and per-app routing storage.
- Added diagnostics redaction and local diagnostic log storage.
- Added connection session persistence and a basic statistics screen.
- Added GitHub release parsing and ABI-specific update asset selection primitives.
- Fixed Android CI issues around URI parsing, minSdk-compatible URL decoding, foreground service type, optional camera feature and package visibility lint.

## 1.4.1 — 2026-07-26

- Fixed "APK signing certificate mismatch" when updating in-app: the expected certificate digest is now normalized (colons and spaces stripped, lowercased) so both plain hex and keytool colon-separated formats are accepted. When no certificate SHA-256 is configured, the check falls back to comparing against the currently installed app's own signing certificate instead of skipping the check entirely.

## 1.4.0 — 2026-07-26

- Added a download progress bar to the in-app APK update dialog, showing percent and MB transferred.
- All Standard (VLESS/VMess/Trojan/SS) profiles are now pinged simultaneously instead of in sequential batches of four; olcRTC profiles remain sequential.

## 1.3.9 — 2026-07-26

- Reduced tunnel health probe interval from 60 s to 180 s to lower idle background traffic through the VPN tunnel.
- Refreshed the VPN connections list immediately on tab resume so newly added subscriptions and QR connections appear without restarting the app.

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
