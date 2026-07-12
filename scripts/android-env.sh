#!/usr/bin/env sh

if [ -z "${JAVA_HOME:-}" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
fi

if [ -z "${ANDROID_HOME:-}" ]; then
    export ANDROID_HOME="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
fi

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
