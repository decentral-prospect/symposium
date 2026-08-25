package com.decentralprospect.symposium

import android.util.Log

/** Keeps app diagnostics out of release logcat while preserving debug diagnostics. */
internal fun debugLog(tag: String, message: String) {
    if (BuildConfig.DEBUG) {
        Log.d(tag, message)
    }
}

internal fun diagnosticWarning(tag: String, message: String) {
    if (BuildConfig.DEBUG) {
        Log.w(tag, message)
    }
}

internal fun diagnosticError(tag: String, message: String) {
    if (BuildConfig.DEBUG) {
        Log.e(tag, message)
    }
}
