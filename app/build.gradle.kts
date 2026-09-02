import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.security.MessageDigest
import java.util.Properties

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")}\""

fun requiredString(map: Map<*, *>, key: String, source: String): String =
    (map[key] as? String)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("$source must contain a non-empty string field '$key'")

val versionFile = rootProject.file("version.properties")
if (!versionFile.isFile) {
    throw GradleException("Missing Android version definition: ${versionFile.path}")
}
val versionProperties = Properties().apply {
    versionFile.inputStream().use { load(it) }
}
val appVersionName = versionProperties.getProperty("VERSION_NAME")
    ?.takeIf { it.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$")) }
    ?: throw GradleException("version.properties contains an invalid VERSION_NAME")
val appVersionCode = versionProperties.getProperty("VERSION_CODE")
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: throw GradleException("version.properties contains an invalid VERSION_CODE")

val relayLockFile = rootProject.file("relay.lock.json")
if (!relayLockFile.isFile) {
    throw GradleException("Missing relay lock file: ${relayLockFile.path}")
}
val relayLock = try {
    JsonSlurper().parse(relayLockFile) as? Map<*, *>
        ?: throw GradleException("relay.lock.json must contain a JSON object")
} catch (error: GradleException) {
    throw error
} catch (error: Exception) {
    throw GradleException("Unable to parse relay.lock.json: ${error.message}", error)
}
val relayRepository = requiredString(relayLock, "repository", "relay.lock.json")
val relayReleaseVersion = requiredString(relayLock, "version", "relay.lock.json")
val relayAsset = requiredString(relayLock, "asset", "relay.lock.json")
val relayBinaryUrl = requiredString(relayLock, "url", "relay.lock.json")
val relayBinarySha256 = requiredString(relayLock, "sha256", "relay.lock.json")
val relayVersionPattern = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$")
val relaySha256Pattern = Regex("^[0-9a-f]{64}$")

if (relayRepository != "decentral-prospect/symposium-relay") {
    throw GradleException("relay.lock.json contains an unexpected repository: $relayRepository")
}
if (relayAsset != "symposium-server-linux-amd64") {
    throw GradleException("relay.lock.json contains an unexpected asset: $relayAsset")
}
if (!relayReleaseVersion.matches(relayVersionPattern)) {
    throw GradleException("relay.lock.json contains an invalid release version: $relayReleaseVersion")
}
if (!relayBinarySha256.matches(relaySha256Pattern)) {
    throw GradleException("relay.lock.json contains an invalid SHA-256")
}
val canonicalRelayUrl =
    "https://github.com/$relayRepository/releases/download/$relayReleaseVersion/$relayAsset"
if (relayBinaryUrl != canonicalRelayUrl) {
    throw GradleException("relay.lock.json URL must be the canonical locked release URL")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun configuredValue(name: String): String = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .getOrElse("")

val telemetryEndpoint = configuredValue("TELEMETRY_ENDPOINT")
val telemetryToken = configuredValue("TELEMETRY_TOKEN")

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull.orEmpty()
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull.orEmpty()
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull.orEmpty()
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull.orEmpty()
val releaseSigningValues = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
)
val hasAnyReleaseSigningValue = releaseSigningValues.any(String::isNotBlank)
val hasCompleteReleaseSigningConfig = releaseSigningValues.all(String::isNotBlank)
val releaseSigningRequired = providers.environmentVariable("SYMPOSIUM_REQUIRE_RELEASE_SIGNING")
    .orNull.equals("true", ignoreCase = true)

if (hasAnyReleaseSigningValue && !hasCompleteReleaseSigningConfig) {
    throw GradleException("Release signing configuration is incomplete")
}
if (releaseSigningRequired && !hasCompleteReleaseSigningConfig) {
    throw GradleException("Release signing is required but credentials are missing")
}

android {
    namespace = "com.decentralprospect.symposium"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.decentralprospect.symposium"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "TELEMETRY_ENDPOINT",
            buildConfigString(telemetryEndpoint)
        )
        buildConfigField(
            "String",
            "TELEMETRY_TOKEN",
            buildConfigString(telemetryToken)
        )
        buildConfigField(
            "String",
            "RELAY_RELEASE_VERSION",
            buildConfigString(relayReleaseVersion)
        )
        buildConfigField("String", "RELAY_BINARY_URL", buildConfigString(relayBinaryUrl))
        buildConfigField("String", "RELAY_BINARY_SHA256", buildConfigString(relayBinarySha256))
    }

    val releaseSigningConfig = if (hasCompleteReleaseSigningConfig) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystorePath)
            storePassword = releaseKeystorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    } else {
        null
    }

    buildTypes {
        release {
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

}

val verifyBundledRelayBinary by tasks.registering {
    val relayBinary = layout.projectDirectory.file(
        "src/main/assets/symposium/symposium-server-linux-amd64"
    )
    inputs.file(relayBinary)
        .withPropertyName("bundledRelayBinary")
        .optional()
    inputs.file(relayLockFile)

    doLast {
        if (!relayBinary.asFile.isFile) {
            throw GradleException(
                "Bundled relay binary is missing. Run scripts/fetch-relay.sh before building."
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        relayBinary.asFile.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == relayBinarySha256) {
            "Bundled relay checksum mismatch: expected $relayBinarySha256, got $actual"
        }
    }
}

tasks.register("printAndroidVersionName") {
    description = "Prints the authoritative Android version name."
    group = "help"
    doLast {
        println(appVersionName)
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyBundledRelayBinary)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:144.7559.09")
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
