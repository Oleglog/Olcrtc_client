# olcRTC Client

Android VPN client for olcRTC and standard proxy profiles. The app is designed for Android 8+ and routes traffic through Android VpnService, HevSocks5Tunnel, Xray, and optionally the olcRTC mobile core.

## Scope

Supported profile families for the 1.0.0 target:

- olcRTC: wbstream, telemost and jitsi within the provider/transport matrix defined by the technical specification.
- VLESS, VMess and Trojan.
- TCP/raw, WebSocket and gRPC for standard profiles.
- XHTTP for VLESS.
- TLS, REALITY and VLESS Vision flow.

Unsupported by design: Clash YAML, sing-box JSON, arbitrary Xray JSON, KCP, QUIC, HTTPUpgrade, Shadowsocks, WireGuard, Android TV, Wear OS, cloud sync, telemetry and admin-panel features.

## Importing profiles

The client validates every incoming configuration before saving it. Supported import paths are:

- QR camera scan.
- QR image import through Android Photo Picker.
- Explicit clipboard paste.
- Deep links for `olcrtc://`, `vless://`, `vmess://` and `trojan://`.
- Plain text files through Storage Access Framework.
- Subscription payloads: plain UTF-8 lists, Base64 lists, olcRTC bundle JSON, `olcrtc+gz` and multipart QR.

External intents do not auto-connect. Imported data is first parsed, validated and stored locally.

## Routing

The app captures allowed per-app traffic through Android VpnService and applies routing inside Xray:

1. User domain/IP/CIDR rules.
2. LAN direct rule when enabled.
3. GeoIP/GeoSite preset.
4. Default route.

Routing presets:

- All traffic through VPN.
- Russia direct: `geoip:ru`, `geosite:category-ru` and `geosite:ru-available-only-inside` go direct, other traffic uses VPN.

Per-app routing supports all apps, all except selected, and only selected. The package list is local-only and is never exported in diagnostics.

## Privacy and diagnostics

olcRTC Client has no telemetry, analytics or automatic crash upload. Diagnostics are stored locally, rotated, and redacted before display or export. Redaction covers:

- raw share links;
- keys, UUIDs, passwords, auth tokens and client IDs;
- subscription URL query strings;
- mirror keys;
- authorization headers.

Logs are kept for up to 7 days and capped at 20 MiB.

## Building

The Android CI workflow builds the native components before Gradle verification:

```bash
scripts/build_mobilecore.sh
scripts/build_hev.sh
./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug assembleRelease
./gradlew --no-daemon :app:assembleDebugAndroidTest
```

Required toolchain:

- JDK 17.
- Android SDK 36.
- Android NDK 28.2.13676358.
- Go 1.25.x for mobilecore.

## Troubleshooting

- If the VPN cannot start, re-check VpnService permission and selected profile validity.
- If only selected apps mode is enabled, at least one selected installed package must exist.
- If a subscription update fails, the previous working profiles are kept.
- If diagnostics are shared, use the redacted copy/export action from Settings.

## License

The repository contains proprietary Android application code. Third-party notices are documented in `THIRD_PARTY_NOTICES.md`.
