package com.decentralprospect.symposium

internal const val TAG = "RTC_APP"
internal const val APP_VERSION = "v0.3.2"
internal const val EXPECTED_RELAY_VERSION = APP_VERSION
internal const val TARGET_PUBLISH = "publish"
internal const val TARGET_SUBSCRIBE = "subscribe"
internal const val ROLE_GUEST = "guest"
internal const val ROLE_MODERATOR = "moderator"
internal const val RECONNECT_DELAY_MS = 5000L
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
