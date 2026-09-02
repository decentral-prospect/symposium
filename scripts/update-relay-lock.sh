#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_dir="$(cd "$script_dir/.." && pwd)"

# shellcheck source=relay-common.sh
source "$script_dir/relay-common.sh"

usage() {
    echo "Usage: scripts/update-relay-lock.sh RELEASE_VERSION" >&2
    exit 2
}

[[ $# -eq 1 ]] || usage
version="$1"
[[ "$version" =~ $relay_version_pattern ]] || relay_error "invalid release version: $version"

for command_name in gh jq sha256sum awk mktemp; do
    require_relay_command "$command_name"
done

umask 077
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/symposium-relay-lock.XXXXXX")"
lock_file="$repository_dir/relay.lock.json"
lock_staging=""

cleanup() {
    local status=$?
    if [[ -n "$lock_staging" ]]; then
        rm -f -- "$lock_staging"
    fi
    if [[ "$work_dir" == "${TMPDIR:-/tmp}/symposium-relay-lock."* ]]; then
        rm -rf -- "$work_dir"
    fi
    exit "$status"
}
trap cleanup EXIT INT TERM

release_json="$work_dir/release.json"
gh api "repos/${relay_repository}/releases/tags/${version}" >"$release_json"

[[ "$(jq -er '.tag_name' "$release_json")" == "$version" ]] ||
    relay_error "GitHub returned a different release tag"
[[ "$(jq -er '.draft' "$release_json")" == "false" ]] ||
    relay_error "release is still a draft: $version"
[[ "$(jq -er '.immutable // false' "$release_json")" == "true" ]] ||
    relay_error "release is not immutable: $version"

asset_count="$(jq --arg name "$relay_asset" '[.assets[] | select(.name == $name)] | length' "$release_json")"
checksums_count="$(jq '[.assets[] | select(.name == "SHA256SUMS")] | length' "$release_json")"
[[ "$asset_count" == "1" ]] || relay_error "release must contain exactly one $relay_asset asset"
[[ "$checksums_count" == "1" ]] || relay_error "release must contain exactly one SHA256SUMS asset"

asset_url="$(jq -er --arg name "$relay_asset" '.assets[] | select(.name == $name) | .browser_download_url' "$release_json")"
asset_digest="$(jq -er --arg name "$relay_asset" '.assets[] | select(.name == $name) | .digest' "$release_json")"
canonical_url="https://github.com/${relay_repository}/releases/download/${version}/${relay_asset}"
[[ "$asset_url" == "$canonical_url" ]] || relay_error "GitHub returned a non-canonical asset URL"
[[ "$asset_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || relay_error "GitHub did not expose a valid asset digest"

gh release download "$version" -R "$relay_repository" \
    --pattern SHA256SUMS --dir "$work_dir"
gh release download "$version" -R "$relay_repository" \
    --pattern "$relay_asset" --dir "$work_dir"

mapfile -t checksum_matches < <(
    awk -v asset="$relay_asset" '
        $2 == asset || $2 == "*" asset { print tolower($1) }
    ' "$work_dir/SHA256SUMS"
)
[[ ${#checksum_matches[@]} -eq 1 ]] ||
    relay_error "SHA256SUMS must contain exactly one checksum for $relay_asset"
expected_sha256="${checksum_matches[0]}"
[[ "$expected_sha256" =~ $relay_sha256_pattern ]] ||
    relay_error "SHA256SUMS contains an invalid checksum for $relay_asset"
[[ "$asset_digest" == "sha256:$expected_sha256" ]] ||
    relay_error "SHA256SUMS does not match the immutable GitHub asset digest"

verify_relay_sha256 "$work_dir/$relay_asset" "$expected_sha256"
gh attestation verify "$work_dir/$relay_asset" -R "$relay_repository"
gh attestation verify "$work_dir/$relay_asset" -R "$relay_repository" \
    --predicate-type https://cyclonedx.org/bom

lock_staging="$(mktemp "${lock_file}.tmp.XXXXXX")"
jq -n \
    --arg repository "$relay_repository" \
    --arg version "$version" \
    --arg asset "$relay_asset" \
    --arg url "$canonical_url" \
    --arg sha256 "$expected_sha256" \
    '{repository: $repository, version: $version, asset: $asset, url: $url, sha256: $sha256}' \
    >"$lock_staging"
validate_relay_lock "$lock_staging"
chmod 0644 "$lock_staging"
mv -f -- "$lock_staging" "$lock_file"
lock_staging=""

echo "relay.lock.json now pins ${relay_repository} ${version} (${expected_sha256})."
