#!/usr/bin/env sh
set -eu

key_path="${RECLAIMED_PLAYER_KEYSTORE:-$HOME/.config/reclaimed-player/reclaimed-player-debug.keystore}"

if [ -e "$key_path" ]; then
    printf 'Refusing to replace existing signing key: %s\n' "$key_path" >&2
    exit 1
fi

mkdir -p "$(dirname "$key_path")"
keytool -genkeypair \
    -keystore "$key_path" \
    -storepass android \
    -keypass android \
    -alias reclaimeddebug \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -dname "CN=Reclaimed Player Debug, O=Reclaimed Player, C=US"
chmod 600 "$key_path"
printf 'Created stable debug signing key: %s\n' "$key_path"
printf 'Back it up securely and copy it to the same path on other development computers.\n'
