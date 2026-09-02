#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_dir="$(cd "$script_dir/.." && pwd)"

# shellcheck source=relay-common.sh
source "$script_dir/relay-common.sh"

if [[ $# -ne 0 ]]; then
    echo "Usage: scripts/verify-relay-provenance.sh" >&2
    exit 2
fi

for command_name in gh jq sha256sum mktemp; do
    require_relay_command "$command_name"
done

lock_file="$repository_dir/relay.lock.json"
validate_relay_lock "$lock_file"

repository="$(jq -er '.repository' "$lock_file")"
version="$(jq -er '.version' "$lock_file")"
asset="$(jq -er '.asset' "$lock_file")"
url="$(jq -er '.url' "$lock_file")"
expected_sha256="$(jq -er '.sha256' "$lock_file")"
binary="$repository_dir/app/src/main/assets/symposium/$asset"
verify_relay_sha256 "$binary" "$expected_sha256"

umask 077
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/symposium-relay-provenance.XXXXXX")"

cleanup() {
    local status=$?
    if [[ "$work_dir" == "${TMPDIR:-/tmp}/symposium-relay-provenance."* ]]; then
        rm -rf -- "$work_dir"
    fi
    exit "$status"
}
trap cleanup EXIT INT TERM

release_json="$work_dir/release.json"
gh api "repos/${repository}/releases/tags/${version}" >"$release_json"

[[ "$(jq -er '.tag_name' "$release_json")" == "$version" ]] ||
    relay_error "GitHub returned a different release tag"
[[ "$(jq -er '.draft' "$release_json")" == "false" ]] ||
    relay_error "locked relay release is a draft"
[[ "$(jq -er '.immutable // false' "$release_json")" == "true" ]] ||
    relay_error "locked relay release is not immutable"

asset_count="$(jq --arg name "$asset" '[.assets[] | select(.name == $name)] | length' "$release_json")"
[[ "$asset_count" == "1" ]] || relay_error "release must contain exactly one $asset asset"
api_url="$(jq -er --arg name "$asset" '.assets[] | select(.name == $name) | .browser_download_url' "$release_json")"
api_digest="$(jq -er --arg name "$asset" '.assets[] | select(.name == $name) | .digest' "$release_json")"
[[ "$api_url" == "$url" ]] || relay_error "locked URL does not match the immutable release asset"
[[ "$api_digest" == "sha256:$expected_sha256" ]] ||
    relay_error "locked SHA-256 does not match the immutable release asset"

gh attestation verify "$binary" -R "$repository"
gh attestation verify "$binary" -R "$repository" \
    --predicate-type https://cyclonedx.org/bom

echo "Verified relay ${version} release metadata, SLSA provenance, and CycloneDX attestation."
