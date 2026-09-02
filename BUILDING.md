# Building Symposium for Android

## Prerequisites

- Git;
- JDK 17;
- Android SDK Platform 36 and current Android SDK build tools;
- `curl`, `jq`, `sha256sum`, and Bash.

GitHub CLI (`gh`) is needed only when updating a relay lock or explicitly
checking relay provenance. It is not required for an ordinary Android build.

Android Gradle Plugin 8.13 uses Gradle 8.13 and JDK 17. The Gradle wrapper pins
the official Gradle distribution SHA-256 and should be used instead of a system
Gradle installation.

## Clean build

From a fresh checkout:

```bash
scripts/test-relay-tools.sh
scripts/fetch-relay.sh
./gradlew --dependency-verification strict :app:testDebugUnitTest
./gradlew --dependency-verification strict :app:lintDebug
./gradlew --dependency-verification strict :app:assembleDebug
./gradlew --dependency-verification strict :app:assembleRelease
```

`scripts/fetch-relay.sh` reads `relay.lock.json`, downloads only its canonical
asset when it is not already present, verifies the locked SHA-256, and atomically
installs it under `app/src/main/assets/symposium/`. It never changes the lock or
selects another relay version. If the correct binary is already present, the
script and Gradle's relay verification need neither network access nor `gh`.
Gradle may still need its normal dependency repositories on an empty dependency
cache.

The relay asset is intentionally ignored by Git. A developer never needs to
copy a relay binary manually. Every Android build verifies the bundled asset
against `relay.lock.json` before compilation.

Relay SLSA and CycloneDX attestations are checked separately at trust
boundaries. `scripts/update-relay-lock.sh` checks them before changing the lock;
`scripts/verify-relay-provenance.sh` checks an existing lock. CI runs the latter
for a changed `relay.lock.json` and on manual runs, while the release workflow
runs it for every official release.

Dependency verification is strict whenever `gradle/verification-metadata.xml`
is present. If an intentional dependency or plugin update changes artifacts,
regenerate metadata from the actual build tasks using an empty Gradle user home,
then review every checksum diff:

```bash
cold_gradle_home="$(mktemp -d)"
GRADLE_USER_HOME="$cold_gradle_home" ./gradlew --write-verification-metadata sha256 \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleRelease \
  :app:bundleRelease \
  :app:assembleDebugAndroidTest
```

Do not accept new metadata without confirming that the requested dependency
change and resolved repositories are expected. Validate the reviewed file by
running the same tasks with `--dependency-verification strict` and a second empty
`GRADLE_USER_HOME`; this catches descriptors omitted by a warm cache.

## Updating the locked relay

Relay releases use tags without a leading `v`. To pin a new immutable release:

```bash
scripts/update-relay-lock.sh 0.3.4
scripts/fetch-relay.sh
```

The update script obtains the checksum from the release's `SHA256SUMS`, compares
it with GitHub's immutable asset digest, verifies the binary, verifies SLSA and
CycloneDX attestations against `decentral-prospect/symposium-relay`, and only
then atomically replaces `relay.lock.json`. It never trusts a caller-provided
checksum.

Android application versions are defined independently in `version.properties`.
Changing the relay lock does not change `versionName` or `versionCode`.

## Telemetry configuration

Telemetry is disabled when its endpoint is blank. Values can be supplied as
Gradle properties or environment variables:

```bash
./gradlew :app:assembleDebug \
  -PTELEMETRY_ENDPOINT=https://telemetry.example.invalid \
  -PTELEMETRY_TOKEN=local-value
```

Do not store telemetry credentials in `gradle.properties`. Anything embedded in
an Android APK can be extracted by an end user and must not be treated as a
strong secret or security boundary. Server-side authorization should limit the
scope and value of any embedded token.

## Signed release build

Local release signing uses environment variables only:

```bash
export ANDROID_KEYSTORE_PATH=/absolute/path/to/release.jks
export ANDROID_KEYSTORE_PASSWORD='...'
export ANDROID_KEY_ALIAS='...'
export ANDROID_KEY_PASSWORD='...'
export SYMPOSIUM_REQUIRE_RELEASE_SIGNING=true
./gradlew --dependency-verification strict :app:assembleRelease :app:bundleRelease
```

An unsigned `assembleRelease` is allowed for local and pull-request compilation.
Official releases set `SYMPOSIUM_REQUIRE_RELEASE_SIGNING=true`, so missing or
partial signing credentials fail the build.

## Android media E2E

Start an emulator or connect a device and point the runner at an explicit relay
checkout when it is not available at the legacy sibling path:

```bash
SYMPOSIUM_RELAY_DIR=/absolute/path/to/symposium-relay \
SYMPOSIUM_RELAY_REVISION=0.3.4 \
scripts/android-media-e2e.sh
```

The revision check prevents an unintended relay checkout from being used. The
scheduled E2E workflow checks out the exact relay tag from `relay.lock.json`.
