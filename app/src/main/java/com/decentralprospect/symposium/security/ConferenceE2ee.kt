package com.decentralprospect.symposium

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.webrtc.CryptoOptions
import org.webrtc.FrameCryptor
import org.webrtc.FrameCryptorAlgorithm
import org.webrtc.FrameCryptorFactory
import org.webrtc.FrameCryptorKeyDerivationAlgorithm
import org.webrtc.FrameCryptorKeyProvider
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

internal const val CONFERENCE_E2EE_SUITE = "frame-aes-gcm-v1"
internal const val CONFERENCE_E2EE_SECRET_BYTES = 32
internal const val CONFERENCE_E2EE_KEY_INDEX = 0

private const val E2EE_KEY_RING_SIZE = 1
private const val E2EE_FAILURE_TOLERANCE = 0
private const val E2EE_RATCHET_WINDOW_SIZE = 0
private const val E2EE_PREFS_NAME = "conference_e2ee_secrets_v1"
private val E2EE_RATCHET_SALT = "SymposiumFrameEncryptionKey/v1".toByteArray(Charsets.UTF_8)
private val E2EE_SECRET_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")

internal fun generateConferenceE2eeSecret(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(CONFERENCE_E2EE_SECRET_BYTES)
    random.nextBytes(bytes)
    return encodeConferenceE2eeSecret(bytes)
}

internal fun decodeConferenceE2eeSecret(raw: String): ByteArray {
    val normalized = raw.trim()
    require(E2EE_SECRET_PATTERN.matches(normalized)) { "invalid conference E2EE secret" }

    val decoded = runCatching {
        Base64.decode(normalized, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }.getOrElse {
        throw IllegalArgumentException("invalid conference E2EE secret", it)
    }
    require(decoded.size == CONFERENCE_E2EE_SECRET_BYTES) { "invalid conference E2EE secret length" }
    require(encodeConferenceE2eeSecret(decoded) == normalized) { "non-canonical conference E2EE secret" }
    return decoded
}

internal fun normalizeConferenceE2eeSecret(raw: String): String =
    encodeConferenceE2eeSecret(decodeConferenceE2eeSecret(raw))

private fun encodeConferenceE2eeSecret(bytes: ByteArray): String =
    Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

internal class ConferenceE2eeSecretStore(context: Context) {
    private val appContext = context.applicationContext

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val securePrefs by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            E2EE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getOrCreate(host: String, port: Int?, room: String): String {
        val key = storageKey(host, port, room)
        val existing = securePrefs.getString(key, null)
        if (!existing.isNullOrBlank()) {
            runCatching { normalizeConferenceE2eeSecret(existing) }.getOrNull()?.let { return it }
        }
        return generateConferenceE2eeSecret().also { secret ->
            check(securePrefs.edit().putString(key, secret).commit()) {
                "failed to store the conference E2EE secret"
            }
        }
    }

    fun rotate(host: String, port: Int?, room: String): String =
        generateConferenceE2eeSecret().also { secret ->
            check(securePrefs.edit().putString(storageKey(host, port, room), secret).commit()) {
                "failed to rotate the conference E2EE secret"
            }
        }

    fun save(host: String, port: Int?, room: String, rawSecret: String): String {
        val secret = normalizeConferenceE2eeSecret(rawSecret)
        check(securePrefs.edit().putString(storageKey(host, port, room), secret).commit()) {
            "failed to store the imported conference E2EE secret"
        }
        return secret
    }

    fun remove(host: String, port: Int?, room: String) {
        securePrefs.edit().remove(storageKey(host, port, room)).apply()
    }

    private fun storageKey(host: String, port: Int?, room: String): String {
        val rawAuthority = host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("wss://")
            .removePrefix("ws://")
            .substringBefore('/')
        val endpoint = android.net.Uri.parse("https://$rawAuthority")
        val canonicalHost = endpoint.host?.trim()?.lowercase().orEmpty()
        require(canonicalHost.isNotBlank()) { "invalid relay host" }
        val canonicalPort = port ?: endpoint.port.takeIf { it > 0 } ?: DEFAULT_RELAY_HTTP_PORT
        val canonical = "$canonicalHost:$canonicalPort|${room.trim()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

internal fun requiredFrameCryptoOptions(): CryptoOptions = CryptoOptions.builder()
    .setEnableGcmCryptoSuites(true)
    // RTP header extensions remain hop-by-hop SRTP metadata. Enabling their
    // separate encryption breaks interoperability with the Pion relay/peers;
    // encoded audio/video frame bodies are still authenticated end to end.
    .setEnableEncryptedRtpHeaderExtensions(false)
    // This switch is exclusively for WebRTC's legacy FrameEncryptor /
    // FrameDecryptor API. FrameCryptor uses encoded-frame transformers and
    // enforces fail-closed behavior through discardFrameWhenCryptorNotReady.
    .setRequireFrameEncryption(false)
    .createCryptoOptions()

internal fun CallRuntime.configureConferenceE2ee(rawSecret: String) {
    disposeConferenceE2ee()
    val secret = normalizeConferenceE2eeSecret(rawSecret)
    val keyMaterial = decodeConferenceE2eeSecret(secret)

    val provider = FrameCryptorFactory.createFrameCryptorKeyProvider(
        true,
        E2EE_RATCHET_SALT,
        E2EE_RATCHET_WINDOW_SIZE,
        byteArrayOf(),
        E2EE_FAILURE_TOLERANCE,
        E2EE_KEY_RING_SIZE,
        true,
        FrameCryptorKeyDerivationAlgorithm.PBKDF2
    )
    check(provider.setSharedKey(CONFERENCE_E2EE_KEY_INDEX, keyMaterial)) {
        "WebRTC rejected the conference E2EE key"
    }

    conferenceE2eeKeyProvider = provider
    conferenceE2eeEnabled = true
    conferenceE2eeLastError = null
    diagLog("Conference E2EE configured", CONFERENCE_E2EE_SUITE)
}

internal fun CallRuntime.attachE2eeSender(sender: RtpSender, kind: String) {
    check(conferenceE2eeEnabled) { "conference E2EE is not configured" }
    check(sender.track() != null) { "$kind sender has no media track" }
    val provider = conferenceE2eeKeyProvider ?: error("conference E2EE key provider is missing")
    val factory = pcFactory ?: error("peer connection factory is missing")
    val senderId = sender.id()
    if (conferenceE2eeSenderCryptors.containsKey(senderId)) return

    val cryptor = FrameCryptorFactory.createFrameCryptorForRtpSender(
        factory,
        sender,
        "conference-shared",
        FrameCryptorAlgorithm.AES_GCM,
        provider
    )
    // WebRTC M144's Android observer wrapper does not retain/release its JNI
    // pointer correctly. Keep state callbacks in debug/tests; the cryptor and
    // fail-closed behavior are identical in release builds.
    if (BuildConfig.DEBUG) {
        observeFrameCryptor(cryptor, "sender", kind, senderId)
    }
    cryptor.setKeyIndex(CONFERENCE_E2EE_KEY_INDEX)
    cryptor.setEnabled(true)
    check(cryptor.isEnabled()) { "WebRTC did not enable the $kind frame encryptor" }
    conferenceE2eeSenderCryptors[senderId] = cryptor
    diagLog("E2EE sender attached", "kind=$kind id=$senderId")
}

internal fun CallRuntime.attachE2eeReceiver(receiver: RtpReceiver) {
    check(conferenceE2eeEnabled) { "conference E2EE is not configured" }
    val provider = conferenceE2eeKeyProvider ?: error("conference E2EE key provider is missing")
    val factory = pcFactory ?: error("peer connection factory is missing")
    val receiverId = receiver.id()
    if (conferenceE2eeReceiverCryptors.containsKey(receiverId)) return

    val kind = receiver.track()?.kind().orEmpty().ifBlank { "unknown" }
    val cryptor = FrameCryptorFactory.createFrameCryptorForRtpReceiver(
        factory,
        receiver,
        "conference-shared",
        FrameCryptorAlgorithm.AES_GCM,
        provider
    )
    if (BuildConfig.DEBUG) {
        observeFrameCryptor(cryptor, "receiver", kind, receiverId)
    }
    cryptor.setKeyIndex(CONFERENCE_E2EE_KEY_INDEX)
    cryptor.setEnabled(true)
    check(cryptor.isEnabled()) { "WebRTC did not enable the $kind frame decryptor" }
    conferenceE2eeReceiverCryptors[receiverId] = cryptor
    diagLog("E2EE receiver attached", "kind=$kind id=$receiverId")
}

private fun CallRuntime.observeFrameCryptor(
    cryptor: FrameCryptor,
    direction: String,
    kind: String,
    id: String
) {
    cryptor.setObserver { _, state ->
        diagLog(
            "E2EE frame state",
            "direction=$direction kind=$kind state=${state.name.lowercase()}"
        )
        postUi {
            val stateName = state.name.lowercase()
            conferenceE2eeStates["$direction:$kind:$id"] = stateName
            when (state) {
                FrameCryptor.FrameCryptionState.ENCRYPTIONFAILED,
                FrameCryptor.FrameCryptionState.DECRYPTIONFAILED,
                FrameCryptor.FrameCryptionState.MISSINGKEY,
                FrameCryptor.FrameCryptionState.INTERNALERROR -> {
                    conferenceE2eeLastError = "$direction $kind: $stateName"
                    diagnosticError(TAG, "E2EE $direction $kind failed: $stateName")
                }

                else -> Unit
            }
        }
    }
}

internal fun CallRuntime.disposeE2eeReceiverCryptors() {
    conferenceE2eeReceiverCryptors.values.forEach { cryptor ->
        runCatching { cryptor.dispose() }
    }
    conferenceE2eeReceiverCryptors.clear()
    conferenceE2eeStates.keys.removeAll { it.startsWith("receiver:") }
}

internal fun CallRuntime.disposeE2eePeerCryptors() {
    conferenceE2eeSenderCryptors.values.forEach { cryptor ->
        runCatching { cryptor.dispose() }
    }
    conferenceE2eeSenderCryptors.clear()
    disposeE2eeReceiverCryptors()
    conferenceE2eeStates.clear()
}

internal fun CallRuntime.disposeConferenceE2ee() {
    disposeE2eePeerCryptors()
    runCatching { conferenceE2eeKeyProvider?.dispose() }
    conferenceE2eeKeyProvider = null
    conferenceE2eeEnabled = false
    conferenceE2eeLastError = null
}

internal fun ConcurrentHashMap<String, String>.hasOkState(direction: String, kind: String): Boolean =
    entries.any { (key, state) ->
        key.startsWith("$direction:$kind:") && (state == "ok" || state == "keyratcheted")
    }
