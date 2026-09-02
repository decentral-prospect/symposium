#!/usr/bin/env bash

relay_repository="decentral-prospect/symposium-relay"
relay_asset="symposium-server-linux-amd64"
relay_version_pattern='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$'
relay_sha256_pattern='^[0-9a-f]{64}$'

relay_error() {
    echo "Relay verification error: $*" >&2
    return 1
}

require_relay_command() {
    local command_name="$1"
    command -v "$command_name" >/dev/null 2>&1 ||
        relay_error "required command is missing: $command_name"
}

validate_relay_lock() {
    local lock_file="$1"
    local repository version asset url sha256 expected_url

    require_relay_command jq || return 1
    [[ -f "$lock_file" ]] || relay_error "lock file does not exist: $lock_file" || return 1

    if ! jq -e '
        type == "object" and
        (.repository | type == "string" and length > 0) and
        (.version | type == "string" and length > 0) and
        (.asset | type == "string" and length > 0) and
        (.url | type == "string" and length > 0) and
        (.sha256 | type == "string" and length > 0)
    ' "$lock_file" >/dev/null 2>&1; then
        relay_error "lock file is malformed or is missing required string fields: $lock_file"
        return 1
    fi

    repository="$(jq -er '.repository' "$lock_file")"
    version="$(jq -er '.version' "$lock_file")"
    asset="$(jq -er '.asset' "$lock_file")"
    url="$(jq -er '.url' "$lock_file")"
    sha256="$(jq -er '.sha256' "$lock_file")"

    [[ "$repository" == "$relay_repository" ]] ||
        relay_error "unexpected relay repository: $repository" || return 1
    [[ "$asset" == "$relay_asset" ]] ||
        relay_error "unexpected relay asset: $asset" || return 1
    [[ "$version" =~ $relay_version_pattern ]] ||
        relay_error "invalid relay release version: $version" || return 1
    [[ "$sha256" =~ $relay_sha256_pattern ]] ||
        relay_error "relay SHA-256 must be 64 lowercase hexadecimal characters" || return 1

    expected_url="https://github.com/${repository}/releases/download/${version}/${asset}"
    [[ "$url" == "$expected_url" ]] ||
        relay_error "relay URL is not the canonical locked release URL" || return 1
}

verify_relay_sha256() {
    local file_path="$1"
    local expected_sha256="$2"
    local actual_sha256

    require_relay_command sha256sum || return 1
    [[ -f "$file_path" ]] || relay_error "file does not exist: $file_path" || return 1
    [[ "$expected_sha256" =~ $relay_sha256_pattern ]] ||
        relay_error "invalid expected SHA-256" || return 1

    actual_sha256="$(sha256sum "$file_path")"
    actual_sha256="${actual_sha256%% *}"
    [[ "$actual_sha256" == "$expected_sha256" ]] ||
        relay_error "SHA-256 mismatch for $(basename "$file_path"): expected $expected_sha256, got $actual_sha256" ||
        return 1
}
