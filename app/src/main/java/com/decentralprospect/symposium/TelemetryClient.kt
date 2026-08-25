package com.decentralprospect.symposium

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Small, first-party telemetry transport. It deliberately does not read Android identifiers,
 * account data, IP addresses, room names, user names, or conference content.
 */
internal class TelemetryClient private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val privacyPrefs = appContext.getSharedPreferences(PRIVACY_PREFS_NAME, Context.MODE_PRIVATE)
    private val endpoint = BuildConfig.TELEMETRY_ENDPOINT.trim().trimEnd('/')
    private val token = BuildConfig.TELEMETRY_TOKEN.trim()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private var appLaunchRecordedInThisProcess = false
    private var essentialFlushInFlight = false

    val configured: Boolean get() = endpoint.startsWith("https://")

    fun sendLifecycleSignals() {
        if (!configured) return

        val installationId = installationId()
        val now = System.currentTimeMillis()
        val day = now / DAY_MS
        val events = mutableListOf<JSONObject>()
        val markers = mutableListOf<Pair<String, Long>>()

        if (!prefs.getBoolean(KEY_INSTALL_SENT, false)) {
            val firstSeenAt = prefs.getLong(KEY_FIRST_SEEN_AT, now).also {
                if (!prefs.contains(KEY_FIRST_SEEN_AT)) prefs.edit().putLong(KEY_FIRST_SEEN_AT, it).apply()
            }
            events += event(
                id = "install:$installationId",
                name = "app.install",
                occurredAt = firstSeenAt,
                consented = false,
                attrs = emptyMap()
            )
            markers += KEY_INSTALL_SENT to 1L
        }

        if (prefs.getLong(KEY_LAST_HEARTBEAT_DAY, -1L) != day) {
            events += event(
                id = "installed:$installationId:$day",
                name = "app.installed",
                occurredAt = now,
                consented = false,
                attrs = emptyMap()
            )
            markers += KEY_LAST_HEARTBEAT_DAY to day
        }

        if (events.isNotEmpty()) send(events) {
            val editor = prefs.edit()
            markers.forEach { (key, value) ->
                if (key == KEY_INSTALL_SENT) editor.putBoolean(key, true) else editor.putLong(key, value)
            }
            editor.apply()
        }
        flushPendingEssentialEvents()
    }

    fun recordDiagnostic(name: String, attrs: Map<String, Any>) {
        if (!configured || !privacyPrefs.getBoolean(PREF_TELEMETRY_ENABLED, false)) return
        send(
            listOf(
                event(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    occurredAt = System.currentTimeMillis(),
                    consented = true,
                    attrs = attrs
                )
            )
        )
    }

    @Synchronized
    fun recordAppLaunch() {
        if (appLaunchRecordedInThisProcess) return
        appLaunchRecordedInThisProcess = true
        enqueueEssentialEvent("app.launch")
    }

    fun recordConsentChange(enabled: Boolean) {
        enqueueEssentialEvent(if (enabled) "telemetry.consent.enabled" else "telemetry.consent.disabled")
    }

    private fun enqueueEssentialEvent(name: String) {
        if (!configured) return
        val entry = listOf(
            UUID.randomUUID().toString(),
            name,
            System.currentTimeMillis().toString()
        ).joinToString(PENDING_SEPARATOR)

        synchronized(this) {
            val pending = prefs.getStringSet(KEY_PENDING_ESSENTIAL_EVENTS, emptySet()).orEmpty().toMutableSet()
            pending += entry
            val bounded = pending
                .sortedByDescending { parsePendingEssential(it)?.occurredAt ?: 0L }
                .take(MAX_PENDING_ESSENTIAL_EVENTS)
                .toSet()
            prefs.edit().putStringSet(KEY_PENDING_ESSENTIAL_EVENTS, bounded).apply()
        }
        flushPendingEssentialEvents()
    }

    private fun flushPendingEssentialEvents() {
        if (!configured) return
        val batch = synchronized(this) {
            if (essentialFlushInFlight) return
            val parsed = prefs.getStringSet(KEY_PENDING_ESSENTIAL_EVENTS, emptySet())
                .orEmpty()
                .mapNotNull(::parsePendingEssential)
                .sortedBy { it.occurredAt }
                .take(MAX_EVENTS_PER_REQUEST)
            if (parsed.isEmpty()) return
            essentialFlushInFlight = true
            parsed
        }

        send(
            events = batch.map { pending ->
                event(
                    id = "essential:${pending.id}",
                    name = pending.name,
                    occurredAt = pending.occurredAt,
                    consented = false,
                    attrs = emptyMap()
                )
            },
            onFinished = { accepted ->
                synchronized(this) {
                    if (accepted) {
                        val sent = batch.mapTo(mutableSetOf()) { it.raw }
                        val remaining = prefs.getStringSet(KEY_PENDING_ESSENTIAL_EVENTS, emptySet())
                            .orEmpty()
                            .filterNotTo(mutableSetOf()) { it in sent }
                        prefs.edit().putStringSet(KEY_PENDING_ESSENTIAL_EVENTS, remaining).apply()
                    }
                    essentialFlushInFlight = false
                }
                if (accepted) flushPendingEssentialEvents()
            }
        )
    }

    private fun parsePendingEssential(raw: String): PendingEssential? {
        val parts = raw.split(PENDING_SEPARATOR, limit = 3)
        if (parts.size != 3) return null
        val name = parts[1]
        if (name !in ESSENTIAL_EVENT_NAMES) return null
        val occurredAt = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        return PendingEssential(raw, parts[0], name, occurredAt)
    }

    @Synchronized
    private fun installationId(): String {
        prefs.getString(KEY_INSTALLATION_ID, null)?.let { return it }
        val created = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_INSTALLATION_ID, created).apply()
        return created
    }

    private fun event(
        id: String,
        name: String,
        occurredAt: Long,
        consented: Boolean,
        attrs: Map<String, Any>
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("occurredAt", occurredAt)
        .put("consented", consented)
        .put("attributes", JSONObject(attrs))

    private fun send(
        events: List<JSONObject>,
        onAccepted: (() -> Unit)? = null,
        onFinished: ((Boolean) -> Unit)? = null
    ) {
        val body = JSONObject()
            .put("installationId", installationId())
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("platform", "android")
            .put("events", JSONArray(events))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val url = if (endpoint.endsWith("/api/v1/events")) endpoint else "$endpoint/api/v1/events"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Symposium/${BuildConfig.VERSION_NAME} (Android)")
            .header("X-Symposium-Telemetry-Protocol", TELEMETRY_PROTOCOL_VERSION)
            .post(body)
        if (token.isNotEmpty()) requestBuilder.header("Authorization", "Bearer $token")

        http.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                diagnosticWarning(TAG, "Telemetry delivery failed: ${e.javaClass.simpleName}")
                onFinished?.invoke(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        onAccepted?.invoke()
                        onFinished?.invoke(true)
                    } else {
                        val error = it.body?.string().orEmpty().take(160)
                        diagnosticWarning(TAG, "Telemetry endpoint returned HTTP ${it.code}: $error")
                        onFinished?.invoke(false)
                    }
                }
            }
        })
    }

    companion object {
        private data class PendingEssential(
            val raw: String,
            val id: String,
            val name: String,
            val occurredAt: Long
        )

        private const val PREFS_NAME = "first_party_telemetry"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_FIRST_SEEN_AT = "first_seen_at"
        private const val KEY_INSTALL_SENT = "install_sent"
        private const val KEY_LAST_HEARTBEAT_DAY = "last_heartbeat_day"
        private const val KEY_PENDING_ESSENTIAL_EVENTS = "pending_essential_events"
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val PENDING_SEPARATOR = "|"
        private const val MAX_PENDING_ESSENTIAL_EVENTS = 512
        private const val MAX_EVENTS_PER_REQUEST = 32
        private const val TELEMETRY_PROTOCOL_VERSION = "2"
        private val ESSENTIAL_EVENT_NAMES = setOf(
            "app.launch",
            "telemetry.consent.enabled",
            "telemetry.consent.disabled"
        )
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        @Volatile private var instance: TelemetryClient? = null

        fun get(context: Context): TelemetryClient = instance ?: synchronized(this) {
            instance ?: TelemetryClient(context).also { instance = it }
        }
    }
}
