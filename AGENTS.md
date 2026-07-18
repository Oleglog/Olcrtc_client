# Olcrtc_client agent rules

- Never run Gradle, Android, APK, native, lint, unit-test, instrumentation-test, or other validation commands locally.
- Run every check, build, and release only through GitHub Actions workflows in this repository.
- Keep `docs/` local-only. Do not add, commit, or push it.
- Release from `main` with semantic tags. This project rolls the minor version after patch 9 (`1.0.9` -> `1.1.0`).
- Keep only the five newest GitHub Release objects; preserve Git tags and Git history.
