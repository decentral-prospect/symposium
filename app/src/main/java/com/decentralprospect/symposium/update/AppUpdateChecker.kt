package com.decentralprospect.symposium

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val APP_VERSION_ENDPOINT = "https://symposium-dp.app/version"
private val VERSION_VALUE_PATTERN = Regex(
    pattern = "[vV]?[0-9]+(?:\\.[0-9A-Za-z]+)*(?:[-+][0-9A-Za-z.-]+)?"
)

internal object AppUpdateChecker {
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    suspend fun findAvailableVersion(currentVersion: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(APP_VERSION_ENDPOINT)
                .header("Accept", "text/plain")
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null

                availableVersionFromResponse(
                    currentVersion = currentVersion,
                    rawRemoteVersion = response.body?.string().orEmpty()
                )
            }
        }.getOrNull()
    }
}

internal fun availableVersionFromResponse(
    currentVersion: String,
    rawRemoteVersion: String
): String? {
    val remoteVersion = rawRemoteVersion
        .trim()
        .removePrefix("\uFEFF")
        .trim()

    if (!VERSION_VALUE_PATTERN.matches(remoteVersion)) return null

    return remoteVersion.takeUnless {
        normalizeVersionForComparison(it) == normalizeVersionForComparison(currentVersion)
    }
}

private fun normalizeVersionForComparison(version: String): String =
    version.trim().removePrefix("v").removePrefix("V")
