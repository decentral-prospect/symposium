# Symposium Android Client



Privacy-focused conference calls for communities operating under censorship and network restrictions.



## About



Symposium is an open-source Android application for audio and video conferences designed to remain usable in environments with unstable connectivity, network interference, and censorship.

The application connects to self-hosted Symposium Relay servers and uses WebRTC for real-time media transmission.










## Requirements



* Android 7.0 (API 24) or newer

* Microphone permission

* Camera permission (video calls only)

* Internet connection





## Telemetry



Symposium uses its own minimal telemetry service. Without diagnostic consent it sends only a random installation ID, the app version, installation and launch events, a daily installed heartbeat, and diagnostic-consent changes. Android cannot reliably report its own uninstall; the server labels long-inactive installations as likely removed instead.

After explicit consent, Symposium sends a limited set of anonymous operational metrics to improve reliability.



Examples include:



* Application version

* Connection success and failure events

* Conference duration

* Network quality indicators



No conference content, audio, video, messages, or participant identities are transmitted through telemetry systems.

Set `TELEMETRY_ENDPOINT` and `TELEMETRY_TOKEN` as Gradle properties or
environment variables to enable delivery. If the endpoint is empty, no telemetry
leaves the device. Do not commit these values to `gradle.properties`. Values
embedded in an Android APK can be extracted and are not a strong secret boundary.



## Project Status



The project is under active development.



Current priorities:



* Stability improvements

* Security hardening

* Accessibility enhancements

* Large-scale testing under restricted network conditions


## Media end-to-end encryption

Symposium encrypts encoded Opus and video frames on the sending Android device
and authenticates/decrypts them only on receiving devices. The SFU relay forwards
the encrypted RTP payloads without receiving the conference key, so it cannot
listen to, view, transcode, or record the media content.

Each room invitation contains a random 32-byte secret in the URL fragment:
`#e2ee=...`. URL fragments are not part of HTTP requests, WebSocket signaling,
or relay logs. Android stores imported/generated room secrets in encrypted local
preferences backed by Android Keystore. Legacy invitations without a valid E2EE
fragment are rejected.

The current media suite is `frame-aes-gcm-v1`: WebRTC FrameCryptor with AES-GCM,
a key derived with PBKDF2-HMAC-SHA256 (100,000 iterations), and fail-closed frame
discarding until the encryptor/decryptor and key are ready. Audio and video both
use the same room secret. RTP headers and routing metadata remain outside the
room-key encryption so the SFU can route streams; each media hop still uses
DTLS-SRTP for transport protection.

This protects media content from the relay, hosting provider, and network
observers. It does not hide IP addresses or packet timing/sizes from network
observers, nor room membership and track metadata from the relay. Anyone who
obtains an invitation gets the room secret. This first version uses a shared room
key and does not yet provide MLS-style member-specific keys, forward secrecy, or
post-compromise security; closing and reopening a room creates a fresh secret.


## End-to-end media tests

The relay has in-process tests for signaling and relayed Opus/VP8 RTP, plus an
AES-GCM wire-format test that rejects modified ciphertext:

```bash
cd ../S-server
go test -run TestMediaTrafficE2EAudioAndVideo -v
```

The Android E2E launches the real activity, call service, signaling client,
microphone, camera, and split WebRTC peer connections against a standalone relay.
An independent host peer encrypts media for Android, authenticates Android's
encrypted media in the reverse direction, and verifies that the room secret never
appears in the relay log. Start an Android emulator or connect a device, then run:

```bash
./scripts/android-media-e2e.sh
```

The runner defaults to the Android Emulator host alias `10.0.2.2`. For a physical
device, set `SYMPOSIUM_E2E_RELAY_HOST` to the development machine's LAN address.
Set `SYMPOSIUM_RELAY_DIR` to an explicit `symposium-relay` checkout when it is
not available at the legacy sibling path; `SYMPOSIUM_RELAY_REVISION` can require
an exact tag or commit.
An AVD without an audio input can test every path except Android outbound audio
with `SYMPOSIUM_ANDROID_E2E_REQUIRE_OUTBOUND_AUDIO=false`; physical-device and CI
runs require it by default.




## License



Licensed under the Apache License 2.0.



See the LICENSE file for details.
