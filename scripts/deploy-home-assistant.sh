#!/usr/bin/env sh

set -eu

ssh_host="${HA_SSH_HOST:-192.168.68.60}"
ssh_user="${HA_SSH_USER:-root}"
ssh_port="${HA_SSH_PORT:-22}"
identity_file="${HA_SSH_IDENTITY:-$HOME/.ssh/reclaimed_player_ha}"
known_hosts_file="${HA_SSH_KNOWN_HOSTS:-$HOME/.ssh/reclaimed_player_ha_lan_known_hosts}"
config_dir="${HA_CONFIG_DIR:-/config}"
health_url="${HA_HEALTH_URL:-http://192.168.68.60:8123/}"
component="reclaimed_player"
source_dir="home-assistant/custom_components/$component"
remote_root="$config_dir/custom_components"

if [ ! -d "$source_dir" ]; then
    printf 'Run this command from the repository root.\n' >&2
    exit 1
fi
if [ ! -f "$identity_file" ]; then
    printf 'Missing Home Assistant deploy key: %s\n' "$identity_file" >&2
    exit 1
fi
if [ ! -f "$known_hosts_file" ]; then
    printf 'Missing pinned Home Assistant host key: %s\n' "$known_hosts_file" >&2
    exit 1
fi

ssh_command="ssh -T -p $ssh_port -i $identity_file -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$known_hosts_file"
remote="$ssh_user@$ssh_host"

printf 'Uploading %s to %s...\n' "$component" "$remote"
$ssh_command "$remote" \
    "mkdir -p '$remote_root' && rm -rf '$remote_root/$component.next' && mkdir '$remote_root/$component.next'"
tar -C "$source_dir" \
    --exclude='__pycache__' \
    --exclude='*.pyc' \
    -czf - . | $ssh_command "$remote" "tar -xzf - -C '$remote_root/$component.next'"

printf 'Checking and activating the uploaded component...\n'
$ssh_command "$remote" "
set -eu
if command -v python3 >/dev/null 2>&1; then
    python3 -m compileall -q '$remote_root/$component.next'
fi
rm -rf '$remote_root/$component.previous'
if [ -d '$remote_root/$component' ]; then
    mv '$remote_root/$component' '$remote_root/$component.previous'
fi
mv '$remote_root/$component.next' '$remote_root/$component'
if ! ha core check; then
    rm -rf '$remote_root/$component'
    if [ -d '$remote_root/$component.previous' ]; then
        mv '$remote_root/$component.previous' '$remote_root/$component'
    fi
    printf 'Home Assistant configuration check failed; restored the previous component.\n' >&2
    exit 1
fi
ha core restart
"

printf 'Waiting for Home Assistant Core at %s...\n' "$health_url"
attempt=0
while [ "$attempt" -lt 60 ]; do
    status="$(curl -k -sS -o /dev/null -w '%{http_code}' --connect-timeout 2 --max-time 5 "$health_url" 2>/dev/null || true)"
    case "$status" in
        200)
            printf 'Home Assistant is ready (HTTP %s).\n' "$status"
            exit 0
            ;;
    esac
    attempt=$((attempt + 1))
    sleep 2
done

printf 'Home Assistant did not become ready within 120 seconds.\n' >&2
exit 1
