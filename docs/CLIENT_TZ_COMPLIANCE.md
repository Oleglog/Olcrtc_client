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

## Client completion status`r`n`r`nThe Android client-side requirements are implemented in this repository. The only remaining items from the full specification are outside the client repository or require physical-device verification rather than more client code.`r`n`r`n## External/manual items`r`n`r`n- Server-side `Olcrtc_manager` contract work must be completed in the manager repository.`r`n- Final manual matrix testing must be performed on real devices for TCP/UDP/IPv4/IPv6, Always-on behavior, vendor battery policies and real provider credentials.`r`n- Further APK size reductions beyond current ABI split require product decisions such as downloading geo assets after install or shipping a reduced Xray/mobilecore feature set.`r`n
