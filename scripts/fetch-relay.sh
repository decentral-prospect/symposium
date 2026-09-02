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

for command_name in curl jq sha256sum mktemp install; do
    require_relay_command "$command_name"
done

lock_file="$repository_dir/relay.lock.json"
validate_relay_lock "$lock_file"

repository="$(jq -er '.repository' "$lock_file")"
version="$(jq -er '.version' "$lock_file")"
asset="$(jq -er '.asset' "$lock_file")"
url="$(jq -er '.url' "$lock_file")"
expected_sha256="$(jq -er '.sha256' "$lock_file")"

verify_attestation="${SYMPOSIUM_VERIFY_RELAY_ATTESTATION:-${CI:-false}}"
case "$verify_attestation" in
    1|true|TRUE|yes|YES) verify_attestation=true ;;
    0|false|FALSE|no|NO|'') verify_attestation=false ;;
    *) relay_error "SYMPOSIUM_VERIFY_RELAY_ATTESTATION must be true or false" ;;
esac

if [[ "$verify_attestation" == true ]]; then
    require_relay_command gh
fi

umask 077
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/symposium-relay-fetch.XXXXXX")"
destination_dir="$repository_dir/app/src/main/assets/symposium"
destination="$destination_dir/$asset"
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

if [[ "$verify_attestation" == true ]]; then
    release_json="$work_dir/release.json"
    gh api "repos/${repository}/releases/tags/${version}" >"$release_json"
    [[ "$(jq -er '.tag_name' "$release_json")" == "$version" ]] ||
        relay_error "GitHub returned a different release tag"
    [[ "$(jq -er '.draft' "$release_json")" == "false" ]] ||
        relay_error "locked relay release is a draft"
    [[ "$(jq -er '.immutable // false' "$release_json")" == "true" ]] ||
        relay_error "locked relay release is not immutable"
    api_url="$(jq -er --arg name "$asset" '.assets[] | select(.name == $name) | .browser_download_url' "$release_json")"
    api_digest="$(jq -er --arg name "$asset" '.assets[] | select(.name == $name) | .digest' "$release_json")"
    [[ "$api_url" == "$url" ]] || relay_error "locked URL does not match the immutable release asset"
    [[ "$api_digest" == "sha256:$expected_sha256" ]] ||
        relay_error "locked SHA-256 does not match the immutable release asset"

    gh attestation verify "$downloaded" -R "$repository"
    gh attestation verify "$downloaded" -R "$repository" \
        --predicate-type https://cyclonedx.org/bom
fi

mkdir -p "$destination_dir"
destination_staging="$(mktemp "$destination_dir/.${asset}.XXXXXX")"
install -m 0755 "$downloaded" "$destination_staging"
verify_relay_sha256 "$destination_staging" "$expected_sha256"
mv -f -- "$destination_staging" "$destination"
destination_staging=""

echo "Fetched and verified relay ${version} to ${destination#$repository_dir/}."
