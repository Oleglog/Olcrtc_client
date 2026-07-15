# Client Technical Specification Compliance

This document tracks the Android client side of `ANDROID_CLIENT_TECHNICAL_SPECIFICATION.md`.

## Implemented in the Android client

- Android 8+ VpnService application with package `io.github.oleglog.olcrtc.client`.
- Proprietary application license policy and third-party notices.
- Native vertical slice: gomobile `mobilecore`, Xray config generation, HEV SOCKS tunnel, rollback on native startup failure.
- olcRTC profile model, parser, serializer, validation and local encrypted secret storage.
- Standard profile support: VLESS, VMess and Trojan import/export for supported transports and security modes.
- Import paths: QR camera, QR image, clipboard, file, deep links and manual entry.
- Subscription payload support: plain lists, Base64 lists, olcRTC bundle JSON, `olcrtc+gz`, multipart QR and Yandex Disk mirror primitives.
- Subscription UI: add, edit, details, refresh, delete, retain profiles and reset locally modified subscription profiles.
- VPN lifecycle: start, stop, reconnect backoff, default network callback, persisted desired state and Always-on restore path.
- Routing: default presets, LAN direct toggle, DNS endpoint, custom rules, per-app policy and installed app selection.
- Geo assets: bundled assets plus GitHub update flow with validation and rollback.
- Statistics: persisted connection sessions, current session, day/month totals, recent sessions and clear history.
- Traffic counters: HEV tunnel stats are exposed through `NativeSession` and used for notification speed plus persisted session totals.
- Diagnostics: local redacted logs, copy/export/view flows and GitHub issue URL generation without automatic upload.
- Privacy/security: no telemetry, no analytics, explicit clipboard/file/user-driven exports, redaction for profile links and secrets.
- Update/release: GitHub release parser, ABI-aware APK selection, SHA-256 verification, package/signature checks and Package Installer handoff.
- Release signing: GitHub Actions release workflow with repository secrets, SHA256SUMS generation and GitHub Release publishing.
- APK distribution: ABI split APKs for `arm64-v8a`, `armeabi-v7a` and universal fallback.
- RU/EN resources are present and stored as UTF-8.

## Not part of the Android client repository

The specification also requires server-side `Olcrtc_manager` work: installer updates, server version metadata, QR-safe WB subscription snapshot, Admin UI multipart QR and manager contract tests. These must be completed in the manager repository and are outside the Android client codebase.

## Remaining client hardening after 1.0.x

- Manual device matrix testing for TCP/UDP/IPv4/IPv6 and Always-on vendor behavior.
- Further APK size reduction by trimming native mobilecore/Xray features or moving large geo assets to first-run download.
- UI polishing beyond functional dialogs, especially dedicated details/edit screens for tablets and accessibility review.
