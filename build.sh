#!/bin/bash
# Сборка «Напомни» в Docker (образ и кэш библиотек — общие с «Хрюней» и «Цветопечатью»): тесты, затем APK в dist/.
set -euo pipefail
cd "$(dirname "$0")"
HERE="$(pwd -W 2>/dev/null || pwd)"
MSYS_NO_PATHCONV=1 docker run --rm -v "$HERE:/project" -v doedu-gradle-cache:/gradle-cache -w /project \
  doedu-android-builder gradle testReleaseUnitTest assembleRelease --no-daemon -q "$@"
VER=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
mkdir -p dist && cp app/build/outputs/apk/release/app-release.apk "dist/Napomni-$VER.apk"
echo "приложение $VER: dist/Napomni-$VER.apk"
