set shell := ["sh", "-eu", "-c"]

android_env := "scripts/android-env.sh"
gradle := "./gradlew"

# Show the available development commands.
default:
    @just --list

# Accept Android licenses and install the SDK packages required by this project.
setup:
    #!/usr/bin/env sh
    set -eu
    . {{ android_env }}
    sdkmanager --licenses
    sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"

# Check that the local Android command-line environment is usable.
doctor:
    #!/usr/bin/env sh
    set -eu
    . {{ android_env }}
    printf 'JAVA_HOME=%s\nANDROID_HOME=%s\n' "$JAVA_HOME" "$ANDROID_HOME"
    if [ ! -x "$JAVA_HOME/bin/java" ]; then
        printf 'Missing JDK: run `mise install`, then execute commands with `mise exec --`.\n' >&2
        exit 1
    fi
    if ! command -v sdkmanager >/dev/null 2>&1; then
        printf 'Missing Android command-line tools: run `mise install`.\n' >&2
        exit 1
    fi
    if [ ! -x "$ANDROID_HOME/platform-tools/adb" ]; then
        printf 'Missing Android platform tools: run `just setup`.\n' >&2
        exit 1
    fi
    if [ ! -d "$ANDROID_HOME/platforms/android-36" ] || [ ! -d "$ANDROID_HOME/build-tools/36.0.0" ]; then
        printf 'Missing Android 36 SDK packages: run `just setup`.\n' >&2
        exit 1
    fi
    java -version
    sdkmanager --version
    adb version

# Compile the debug APK.
build:
    . {{ android_env }} && {{ gradle }} assembleDebug

# Run Android lint for the debug build.
lint:
    . {{ android_env }} && {{ gradle }} lintDebug

# Run debug unit tests.
test:
    . {{ android_env }} && {{ gradle }} testDebugUnitTest

# Run the complete local verification pass.
check:
    . {{ android_env }} && {{ gradle }} assembleDebug lintDebug testDebugUnitTest

# List all Gradle tasks.
tasks:
    . {{ android_env }} && {{ gradle }} tasks --all

# Remove generated build outputs.
clean:
    . {{ android_env }} && {{ gradle }} clean

# List connected Android devices with details.
devices:
    . {{ android_env }} && adb devices -l

# Install the debug APK on the connected device, preserving app data.
install:
    . {{ android_env }} && {{ gradle }} installDebug
