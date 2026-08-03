package com.voiceid.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.voiceid.app.data.model.Call
import com.voiceid.app.data.remote.SupabaseModule
import com.voiceid.app.data.repository.CallRepository
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.LinkedList

enum class CallState {
    IDLE, OUTGOING_RINGING, INCOMING_RINGING, CONNECTING, ACTIVE, ENDED
}

@Serializable
data class SdpPayload(val type: String, val sdp: String)

@Serializable
data class IceCandidatePayload(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String)

/**
 * Native Android implementation of the WebRTC voice-call signaling protocol documented in
 * BACKEND_README.md §6.3(A). Channel name (`voice-call:{callId}`), event names
 * (`receiver-ready`, `offer`, `answer`, `ice-candidate`), and payload shapes (raw
 * RTCSessionDescriptionInit / RTCIceCandidateInit JSON) are a cross-client compatibility
 * surface and MUST match the web client exactly, or a web user and an Android user cannot
 * call each other. STUN-only (no TURN configured), exactly mirroring the web client's known
 * limitation — see AI_HANDOFF.md §5 re: adding a TURN server as a documented, additive gap.
 */
class WebRtcCallManager(private val context: Context, private val scope: CoroutineScope) {

    private val callRepository = CallRepository()
    private val json = Json { ignoreUnknownKeys = true }

    private val eglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var signalingChannel: RealtimeChannel? = null
    private val iceCandidateQueue = LinkedList<IceCandidate>()
    private var remoteDescriptionSet = false

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _remoteStream = MutableStateFlow<MediaStream?>(null)
    val remoteStream: StateFlow<MediaStream?> = _remoteStream.asStateFlow()

    var activeCall: Call? = null
        private set

    private var callActionReceiver: BroadcastReceiver? = null
    private var incomingTimeoutJob: kotlinx.coroutines.Job? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    private fun ensureFactory(): PeerConnectionFactory {
        return peerConnectionFactory ?: run {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
            peerConnectionFactory = factory
            factory
        }
    }

    /** Caller flow: step 1-2 of BACKEND_README §6.3(A). */
    fun startOutgoingCall(receiverId: String) {
        scope.launch {
            val call = callRepository.createRingingCall(receiverId)
            activeCall = call
            _callState.value = CallState.OUTGOING_RINGING
            subscribeSignaling(call.id)

            // 30s no-answer timeout -> missed, per §6.3(A) step 8
            delay(30_000)
            if (_callState.value == CallState.OUTGOING_RINGING) {
                callRepository.markMissed(call.id)
                cleanup()
            }
        }
    }

    /** Receiver flow: on incoming call row, accept -> subscribe -> broadcast receiver-ready, step 3. */
    fun acceptIncomingCall(call: Call) {
        stopIncomingCallAlert()
        activeCall = call
        _callState.value = CallState.CONNECTING
        scope.launch {
            subscribeSignaling(call.id)
            signalingChannel?.broadcast(event = "receiver-ready", message = kotlinx.serialization.json.buildJsonObject {})
            callRepository.markAccepted(call.id)
        }
    }

    fun rejectIncomingCall(call: Call) {
        stopIncomingCallAlert()
        scope.launch {
            callRepository.markRejected(call.id)
            cleanup()
        }
    }

    /**
     * ROOT CAUSE FIX ("call shows in notification but there's no way to answer, other phone
     * never rings"): this previously only flipped CallState to INCOMING_RINGING with no
     * audible/visible alert of any kind outside of whatever Composable happened to be on
     * screen. Now it also: (1) plays the device's default ringtone + vibration pattern, (2)
     * posts a real heads-up notification with working Accept/Reject actions and a
     * full-screen intent, and (3) listens for those actions via a receiver registered for
     * the lifetime of this specific incoming call.
     */
    fun setIncomingCall(call: Call, callerName: String) {
        activeCall = call
        _callState.value = CallState.INCOMING_RINGING
        startIncomingCallAlert(call, callerName)
    }

    private fun startIncomingCallAlert(call: Call, callerName: String) {
        stopIncomingCallAlert() // defensive: clear any stale alert first

        CallRinger.start(context)
        IncomingCallNotificationHelper.show(context, call.id, callerName)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val incoming = activeCall ?: return
                if (intent.getStringExtra("callId") != incoming.id) return
                when (intent.action) {
                    CallActionReceiver.BROADCAST_ACCEPT -> acceptIncomingCall(incoming)
                    CallActionReceiver.BROADCAST_REJECT -> rejectIncomingCall(incoming)
                }
            }
        }
        callActionReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(CallActionReceiver.BROADCAST_ACCEPT)
            addAction(CallActionReceiver.BROADCAST_REJECT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Local safety-net timeout mirroring the caller's own 30s no-answer window (see
        // startOutgoingCall), so a receiver whose screen stays on this call doesn't keep
        // ringing/vibrating forever if the caller's own timeout update never reaches us
        // (incomingCallFlow only listens for Insert events today, not the caller's later
        // Update to status=missed).
        incomingTimeoutJob = scope.launch {
            delay(35_000)
            if (_callState.value == CallState.INCOMING_RINGING) {
                stopIncomingCallAlert()
                cleanup()
            }
        }
    }

    private fun stopIncomingCallAlert() {
        incomingTimeoutJob?.cancel()
        incomingTimeoutJob = null
        CallRinger.stop(context)
        IncomingCallNotificationHelper.cancel(context)
        callActionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        callActionReceiver = null
    }

    private suspend fun subscribeSignaling(callId: String) {
        val client = SupabaseModule.client()
        val channel = client.channel("voice-call:$callId")
        signalingChannel = channel

        scope.launch {
            channel.broadcastFlow<Map<String, Any?>>(event = "receiver-ready").collect {
                // Caller: on receiver-ready, create offer — §6.3(A) step 4
                createPeerConnectionAndOffer()
            }
        }
        scope.launch {
            channel.broadcastFlow<SdpPayload>(event = "offer").collect { offer ->
                onRemoteOffer(offer)
            }
        }
        scope.launch {
            channel.broadcastFlow<SdpPayload>(event = "answer").collect { answer ->
                onRemoteAnswer(answer)
            }
        }
        scope.launch {
            channel.broadcastFlow<IceCandidatePayload>(event = "ice-candidate").collect { candidatePayload ->
                onRemoteIceCandidate(candidatePayload)
            }
        }
        channel.subscribe(blockUntilSubscribed = true)
    }

    private fun buildPeerConnection(): PeerConnection {
        val factory = ensureFactory()
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch {
                    signalingChannel?.broadcast(
                        event = "ice-candidate",
                        message = Json.encodeToJsonElement(
                            IceCandidatePayload(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                        ) as JsonObject
                    )
                }
            }

            override fun onAddStream(stream: MediaStream) {
                // No-op under UNIFIED_PLAN — this legacy Plan-B callback does not reliably
                // fire once addTrack() (above) replaces addStream() on the sending side. See
                // onAddTrack below, which is the callback that actually fires now.
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    _callState.value = CallState.ACTIVE
                } else if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.DISCONNECTED
                ) {
                    scope.launch { endCall() }
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) {}
            override fun onRemoveStream(p0: MediaStream) {}
            override fun onDataChannel(p0: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out MediaStream>) {
                // The Unified-Plan-correct counterpart to the sending side's addTrack() above
                // — this is what actually fires (onAddStream does not), so the remote-track
                // state and ACTIVE transition both need to live here, not in onAddStream.
                _remoteStream.value = streams.firstOrNull()
                _callState.value = CallState.ACTIVE
            }
        })!!

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val audioSource: AudioSource = factory.createAudioSource(audioConstraints)
        val audioTrack = factory.createAudioTrack("voiceid-audio", audioSource)
        localAudioTrack = audioTrack
        // ROOT CAUSE FIX: sdpSemantics is UNIFIED_PLAN (line 169) but this used to call the
        // legacy Plan-B `addStream()` API, which is unreliable/unsupported when mixed with
        // Unified Plan in native WebRTC — the remote side's negotiation can silently fail to
        // produce a working track, matching "call rings/connects on signaling but audio/ICE
        // never actually completes". addTrack() is the Unified-Plan-correct API.
        val localStream = factory.createLocalMediaStream("voiceid-stream")
        localStream.addTrack(audioTrack)
        pc.addTrack(audioTrack, listOf(localStream.id))

        peerConnection = pc
        return pc
    }

    /** Caller: creates offer once receiver-ready received — §6.3(A) step 4. */
    private fun createPeerConnectionAndOffer() {
        val pc = buildPeerConnection()
        _callState.value = CallState.CONNECTING
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(SdpObserverAdapter(), desc)
                scope.launch {
                    signalingChannel?.broadcast(
                        event = "offer",
                        message = Json.encodeToJsonElement(
                            SdpPayload(type = desc.type.canonicalForm(), sdp = desc.description)
                        ) as JsonObject
                    )
                }
            }
        }, MediaConstraints())
    }

    /** Receiver: on offer, create own connection + answer — §6.3(A) step 5. */
    private fun onRemoteOffer(offer: SdpPayload) {
        val pc = peerConnection ?: buildPeerConnection()
        pc.setRemoteDescription(SdpObserverAdapter(onSuccess = {
            remoteDescriptionSet = true
            flushIceQueue(pc)
        }), SessionDescription(SessionDescription.Type.OFFER, offer.sdp))

        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(SdpObserverAdapter(), desc)
                scope.launch {
                    signalingChannel?.broadcast(
                        event = "answer",
                        message = Json.encodeToJsonElement(
                            SdpPayload(type = desc.type.canonicalForm(), sdp = desc.description)
                        ) as JsonObject
                    )
                }
            }
        }, MediaConstraints())
    }

    /** Caller: on answer, complete negotiation — §6.3(A) step 5 continuation. */
    private fun onRemoteAnswer(answer: SdpPayload) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(SdpObserverAdapter(onSuccess = {
            remoteDescriptionSet = true
            flushIceQueue(pc)
        }), SessionDescription(SessionDescription.Type.ANSWER, answer.sdp))
    }

    /** Both sides: ICE exchange with a queue for candidates arriving before remote description — step 6. */
    private fun onRemoteIceCandidate(payload: IceCandidatePayload) {
        val candidate = IceCandidate(payload.sdpMid, payload.sdpMLineIndex, payload.candidate)
        val pc = peerConnection
        if (pc != null && remoteDescriptionSet) {
            pc.addIceCandidate(candidate)
        } else {
            iceCandidateQueue.add(candidate)
        }
    }

    private fun flushIceQueue(pc: PeerConnection) {
        while (iceCandidateQueue.isNotEmpty()) {
            pc.addIceCandidate(iceCandidateQueue.poll())
        }
    }

    /** Either side ending the call — §6.3(A) step 9. */
    suspend fun endCall() {
        activeCall?.let { call ->
            if (_callState.value != CallState.ENDED) {
                callRepository.markEnded(call.id)
            }
        }
        cleanup()
    }

    suspend fun cancelOutgoing() {
        activeCall?.let { callRepository.markCancelled(it.id) }
        cleanup()
    }

    private fun cleanup() {
        stopIncomingCallAlert()
        _callState.value = CallState.ENDED
        peerConnection?.close()
        peerConnection = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        _remoteStream.value = null
        iceCandidateQueue.clear()
        remoteDescriptionSet = false
        signalingChannel?.let { ch ->
            scope.launch { SupabaseModule.client().realtime.removeChannel(ch) }
        }
        signalingChannel = null
        activeCall = null
        _callState.value = CallState.IDLE
    }

    fun toggleMute(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }
}

private open class SdpObserverAdapter(private val onSuccess: (() -> Unit)? = null) : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() { onSuccess?.invoke() }
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
