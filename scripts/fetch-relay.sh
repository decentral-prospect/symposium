#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_dir="$(cd "$script_dir/.." && pwd)"

# shellcheck source=relay-common.sh
source "$script_dir/relay-common.sh"

if [[ $# -ne 0 ]]; then
    echo "Usage: scripts/fetch-relay.sh" >&2
    exit 2
fi

for command_name in jq sha256sum; do
    require_relay_command "$command_name"
done

lock_file="$repository_dir/relay.lock.json"
validate_relay_lock "$lock_file"

version="$(jq -er '.version' "$lock_file")"
asset="$(jq -er '.asset' "$lock_file")"
url="$(jq -er '.url' "$lock_file")"
expected_sha256="$(jq -er '.sha256' "$lock_file")"

destination_dir="$repository_dir/app/src/main/assets/symposium"
destination="$destination_dir/$asset"
if [[ -f "$destination" ]] && verify_relay_sha256 "$destination" "$expected_sha256"; then
    echo "Relay ${version} is already present and verified."
    exit 0
fi

for command_name in curl mktemp install; do
    require_relay_command "$command_name"
done

umask 077
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/symposium-relay-fetch.XXXXXX")"
destination_staging=""

cleanup() {
    local status=$?
    if [[ -n "$destination_staging" ]]; then
        rm -f -- "$destination_staging"
    fi
    if [[ "$work_dir" == "${TMPDIR:-/tmp}/symposium-relay-fetch."* ]]; then
        rm -rf -- "$work_dir"
    fi
    exit "$status"
}
trap cleanup EXIT INT TERM

downloaded="$work_dir/$asset"
curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
    --retry 3 --retry-all-errors --connect-timeout 15 --max-time 900 \
    --output "$downloaded" "$url"
verify_relay_sha256 "$downloaded" "$expected_sha256"

mkdir -p "$destination_dir"
destination_staging="$(mktemp "$destination_dir/.${asset}.XXXXXX")"
install -m 0755 "$downloaded" "$destination_staging"
verify_relay_sha256 "$destination_staging" "$expected_sha256"
mv -f -- "$destination_staging" "$destination"
destination_staging=""
verify_relay_sha256 "$destination" "$expected_sha256"

echo "Fetched and verified relay ${version} to ${destination#$repository_dir/}."
