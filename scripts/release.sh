#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: scripts/release.sh vX.Y.Z"
  exit 1
fi

VERSION="$1"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"

echo "==> Build + test gates"
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest

echo "==> Assembling APK"
./gradlew assembleDebug

echo "==> Git status check"
git status --short

echo "==> Tagging release: $VERSION"
git tag "$VERSION"

echo "==> Release prep complete."
echo "Next steps:"
echo "1) git push origin main --tags"
echo "2) create GitHub release using docs/release-template.md"
echo "3) attach app/build/outputs/apk/debug/app-debug.apk"

