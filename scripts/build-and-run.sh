#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"

export JAVA_HOME="$PROJECT_DIR/.toolchain/jdk-17"
export ANDROID_HOME="$PROJECT_DIR/.toolchain/android-sdk"
export ANDROID_AVD_HOME="$PROJECT_DIR/.toolchain/avd"
export ANDROID_USER_HOME="$PROJECT_DIR/.toolchain/android-user"

ADB="$ANDROID_HOME/platform-tools/adb"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [[ "${1:-}" == "--help" ]]; then
  cat <<'EOF'
Usage: scripts/build-and-run.sh

Builds and tests Wrist MP3, installs it on the running Android emulator, and
opens the app. Start the virtual watch first with start-wear-emulator.sh.
EOF
  exit 0
fi

if [[ ! -x "$ADB" ]]; then
  echo "ADB not found at $ADB" >&2
  exit 1
fi

if ! "$ADB" -e get-state >/dev/null 2>&1; then
  echo "No running emulator. Start one in another terminal with:" >&2
  echo "  scripts/start-wear-emulator.sh" >&2
  exit 1
fi

cd "$PROJECT_DIR"
./gradlew assembleDebug testDebugUnitTest
"$ADB" -e install -r "$APK"
"$ADB" -e shell am start -W -n com.galaxywatch.mp3player/.MainActivity
