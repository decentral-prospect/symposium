package com.decentralprospect.symposium

internal const val TAG = "RTC_APP"
internal val APP_VERSION_NAME: String = BuildConfig.VERSION_NAME
internal val APP_VERSION: String = "v$APP_VERSION_NAME"
internal val EXPECTED_RELAY_VERSION: String = APP_VERSION
internal const val TARGET_PUBLISH = "publish"
internal const val TARGET_SUBSCRIBE = "subscribe"
internal const val ROLE_GUEST = "guest"
internal const val ROLE_MODERATOR = "moderator"
internal const val RECONNECT_INITIAL_DELAY_MS = 350L
internal const val RECONNECT_BASE_DELAY_MS = 2_000L
internal const val RECONNECT_MAX_DELAY_MS = 30_000L
internal const val RECONNECT_MAX_JITTER_MS = 1_500L
internal const val RECONNECT_CONNECT_POLL_MS = 1_000L
internal const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L
internal const val WAKELOCK_REFRESH_MS = 5 * 60 * 1000L
internal const val PRIVACY_PREFS_NAME = "privacy_settings"
internal const val PREF_TELEMETRY_ENABLED = "telemetry_enabled"
internal const val PREF_TELEMETRY_PROMPT_SHOWN = "telemetry_prompt_shown"
internal const val TELEMETRY_STATS_INTERVAL_MS = 15000L
internal const val TELEMETRY_PING_EVENT_INTERVAL_MS = 60000L
internal const val TELEMETRY_SLOW_PING_MS = 1500L
internal const val TELEMETRY_CRITICAL_PING_MS = 3000L
internal const val TELEMETRY_BAD_JITTER_SEC = 0.08
internal const val TELEMETRY_BAD_CANDIDATE_RTT_SEC = 1.0
