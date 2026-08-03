package com.voiceid.app.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingCallAction(val action: String, val callId: String) {
    companion object {
        const val ACCEPT = "accept"
        const val REJECT = "reject"
    }
}

/**
 * Hand-off point between MainActivity (which receives the Accept/Reject intent extras when
 * the user taps a notification action and the app has to cold-start or come to the
 * foreground) and CallViewModel (which is only constructed once Compose reaches
 * MainNavGraph). MainActivity.onCreate/onNewIntent writes here; MainNavGraph's LaunchedEffect
 * consumes it exactly once. A plain StateFlow-backed object rather than a ViewModel itself,
 * since MainActivity needs to reach it before any ViewModel necessarily exists yet.
 */
object PendingCallActionHolder {
    private val _current = MutableStateFlow<PendingCallAction?>(null)
    val current: StateFlow<PendingCallAction?> = _current.asStateFlow()

    fun set(action: PendingCallAction) {
        _current.value = action
    }

    fun consume() {
        _current.value = null
    }
}
