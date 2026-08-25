import java.security.MessageDigest

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val appVersionName = "0.3.3"
val relayBinarySha256ByVersion = mapOf(
    "0.3.2" to "d809a98b415a46408935d5208c6b13a584a1bdad00feba4e06cff9a2eda3bcb1",
    "0.3.3" to "08c42c364fd8aed2578cefa9aa0f3af61e4a2453951e9bf08e4a137c15558f4f"
)
val relayBinarySha256 = requireNotNull(relayBinarySha256ByVersion[appVersionName]) {
    "Missing pinned relay checksum for app version $appVersionName"
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.decentralprospect.symposium"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.decentralprospect.symposium"
        minSdk = 24
        targetSdk = 36
        versionCode = 33
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "TELEMETRY_ENDPOINT",
            buildConfigString(project.findProperty("TELEMETRY_ENDPOINT")?.toString().orEmpty())
        )
        buildConfigField(
            "String",
            "TELEMETRY_TOKEN",
            buildConfigString(project.findProperty("TELEMETRY_TOKEN")?.toString().orEmpty())
        )
        buildConfigField("String", "RELAY_BINARY_SHA256", buildConfigString(relayBinarySha256))
    }

    buildTypes {
        release {
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

    doLast {
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

tasks.named("preBuild").configure {
    dependsOn(verifyBundledRelayBinary)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:144.7559.09")
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
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
