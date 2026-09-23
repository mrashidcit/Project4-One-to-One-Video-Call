package com.mrashidcit.project4one_to_onevideocall.data.repository

import android.util.Log
import com.mrashidcit.project4one_to_onevideocall.data.signaling.SignalingClient
import com.mrashidcit.project4one_to_onevideocall.data.webrtc.AudioRouteManager
import com.mrashidcit.project4one_to_onevideocall.data.webrtc.LocalMediaManager
import com.mrashidcit.project4one_to_onevideocall.data.webrtc.PeerConnectionManager
import com.mrashidcit.project4one_to_onevideocall.data.webrtc.WebRtcClient
import com.mrashidcit.project4one_to_onevideocall.di.ApplicationScope
import com.mrashidcit.project4one_to_onevideocall.domain.model.CallState
import com.mrashidcit.project4one_to_onevideocall.domain.model.IceCandidateModel
import com.mrashidcit.project4one_to_onevideocall.domain.model.SignalingErrorMessages
import com.mrashidcit.project4one_to_onevideocall.domain.model.SignalingEvent
import com.mrashidcit.project4one_to_onevideocall.domain.repository.CallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place that wires [SignalingClient] together with [WebRtcClient] and implements
 * the full flow from README section 20 ("Complete Signaling + WebRTC Flow"). This is the ONLY
 * class in the app that both sends signaling messages AND drives the PeerConnection - by
 * design, neither [SignalingClient] nor [PeerConnectionManager] know about each other.
 *
 * ## Caller / callee determination (README section 19 - revised)
 * The room holds at most two peers, and exactly one of them must call createOffer(). The
 * spec's simplifying assumption - "only the peer already in the room receives peer_joined" -
 * does NOT hold for every Project #3 implementation: some servers (including the one this was
 * tested against) notify BOTH peers with `peer_joined` once the room fills up. Relying on
 * "whoever receives peer_joined is the caller" then makes both sides call createOffer()
 * simultaneously - both end up stuck in HAVE_LOCAL_OFFER, neither ever processes the other's
 * offer (each ignores it, since it already has its own PeerConnection), no answer is ever sent,
 * and the UI hangs on "Connecting..." forever.
 *
 * The fix used here is a deterministic election that both sides can compute independently,
 * regardless of which of them actually receives `peer_joined`: once BOTH peer IDs are known
 * (our own, from `joined`; the other's, from `peer_joined.peerId` or an incoming offer's
 * `from`), the peer whose ID sorts lexicographically SMALLER becomes the CALLER
 * ([startAsCaller]); the other simply waits for the incoming `offer`, making it the CALLEE
 * ([startAsCallee]). Since peer IDs are unique and both sides compare the same two values,
 * exactly one side calls createOffer() - no glare, no perfect-negotiation logic needed
 * (section 42) - no matter which peer(s) the server happens to notify.
 *
 * ## Lifecycle
 * This is a Hilt @Singleton: it (and its [SignalingClient]) survive Activity/ViewModel
 * recreation, e.g. a screen rotation, so an in-progress call is not disrupted. Call-level
 * WebRTC resources ([PeerConnectionManager], [LocalMediaManager]) are created per call and
 * released on [endCall] / peer_left / connection failure.
 */
@Singleton
class CallRepositoryImpl @Inject constructor(
    private val signalingClient: SignalingClient,
    private val webRtcClient: WebRtcClient,
    private val audioRouteManager: AudioRouteManager,
    @ApplicationScope private val scope: CoroutineScope
) : CallRepository {

    private companion object {
        const val TAG = "CallRepository"
    }

    private var peerConnectionManager: PeerConnectionManager? = null
    private var localMediaManager: LocalMediaManager? = null
    private var remotePeerId: String? = null
    private var ownPeerId: String? = null

    // Guards against starting a call twice (e.g. a duplicate/late peer_joined arriving while
    // startAsCaller()/startAsCallee() is already mid-flight, before peerConnectionManager has
    // been assigned). Reset in closePeerConnectionOnly() so a later peer can start a new call.
    private var callInitiated = false

    // ICE candidates can arrive before we've created our PeerConnection at all (not just before
    // setRemoteDescription() - see PeerConnectionManager's own queue for that later stage): the
    // callee only creates its PeerConnection once the offer arrives, so a candidate that beats
    // the offer across the wire (or, on the caller side, a stray race between sendOffer() and
    // the ICE-forwarding coroutine - see observePeerConnection()) would otherwise be silently
    // dropped. Buffered here and flushed into the PeerConnectionManager right after it's created.
    private val earlyIceCandidates = mutableListOf<IceCandidateModel>()

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    override val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    override val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    override val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(true)
    override val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(true)
    override val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    override val isSpeakerOn: StateFlow<Boolean> = audioRouteManager.isSpeakerOn

    override val eglBaseContext: EglBase.Context get() = webRtcClient.eglBaseContext

    init {
        // Collect signaling events for the lifetime of the process. Started once, here, rather
        // than per-call, so we never miss an event delivered between joinRoom() calls.
        scope.launch {
            signalingClient.events.collect { event -> handleSignalingEvent(event) }
        }
    }

    override fun joinRoom(serverUrl: String, roomId: String) {
        val current = _callState.value
        if (current !is CallState.Idle && current !is CallState.Ended && current !is CallState.Error) {
            Log.w(TAG, "joinRoom() ignored - already in state $current")
            return
        }
        _callState.value = CallState.JoiningRoom
        scope.launch {
            try {
                val media = webRtcClient.newLocalMediaManager()
                media.initLocalTracks()
                media.startCapture()
                localMediaManager = media
                _localVideoTrack.value = media.localVideoTrack
                _isMicEnabled.value = true
                _isCameraEnabled.value = true
                // Play call audio through the main loudspeaker, not the top earpiece.
                audioRouteManager.start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire camera/microphone", e)
                _callState.value = CallState.Error("Could not access the camera or microphone.")
                return@launch
            }
            signalingClient.connect(serverUrl, roomId)
        }
    }

    override fun endCall() {
        if (_callState.value is CallState.Idle) return
        _callState.value = CallState.Ending
        scope.launch {
            signalingClient.sendLeave()
            cleanupCallResources()
            signalingClient.disconnect()
            _callState.value = CallState.Ended
            Log.d(TAG, "Call ended, all resources released")
        }
    }

    override fun toggleMicrophone() {
        val enabled = !_isMicEnabled.value
        localMediaManager?.setMicEnabled(enabled)
        _isMicEnabled.value = enabled
    }

    override fun toggleCamera() {
        val enabled = !_isCameraEnabled.value
        localMediaManager?.setCameraEnabled(enabled)
        _isCameraEnabled.value = enabled
    }

    override fun toggleSpeaker() {
        audioRouteManager.setSpeakerOn(!audioRouteManager.isSpeakerOn.value)
    }

    override fun switchCamera() {
        localMediaManager?.switchCamera()
    }

    // ---------------------------------------------------------------------------------------
    // Signaling event handling (README section 20 step-by-step flow)
    // ---------------------------------------------------------------------------------------

    private suspend fun handleSignalingEvent(event: SignalingEvent) {
        when (event) {
            is SignalingEvent.Joined -> {
                ownPeerId = event.peerId
                _callState.value = CallState.WaitingForPeer
            }

            is SignalingEvent.PeerJoined -> {
                if (callInitiated) {
                    Log.w(TAG, "Ignoring peer_joined - a call is already starting/active")
                    return
                }
                val own = ownPeerId
                if (own == null) {
                    Log.w(TAG, "peer_joined arrived before joined - ignoring")
                    return
                }
                remotePeerId = event.peerId
                // Deterministic election - see the class doc comment above. Only the side with
                // the lexicographically smaller peerId calls createOffer(); the other just waits
                // for the offer that is guaranteed to arrive (SignalingEvent.OfferReceived).
                if (own < event.peerId) {
                    callInitiated = true
                    startAsCaller()
                } else {
                    Log.d(TAG, "[Signaling] I am the callee - waiting for OFFER from ${event.peerId}")
                }
            }

            is SignalingEvent.OfferReceived -> {
                if (callInitiated) {
                    Log.w(TAG, "Ignoring offer - a call is already starting/active")
                    return
                }
                callInitiated = true
                remotePeerId = event.from
                startAsCallee(event.sdp)
            }

            is SignalingEvent.AnswerReceived -> {
                val pcManager = peerConnectionManager
                if (pcManager == null) {
                    Log.w(TAG, "Received answer with no active PeerConnection - ignoring")
                    return
                }
                try {
                    pcManager.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, event.sdp))
                } catch (e: Exception) {
                    Log.e(TAG, "setRemoteDescription(answer) failed", e)
                    _callState.value = CallState.Error("Failed to finish setting up the call.")
                }
            }

            is SignalingEvent.IceCandidateReceived -> {
                val pcManager = peerConnectionManager
                if (pcManager == null) {
                    Log.d(TAG, "[WebRTC] ICE candidate received before PeerConnection existed - buffering")
                    earlyIceCandidates.add(event.candidate)
                    return
                }
                val model = event.candidate
                pcManager.addIceCandidate(IceCandidate(model.sdpMid, model.sdpMLineIndex ?: 0, model.candidate))
            }

            is SignalingEvent.PeerLeft -> {
                closePeerConnectionOnly()
                if (_callState.value !is CallState.Idle && _callState.value !is CallState.Ended) {
                    _callState.value = CallState.WaitingForPeer
                }
            }

            is SignalingEvent.Error -> {
                _callState.value = CallState.Error(
                    message = SignalingErrorMessages.forCode(event.code, event.message),
                    code = event.code
                )
            }

            SignalingEvent.ConnectionClosed -> {
                if (_callState.value !is CallState.Idle && _callState.value !is CallState.Ended) {
                    cleanupCallResources()
                    _callState.value = CallState.Error("Lost connection to the signaling server.")
                }
            }

            is SignalingEvent.ConnectionFailed -> {
                cleanupCallResources()
                _callState.value = CallState.Error("Could not reach the signaling server. ${event.reason}")
            }
        }
    }

    // Caller: our peer ID sorted smaller in the election (see class doc comment above).
    // createOffer() -> setLocalDescription() -> send OFFER.
    private suspend fun startAsCaller() {
        _callState.value = CallState.Connecting
        val pcManager = webRtcClient.newPeerConnectionManager()
        peerConnectionManager = pcManager
        observePeerConnection(pcManager)
        pcManager.createPeerConnection()
        attachLocalTracks(pcManager)
        flushEarlyIceCandidates(pcManager)
        try {
            val offer = pcManager.createOffer()
            pcManager.setLocalDescription(offer)
            signalingClient.sendOffer(offer.description)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create/send offer", e)
            _callState.value = CallState.Error("Failed to start the call.")
        }
    }

    // Callee: we received an OFFER. setRemoteDescription() -> createAnswer() -> setLocalDescription() -> send ANSWER.
    private suspend fun startAsCallee(offerSdp: String) {
        _callState.value = CallState.Connecting
        val pcManager = webRtcClient.newPeerConnectionManager()
        peerConnectionManager = pcManager
        observePeerConnection(pcManager)
        pcManager.createPeerConnection()
        attachLocalTracks(pcManager)
        flushEarlyIceCandidates(pcManager)
        try {
            pcManager.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, offerSdp))
            val answer = pcManager.createAnswer()
            pcManager.setLocalDescription(answer)
            signalingClient.sendAnswer(answer.description)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create/send answer", e)
            _callState.value = CallState.Error("Failed to accept the call.")
        }
    }

    private fun attachLocalTracks(pcManager: PeerConnectionManager) {
        val media = localMediaManager ?: return
        val audio = media.localAudioTrack
        val video = media.localVideoTrack
        if (audio != null && video != null) {
            pcManager.addLocalTracks(audio, video)
        } else {
            Log.w(TAG, "attachLocalTracks() called before local media was ready")
        }
    }

    /** Replays any ICE candidates buffered by [handleSignalingEvent] before this PeerConnection existed. */
    private fun flushEarlyIceCandidates(pcManager: PeerConnectionManager) {
        if (earlyIceCandidates.isEmpty()) return
        Log.d(TAG, "[WebRTC] Replaying ${earlyIceCandidates.size} early ICE candidate(s)")
        earlyIceCandidates.forEach { model ->
            pcManager.addIceCandidate(IceCandidate(model.sdpMid, model.sdpMLineIndex ?: 0, model.candidate))
        }
        earlyIceCandidates.clear()
    }

    /** Forwards this call's local ICE candidates to signaling and mirrors WebRTC state into our own flows. */
    private fun observePeerConnection(pcManager: PeerConnectionManager) {
        scope.launch {
            pcManager.iceCandidates.collect { candidate ->
                signalingClient.sendIceCandidate(
                    IceCandidateModel(
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex
                    )
                )
            }
        }
        scope.launch {
            pcManager.remoteVideoTrack.collect { track -> _remoteVideoTrack.value = track }
        }
        scope.launch {
            pcManager.connectionState.collect { state ->
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> _callState.value = CallState.Connected
                    PeerConnection.PeerConnectionState.FAILED ->
                        _callState.value = CallState.Error("The call connection failed (ICE failed). See README > Troubleshooting.")

                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        // Transient - WebRTC may recover on its own (brief network blip).
                        if (_callState.value is CallState.Connected) {
                            _callState.value = CallState.Connecting
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun closePeerConnectionOnly() {
        peerConnectionManager?.close()
        peerConnectionManager = null
        callInitiated = false
        earlyIceCandidates.clear()
        _remoteVideoTrack.value = null
    }

    private fun cleanupCallResources() {
        closePeerConnectionOnly()
        localMediaManager?.release()
        localMediaManager = null
        audioRouteManager.stop()
        _localVideoTrack.value = null
        remotePeerId = null
        ownPeerId = null
    }
}
