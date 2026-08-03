package com.voiceid.app.ui.call

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceid.app.call.CallState
import com.voiceid.app.call.PendingCallAction
import com.voiceid.app.call.WebRtcCallManager
import com.voiceid.app.data.model.Call
import com.voiceid.app.data.remote.SupabaseModule
import com.voiceid.app.di.AppContainer
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.decodeRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CallViewModel(context: Context) : ViewModel() {

    private val callRepository = AppContainer.callRepository
    private val contactRepository = AppContainer.contactRepository
    val callManager = WebRtcCallManager(context, viewModelScope)

    val callState: StateFlow<CallState> = callManager.callState

    private val _incomingCall = MutableStateFlow<Call?>(null)
    val incomingCall: StateFlow<Call?> = _incomingCall.asStateFlow()

    private val _incomingCallerName = MutableStateFlow<String?>(null)
    val incomingCallerName: StateFlow<String?> = _incomingCallerName.asStateFlow()

    /** Listen on `calls:{userId}` for new rows where receiver_id = self — API_REFERENCE.md §4. */
    fun startListeningForIncomingCalls() {
        val userId = SupabaseModule.currentUserId() ?: return
        viewModelScope.launch {
            callRepository.incomingCallFlow(userId).collect { action ->
                if (action is PostgresAction.Insert) {
                    val call = action.decodeRecord<Call>()
                    if (call.status == "ringing" && call.receiverId == userId) {
                        _incomingCall.value = call
                        val caller = contactRepository.profileById(call.callerId)
                        val callerName = caller?.displayName ?: caller?.username ?: "Unknown"
                        _incomingCallerName.value = callerName
                        callManager.setIncomingCall(call, callerName)
                    }
                }
            }
        }
    }

    fun startOutgoingCall(receiverId: String) = callManager.startOutgoingCall(receiverId)

    /**
     * Resolves a PendingCallActionHolder action (see that file) — i.e. the user tapped
     * Answer/Decline on the incoming-call notification and the app has just opened, possibly
     * with no CallViewModel having existed yet to have received the original realtime Insert
     * (e.g. the process was killed and this call only reached us via push). Fetches the call
     * row directly by id instead of relying on _incomingCall already being populated.
     */
    fun handlePendingCallAction(action: String, callId: String) {
        viewModelScope.launch {
            val call = callRepository.getById(callId) ?: return@launch
            if (call.status != "ringing") return@launch // already handled via the in-process broadcast, or expired

            when (action) {
                PendingCallAction.ACCEPT -> {
                    _incomingCall.value = null
                    callManager.acceptIncomingCall(call)
                }
                PendingCallAction.REJECT -> {
                    _incomingCall.value = null
                    callManager.rejectIncomingCall(call)
                }
            }
        }
    }

    fun acceptIncomingCall() {
        _incomingCall.value?.let { callManager.acceptIncomingCall(it) }
        _incomingCall.value = null
    }

    fun rejectIncomingCall() {
        _incomingCall.value?.let { callManager.rejectIncomingCall(it) }
        _incomingCall.value = null
    }

    fun endCall() {
        viewModelScope.launch { callManager.endCall() }
    }

    fun cancelOutgoing() {
        viewModelScope.launch { callManager.cancelOutgoing() }
    }

    fun toggleMute(muted: Boolean) = callManager.toggleMute(muted)
}
