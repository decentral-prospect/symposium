package com.decentralprospect.symposium

import android.util.Base64
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal fun CallRuntime.parseHostPort(input: String): HostPort {
    var s = input.trim()
    s = s.removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("wss://")
        .removePrefix("ws://")
        .trim()
    s = s.substringBefore("/")
    val host = s.substringBefore(":")
    val port = s.substringAfter(":", "").toIntOrNull() ?: 443
    return HostPort(host, port)
}

internal fun CallRuntime.normalizeTlsPin(pin: String): String {
    val p = pin.trim()
    require(p.startsWith("sha256/")) { "TLS pin must start with sha256/" }
    require(p.length > "sha256/".length) { "TLS pin is empty" }
    return p
}

internal class SpkiPinTrustManager(expectedPinRaw: String) : X509TrustManager {
    private val expectedPin = normalizePin(expectedPinRaw)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Empty server certificate chain")
        }

        val peerPins = mutableListOf<String>()
        for (cert in chain) {
            val pin = spkiPin(cert)
            peerPins += pin
            if (secureEquals(pin, expectedPin)) return
        }

        throw CertificateException(
            buildString {
                append("SPKI pin mismatch.\n")
                append("Expected: ").append(expectedPin).append('\n')
                append("Peer pins:\n")
                peerPins.forEach { append("  ").append(it).append('\n') }
            }
        )
    }

    private fun spkiPin(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        val b64 = Base64.encodeToString(digest, Base64.NO_WRAP)
        return "sha256/$b64"
    }

    private fun secureEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }

    companion object {
        private fun normalizePin(pin: String): String {
            val p = pin.trim()
            require(p.startsWith("sha256/")) { "TLS pin must start with sha256/" }
            require(p.length > "sha256/".length) { "TLS pin is empty" }
            return p
        }
    }
}

internal fun CallRuntime.pinnedOkHttpClient(host: String, tlsPin: String): OkHttpClient {
    val normalizedPin = normalizeTlsPin(tlsPin)
    val key = "$host|$normalizedPin"

    return pinnedClients.getOrPut(key) {
        val pinTrustManager = SpkiPinTrustManager(normalizedPin)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(pinTrustManager), SecureRandom())

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, pinTrustManager)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}
