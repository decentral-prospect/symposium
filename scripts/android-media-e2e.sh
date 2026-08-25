#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd "$script_dir/.." && pwd)"
relay_dir="$(cd "$android_dir/../S-server" && pwd)"

relay_host="${SYMPOSIUM_E2E_RELAY_HOST:-10.0.2.2}"
relay_port="${SYMPOSIUM_E2E_RELAY_PORT:-38443}"
admin_port="${SYMPOSIUM_E2E_ADMIN_PORT:-38002}"
room="android-media-e2e"
admin_token="android-media-e2e-admin"
require_outbound_audio="${SYMPOSIUM_ANDROID_E2E_REQUIRE_OUTBOUND_AUDIO:-true}"
work_dir="$(mktemp -d /tmp/symposium-android-media-e2e.XXXXXX)"
relay_pid=""
peer_pid=""

if [[ "$require_outbound_audio" != "true" && "$require_outbound_audio" != "false" ]]; then
    echo "SYMPOSIUM_ANDROID_E2E_REQUIRE_OUTBOUND_AUDIO must be true or false" >&2
    exit 1
fi

cleanup() {
    status=$?
    if [[ -n "$peer_pid" ]]; then
        kill "$peer_pid" 2>/dev/null || true
        wait "$peer_pid" 2>/dev/null || true
    fi
    if [[ -n "$relay_pid" ]]; then
        kill "$relay_pid" 2>/dev/null || true
        wait "$relay_pid" 2>/dev/null || true
    fi
    if [[ $status -ne 0 ]]; then
        echo "Android media E2E failed. Relay log:"
        tail -n 80 "$work_dir/relay.log" 2>/dev/null || true
        echo "Host media peer log:"
        tail -n 80 "$work_dir/peer.log" 2>/dev/null || true
    fi
    if [[ "$work_dir" == /tmp/symposium-android-media-e2e.* ]]; then
        rm -rf -- "$work_dir"
    fi
    exit "$status"
}
trap cleanup EXIT INT TERM

for command_name in adb curl go jq openssl timeout tr; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Required command is missing: $command_name" >&2
        exit 1
    fi
done

if ! adb get-state >/dev/null 2>&1; then
    echo "No Android device/emulator is connected. Start an AVD or connect a device first." >&2
    exit 1
fi

if [[ "$relay_host" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    relay_san="IP:$relay_host"
else
    relay_san="DNS:$relay_host"
fi

openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
    -subj "/CN=symposium-android-e2e" \
    -addext "subjectAltName=$relay_san,IP:127.0.0.1,DNS:localhost" \
    -keyout "$work_dir/relay.key" \
    -out "$work_dir/relay.crt" >/dev/null 2>&1

tls_pin="sha256/$(openssl x509 -in "$work_dir/relay.crt" -pubkey -noout \
    | openssl pkey -pubin -outform DER 2>/dev/null \
    | openssl dgst -sha256 -binary \
    | openssl base64 -A)"
e2ee_secret="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"

(
    cd "$relay_dir"
    go build -o "$work_dir/symposium-relay" .
)

"$work_dir/symposium-relay" \
    --addr "0.0.0.0:$relay_port" \
    --admin-addr "127.0.0.1:$admin_port" \
    --admin-token "$admin_token" \
    --instance-name "android-media-e2e" \
    --rooms-db "$work_dir/rooms.db" \
    --db-table open_rooms \
    --db-room-column room_name \
    --db-key-column moderator_key \
    --tls-cert "$work_dir/relay.crt" \
    --tls-key "$work_dir/relay.key" \
    --loopback \
    --allow-private-ice >"$work_dir/relay.log" 2>&1 &
relay_pid=$!

relay_ready=false
for _ in $(seq 1 80); do
    if curl -fsS -H "X-Relay-Key: $admin_token" \
        "http://127.0.0.1:$admin_port/admin/version" >/dev/null 2>&1; then
        relay_ready=true
        break
    fi
    if ! kill -0 "$relay_pid" 2>/dev/null; then
        echo "Relay exited before becoming ready" >&2
        exit 1
    fi
    sleep 0.25
done
if [[ "$relay_ready" != true ]]; then
    echo "Relay did not become ready" >&2
    exit 1
fi

room_response="$(curl -fsS -X POST -H "X-Relay-Key: $admin_token" \
    "http://127.0.0.1:$admin_port/admin/open-room?name=$room")"
moderator_key="$(jq -er '.moderator_key' <<<"$room_response")"

# Build before starting the bounded host peer so a cold Gradle compilation
# cannot consume the media verification timeout.
(
    cd "$android_dir"
    ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
        -PTELEMETRY_ENDPOINT= \
        -PTELEMETRY_TOKEN=
)

(
    cd "$relay_dir"
    SYMPOSIUM_ANDROID_MEDIA_E2E=1 \
    SYMPOSIUM_ANDROID_E2E_WS_URL="wss://127.0.0.1:$relay_port/ws" \
    SYMPOSIUM_ANDROID_E2E_ROOM="$room" \
    SYMPOSIUM_ANDROID_E2E_MODERATOR_KEY="$moderator_key" \
    SYMPOSIUM_ANDROID_E2E_SECRET="$e2ee_secret" \
    SYMPOSIUM_ANDROID_E2E_REQUIRE_OUTBOUND_AUDIO="$require_outbound_audio" \
    go test -run '^TestAndroidClientMediaE2EPeer$' -count=1 -v
) >"$work_dir/peer.log" 2>&1 &
peer_pid=$!

peer_ready=false
for _ in $(seq 1 120); do
    if grep -q "ANDROID_MEDIA_E2E_PEER_READY" "$work_dir/peer.log"; then
        peer_ready=true
        break
    fi
    if ! kill -0 "$peer_pid" 2>/dev/null; then
        echo "Host media peer exited before becoming ready" >&2
        exit 1
    fi
    sleep 0.25
done
if [[ "$peer_ready" != true ]]; then
    echo "Host media peer did not become ready" >&2
    exit 1
fi

(
    cd "$android_dir"
    timeout 180s ./gradlew connectedDebugAndroidTest \
        -PTELEMETRY_ENDPOINT= \
        -PTELEMETRY_TOKEN= \
        -Pandroid.testInstrumentationRunnerArguments.class=com.decentralprospect.symposium.AndroidMediaE2ETest \
        -Pandroid.testInstrumentationRunnerArguments.e2eRelayAddress="$relay_host:$relay_port" \
        -Pandroid.testInstrumentationRunnerArguments.e2eRoom="$room" \
        -Pandroid.testInstrumentationRunnerArguments.e2eModeratorKey="$moderator_key" \
        -Pandroid.testInstrumentationRunnerArguments.e2eTlsPin="$tls_pin" \
        -Pandroid.testInstrumentationRunnerArguments.e2eSecret="$e2ee_secret" \
        -Pandroid.testInstrumentationRunnerArguments.e2eRequireOutboundAudio="$require_outbound_audio"
)

if ! wait "$peer_pid"; then
    peer_pid=""
    echo "Host media peer did not authenticate the required Android tracks" >&2
    exit 1
fi
peer_pid=""

if grep -Fq -- "$e2ee_secret" "$work_dir/relay.log"; then
    echo "Conference E2EE secret leaked into the relay log" >&2
    exit 1
fi

if [[ "$require_outbound_audio" == "true" ]]; then
    echo "Android media E2E passed: authenticated audio/video frame encryption in both directions through the standalone relay."
else
    echo "Android media E2E passed: authenticated inbound audio/video and outbound video through the standalone relay; outbound audio was skipped because the AVD has no input device."
fi
