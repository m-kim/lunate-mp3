#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"

export JAVA_HOME="$PROJECT_DIR/.toolchain/jdk-17"
export ANDROID_HOME="$PROJECT_DIR/.toolchain/android-sdk"
export ANDROID_AVD_HOME="$PROJECT_DIR/.toolchain/avd"
export ANDROID_USER_HOME="$PROJECT_DIR/.toolchain/android-user"

EMULATOR="$ANDROID_HOME/emulator/emulator"
AVD_NAME="wrist_mp3_wear3"

if [[ "${1:-}" == "--help" ]]; then
  cat <<'EOF'
Usage: scripts/start-wear-emulator.sh [--headless] [emulator options]

Starts the installed wrist_mp3_wear3 virtual watch. Use --headless when no
desktop display is available. Any remaining options are passed to the Android
emulator, for example: --wipe-data
EOF
  exit 0
fi

if [[ ! -x "$EMULATOR" ]]; then
  echo "Android emulator not found at $EMULATOR" >&2
  exit 1
fi

emulator_args=(-avd "$AVD_NAME" -no-snapshot)

if [[ "${1:-}" == "--headless" ]]; then
  emulator_args+=(-no-window -no-audio)
  shift
fi

if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
  echo "KVM acceleration is unavailable; using the slower software emulator." >&2
  emulator_args+=(-accel off -gpu swiftshader_indirect)
fi

exec "$EMULATOR" "${emulator_args[@]}" "$@"
