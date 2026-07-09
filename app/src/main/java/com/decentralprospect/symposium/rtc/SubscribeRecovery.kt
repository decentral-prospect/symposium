package com.decentralprospect.symposium

internal fun CallRuntime.cancelSubscribeRecovery() {
    subscribeRecoveryRunnable?.let { subscribeRecoveryHandler.removeCallbacks(it) }
    subscribeRecoveryRunnable = null
}

internal fun CallRuntime.scheduleSubscribeRecovery(reason: String) {
    val pcAtSchedule = subscribePeerConnection ?: return
    if (!joinedRoom) return

    cancelSubscribeRecovery()

    val runnable = Runnable {
        val pc = subscribePeerConnection ?: return@Runnable

        if (pc !== pcAtSchedule) {
            diagLog("Skip subscribe recovery; PC changed", reason)
            return@Runnable
        }

        val stillBad =
            subscribeIceState == "failed" ||
                    subscribeIceState == "disconnected" ||
                    subscribePcState == "failed" ||
                    subscribePcState == "disconnected"

        if (!joinedRoom || webSocket == null || !stillBad) {
            diagLog(
                "Skip subscribe recovery; recovered",
                "reason=$reason subIce=$subscribeIceState subPc=$subscribePcState"
            )
            return@Runnable
        }

        diagLog(
            "Restart subscribe PC after debounce",
            "reason=$reason subIce=$subscribeIceState subPc=$subscribePcState"
        )

        restartSubscribePeerConnection(reason)
    }

    subscribeRecoveryRunnable = runnable
    subscribeRecoveryHandler.postDelayed(runnable, 3500L)
}
