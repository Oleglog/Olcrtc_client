#!/usr/bin/env bash
set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly MODULE="$ROOT/native/mobilecore"
readonly OUTPUT="${1:-$ROOT/app/libs/mobilecore.aar}"
readonly GOMOBILE_VERSION="v0.0.0-20260410095206-2cfb76559b7b"

mkdir -p "$(dirname "$OUTPUT")"
go install "golang.org/x/mobile/cmd/gomobile@$GOMOBILE_VERSION"
gomobile init
(
  cd "$MODULE"
  go mod download
  test -z "$(gofmt -l .)"
  go test ./...
  gomobile bind -target=android -androidapi 26 -o "$OUTPUT" .
)
