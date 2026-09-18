package com.mrashidcit.project4one_to_onevideocall.data.webrtc

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns exactly ONE org.webrtc.PeerConnection for exactly one call. Everything the spec's
 * "WebRTC learning goal" walks through (steps 6-19) happens in this class: creating the
 * PeerConnection, creating the offer/answer, setting local/remote descriptions, and
 * trickling/receiving ICE candidates.
 *
 * This class knows NOTHING about the WebSocket - it only exposes Flows/suspend functions.
 * [com.mrashidcit.project4one_to_onevideocall.data.repository.CallRepositoryImpl] is what wires
 * this together with [com.mrashidcit.project4one_to_onevideocall.data.signaling.SignalingClient].
 *
 * Lifecycle: a NEW instance is created per call (via [WebRtcClient.newPeerConnectionManager])
 * and must be [close]d when the call ends or the peer leaves - it is a call-level resource,
 * unlike the PeerConnectionFactory it was built from (see README section 32).
 */
class PeerConnectionManager(
    private val factory: PeerConnectionFactory,
    private val iceServers: List<PeerConnection.IceServer>
) {

    private companion object {
        const val TAG = "PeerConnectionManager"
        const val LOCAL_STREAM_ID = "ARDAMS"
    }

    private var peerConnection: PeerConnection? = null

    // Trickle ICE (README section 15/41): candidates that arrive from the remote peer before
    // our own setRemoteDescription() has completed cannot be added yet - WebRTC needs the
    // remote description first so it knows which media section (m-line) each candidate
    // belongs to. We queue them and flush once the remote description is set.
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false

    private val _iceCandidates = MutableSharedFlow<IceCandidate>(extraBufferCapacity = 32)
    /** Every LOCAL ICE candidate as soon as onIceCandidate() fires - forward these immediately. */
    val iceCandidates: SharedFlow<IceCandidate> = _iceCandidates.asSharedFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _remoteAudioTrack = MutableStateFlow<AudioTrack?>(null)
    val remoteAudioTrack: StateFlow<AudioTrack?> = _remoteAudioTrack.asStateFlow()

    private val _connectionState = MutableStateFlow<PeerConnection.PeerConnectionState?>(null)
    /** Aggregate connection state - see README > "WebRTC Connection States" for what each value means. */
    val connectionState: StateFlow<PeerConnection.PeerConnectionState?> = _connectionState.asStateFlow()

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
            Log.d(TAG, "[WebRTC] signalingState = $newState")
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "[WebRTC] ICE connection state = $newState")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d(TAG, "[WebRTC] iceConnectionReceiving = $receiving")
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "[WebRTC] iceGatheringState = $newState")
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            // Trickle ICE: forward every candidate the instant it's generated, do NOT wait
            // for gathering to finish (README section 15).
            Log.d(TAG, "[WebRTC] ICE candidate generated")
            _iceCandidates.tryEmit(candidate)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
            Log.d(TAG, "[WebRTC] ICE candidates removed: ${candidates?.size ?: 0}")
        }

        override fun onAddStream(stream: MediaStream?) {
            // Legacy Plan-B callback. We use Unified Plan (see createPeerConnection()) and
            // rely on onAddTrack below instead, but WebRTC still calls this for the underlying
            // MediaStream - no action needed here.
        }

        override fun onRemoveStream(stream: MediaStream?) = Unit

        override fun onDataChannel(channel: DataChannel?) {
            // Not used in this project - no data channels are created.
        }

        override fun onRenegotiationNeeded() {
            // A second renegotiation cycle (adding/removing tracks mid-call, etc.) is out of
            // scope for this project - see README section 42 ("No Perfect Negotiation").
            Log.d(TAG, "[WebRTC] onRenegotiationNeeded (ignored in this project)")
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            when (val track = receiver?.track()) {
                is VideoTrack -> {
                    Log.d(TAG, "[WebRTC] Remote video track received")
                    _remoteVideoTrack.value = track
                }

                is AudioTrack -> {
                    Log.d(TAG, "[WebRTC] Remote audio track received")
                    // No manual playback wiring needed - see README > "Remote Audio".
                    _remoteAudioTrack.value = track
                }

                else -> Unit
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            Log.d(TAG, "[WebRTC] Peer connection state = $newState")
            _connectionState.value = newState
        }

        // The four callbacks below were added to org.webrtc.PeerConnection.Observer in newer
        // WebRTC releases (this project's library version requires all of them to be
        // implemented). onTrack()/onRemoveTrack() are the Unified Plan-native counterparts to
        // onAddTrack() above; we still use onAddTrack() as our source of truth for remote
        // tracks since it directly hands us the MediaStreamTrack, and just log the rest.
        override fun onTrack(transceiver: RtpTransceiver?) {
            Log.d(TAG, "[WebRTC] onTrack: mid=${transceiver?.mid}")
        }

        override fun onRemoveTrack(receiver: RtpReceiver?) {
            Log.d(TAG, "[WebRTC] onRemoveTrack")
        }

        override fun onIceCandidateError(event: IceCandidateErrorEvent?) {
            Log.w(TAG, "[WebRTC] ICE candidate error: ${event?.errorText}")
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
            Log.d(TAG, "[WebRTC] Selected ICE candidate pair changed")
        }
    }

    /** Creates the PeerConnection. Must be called before addLocalTracks()/createOffer()/etc. */
    fun createPeerConnection() {
        check(peerConnection == null) { "createPeerConnection() called twice" }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // tcpCandidatePolicy/bundlePolicy/etc left at their library defaults on purpose -
            // the defaults are fine for a single audio+video m-line call like this one.
        }
        peerConnection = factory.createPeerConnection(rtcConfig, observer)
        Log.d(TAG, "[WebRTC] PeerConnection created")
    }

    /** Adds our local audio+video tracks so they are offered/answered in the SDP. */
    fun addLocalTracks(audioTrack: AudioTrack, videoTrack: VideoTrack) {
        val pc = requireNotNull(peerConnection) { "createPeerConnection() must be called first" }
        pc.addTrack(videoTrack, listOf(LOCAL_STREAM_ID))
        pc.addTrack(audioTrack, listOf(LOCAL_STREAM_ID))
        Log.d(TAG, "[WebRTC] Local audio/video tracks added to PeerConnection")
    }

    /** Caller side: createOffer() -> SessionDescription (does not set it, see [setLocalDescription]). */
    suspend fun createOffer(): SessionDescription {
        val pc = requireNotNull(peerConnection)
        return suspendCancellableCoroutine { cont ->
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                override fun onCreateFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("createOffer failed: $error"))

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            }, MediaConstraints())
        }
    }

    /** Callee side: createAnswer() -> SessionDescription, called after setRemoteDescription(offer). */
    suspend fun createAnswer(): SessionDescription {
        val pc = requireNotNull(peerConnection)
        return suspendCancellableCoroutine { cont ->
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                override fun onCreateFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("createAnswer failed: $error"))

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            }, MediaConstraints())
        }
    }

    /** Wraps the callback-based setLocalDescription() in a suspend function - see README section 40. */
    suspend fun setLocalDescription(sdp: SessionDescription) {
        val pc = requireNotNull(peerConnection)
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) = Unit
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onCreateFailure(error: String?) = Unit
                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("setLocalDescription failed: $error"))
            }, sdp)
        }
        Log.d(TAG, "[WebRTC] Local description set")
    }

    /**
     * Wraps setRemoteDescription() as a suspend function and, once it completes, flushes any
     * ICE candidates that arrived (and were queued) before this point - see README section 41.
     */
    suspend fun setRemoteDescription(sdp: SessionDescription) {
        val pc = requireNotNull(peerConnection)
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) = Unit
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onCreateFailure(error: String?) = Unit
                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("setRemoteDescription failed: $error"))
            }, sdp)
        }
        Log.d(TAG, "[WebRTC] Remote description set")
        isRemoteDescriptionSet = true
        flushPendingIceCandidates()
    }

    /**
     * Adds a REMOTE ICE candidate received over signaling. If the remote description hasn't
     * been set yet, queues it instead of calling addIceCandidate() (which would otherwise be
     * silently ineffective/undefined before WebRTC knows about the corresponding m-line).
     */
    fun addIceCandidate(candidate: IceCandidate) {
        if (isRemoteDescriptionSet) {
            peerConnection?.addIceCandidate(candidate)
            Log.d(TAG, "[WebRTC] ICE candidate received and added")
        } else {
            Log.d(TAG, "[WebRTC] ICE candidate received before remote description - queuing")
            pendingRemoteIceCandidates.add(candidate)
        }
    }

    private fun flushPendingIceCandidates() {
        if (pendingRemoteIceCandidates.isEmpty()) return
        Log.d(TAG, "[WebRTC] Flushing ${pendingRemoteIceCandidates.size} queued ICE candidate(s)")
        pendingRemoteIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
        pendingRemoteIceCandidates.clear()
    }

    /** Tears down the PeerConnection. Safe to call more than once. */
    fun close() {
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        pendingRemoteIceCandidates.clear()
        isRemoteDescriptionSet = false
        _remoteVideoTrack.value = null
        _remoteAudioTrack.value = null
        _connectionState.value = null
        Log.d(TAG, "[WebRTC] PeerConnection closed and disposed")
    }
}
