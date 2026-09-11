#!/usr/bin/env bash
set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly MODULE="$ROOT/native/mobilecore"
readonly OUTPUT="${1:-$ROOT/app/libs/mobilecore.aar}"
readonly GOMOBILE_VERSION="v0.0.0-20260410095206-2cfb76559b7b"

mkdir -p "$(dirname "$OUTPUT")"
if [[ -s "$OUTPUT" && "${FORCE_NATIVE_REBUILD:-}" != "1" ]]; then
  printf 'Using cached mobilecore AAR: %s\n' "$OUTPUT"
  exit 0
fi
if ! command -v gomobile >/dev/null 2>&1; then
  go install "golang.org/x/mobile/cmd/gomobile@$GOMOBILE_VERSION"
fi
gomobile init
(
  cd "$MODULE"
  echo "Running go mod tidy..."
  go mod tidy
  echo "Checking gofmt..."
  test -z "$(gofmt -l .)" || { echo "gofmt failed on:"; gofmt -l .; exit 1; }
  echo "Running go test..."
  go test -v ./...
  echo "Running gomobile bind..."
  gomobile bind -target="${MOBILECORE_TARGET:-android}" -androidapi 26 -ldflags="-s -w -checklinkname=0" -o "$OUTPUT" .
)
