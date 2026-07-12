#!/usr/bin/env sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/android-env.sh"

AVD_NAME=${RECLAIMED_AVD_NAME:-Reclaimed_Player_Pixel_6_API_36}
EMULATOR_PORT=${RECLAIMED_EMULATOR_PORT:-5580}
SERIAL="emulator-$EMULATOR_PORT"
PACKAGE=dev.reclaimed.player
ACTIVITY="$PACKAGE/.MainActivity"
REPORT_DIR="$ROOT_DIR/app/build/reports/emulator-smoke"
LOG_FILE="${TMPDIR:-/tmp}/reclaimed-player-emulator-$EMULATOR_PORT.log"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVD_HOME=${ANDROID_AVD_HOME:-"$HOME/.android/avd"}

case "$(uname -m)" in
    arm64|aarch64)
        ABI=arm64-v8a
        ;;
    x86_64)
        ABI=x86_64
        ;;
    *)
        printf 'Unsupported host architecture: %s\n' "$(uname -m)" >&2
        exit 1
        ;;
esac

SYSTEM_IMAGE="system-images;android-36;google_apis;$ABI"

usage() {
    printf 'Usage: %s {setup|start|start-headless|wait|install|smoke|stop}\n' "$0" >&2
    exit 2
}

require_adb() {
    if [ ! -x "$ADB" ]; then
        printf 'Missing platform-tools. Run `mise exec -- just setup`.\n' >&2
        exit 1
    fi
}

require_avd() {
    if [ ! -f "$AVD_HOME/$AVD_NAME.ini" ]; then
        printf 'Missing %s. Run `mise exec -- just emulator-setup`.\n' "$AVD_NAME" >&2
        exit 1
    fi
    if [ ! -x "$EMULATOR" ]; then
        printf 'Missing Android Emulator. Run `mise exec -- just emulator-setup`.\n' >&2
        exit 1
    fi
}

project_emulator_name() {
    "$ADB" -s "$SERIAL" emu avd name 2>/dev/null | sed -n '1p' | tr -d '\r'
}

require_project_emulator() {
    require_adb
    if [ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)" != device ]; then
        printf '%s is not running. Run `mise exec -- just emulator-start`.\n' "$AVD_NAME" >&2
        exit 1
    fi

    actual_name=$(project_emulator_name)
    if [ "$actual_name" != "$AVD_NAME" ]; then
        printf 'Refusing to continue: %s is %s, not project AVD %s.\n' \
            "$SERIAL" "${actual_name:-an unknown emulator}" "$AVD_NAME" >&2
        exit 1
    fi
}

wait_for_boot() {
    require_adb
    attempt=0
    while [ "$attempt" -lt 120 ]; do
        state=$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)
        boot_completed=$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
        if [ "$state" = device ] && [ "$boot_completed" = 1 ]; then
            require_project_emulator
            printf '%s is ready at %s.\n' "$AVD_NAME" "$SERIAL"
            return
        fi
        attempt=$((attempt + 1))
        sleep 2
    done

    printf 'Timed out waiting for %s. Emulator log: %s\n' "$AVD_NAME" "$LOG_FILE" >&2
    exit 1
}

setup_avd() {
    require_adb
    if ! command -v sdkmanager >/dev/null 2>&1 || ! command -v avdmanager >/dev/null 2>&1; then
        printf 'Missing Android command-line tools. Run `mise install`.\n' >&2
        exit 1
    fi

    sdkmanager "emulator" "$SYSTEM_IMAGE"
    if [ -f "$AVD_HOME/$AVD_NAME.ini" ]; then
        printf '%s already exists.\n' "$AVD_NAME"
        return
    fi

    mkdir -p "$AVD_HOME"
    printf 'no\n' | avdmanager create avd \
        --name "$AVD_NAME" \
        --package "$SYSTEM_IMAGE" \
        --device pixel_6
    printf 'Created %s with %s.\n' "$AVD_NAME" "$SYSTEM_IMAGE"
}

start_emulator() {
    mode=$1
    require_avd
    require_adb

    if [ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)" = device ]; then
        require_project_emulator
        printf '%s is already running at %s.\n' "$AVD_NAME" "$SERIAL"
        wait_for_boot
        return
    fi

    if [ "$mode" = headless ]; then
        nohup "$EMULATOR" -avd "$AVD_NAME" -port "$EMULATOR_PORT" \
            -no-window -no-audio -no-boot-anim -no-snapshot-save \
            -gpu swiftshader_indirect >"$LOG_FILE" 2>&1 &
    else
        nohup "$EMULATOR" -avd "$AVD_NAME" -port "$EMULATOR_PORT" \
            -no-snapshot-save >"$LOG_FILE" 2>&1 &
    fi
    printf 'Starting %s; log: %s\n' "$AVD_NAME" "$LOG_FILE"
    wait_for_boot
}

install_apk() {
    require_project_emulator
    "$ROOT_DIR/gradlew" -p "$ROOT_DIR" assembleDebug
    "$ADB" -s "$SERIAL" install -r "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
}

smoke_test() {
    require_project_emulator
    install_apk

    "$ADB" -s "$SERIAL" logcat -c
    "$ADB" -s "$SERIAL" shell settings put secure immersive_mode_confirmations confirmed
    "$ADB" -s "$SERIAL" shell pm grant "$PACKAGE" android.permission.READ_MEDIA_AUDIO 2>/dev/null || true
    "$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE"
    launch_output=$("$ADB" -s "$SERIAL" shell am start -W -n "$ACTIVITY")
    printf '%s\n' "$launch_output"
    sleep 3

    if [ -z "$("$ADB" -s "$SERIAL" shell pidof "$PACKAGE" | tr -d '\r')" ]; then
        printf 'Smoke test failed: %s is not running.\n' "$PACKAGE" >&2
        exit 1
    fi
    if ! "$ADB" -s "$SERIAL" shell dumpsys activity activities | grep -q "$ACTIVITY"; then
        printf 'Smoke test failed: %s is not in the activity stack.\n' "$ACTIVITY" >&2
        exit 1
    fi

    crash_log=$("$ADB" -s "$SERIAL" logcat -b crash -d)
    if printf '%s\n' "$crash_log" | grep -q "$PACKAGE"; then
        printf 'Smoke test failed with an app crash:\n%s\n' "$crash_log" >&2
        exit 1
    fi

    mkdir -p "$REPORT_DIR"
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$REPORT_DIR/screenshot.png"
    if "$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/reclaimed-player-window.xml >/dev/null; then
        "$ADB" -s "$SERIAL" pull /sdcard/reclaimed-player-window.xml "$REPORT_DIR/window.xml" >/dev/null
        "$ADB" -s "$SERIAL" shell rm /sdcard/reclaimed-player-window.xml
    fi
    printf 'Emulator smoke test passed. Artifacts: %s\n' "$REPORT_DIR"
}

command=${1:-}
case "$command" in
    setup)
        setup_avd
        ;;
    start)
        start_emulator visible
        ;;
    start-headless)
        start_emulator headless
        ;;
    wait)
        wait_for_boot
        ;;
    install)
        install_apk
        ;;
    smoke)
        smoke_test
        ;;
    stop)
        require_project_emulator
        "$ADB" -s "$SERIAL" emu kill
        ;;
    *)
        usage
        ;;
esac
