package com.mrashidcit.project4one_to_onevideocall.domain.repository

import com.mrashidcit.project4one_to_onevideocall.domain.model.CallState
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * Coordinates [com.mrashidcit.project4one_to_onevideocall.data.signaling.SignalingClient] and
 * [com.mrashidcit.project4one_to_onevideocall.data.webrtc.WebRtcClient] to implement the full
 * call flow described in the README. This is the ONLY thing [CallViewModel] talks to - it
 * never touches OkHttp or org.webrtc.PeerConnection directly (see section 26 / 27 of the spec:
 * "keep signaling and WebRTC responsibilities separate").
 *
 * Note: exposing org.webrtc.VideoTrack / EglBase.Context here is a pragmatic compromise for
 * this learning project rather than pure Clean Architecture (which would define its own
 * video-frame abstraction). Compose's SurfaceViewRenderer needs those concrete WebRTC types
 * to attach a sink, and introducing a parallel abstraction over them would add indirection
 * without teaching anything new. See README > "Do Not Overengineer".
 */
interface CallRepository {

    /** Coarse call state driving the UI (see [CallState]). */
    val callState: StateFlow<CallState>

    /** Our own local camera preview track, non-null once media has been acquired. */
    val localVideoTrack: StateFlow<VideoTrack?>

    /** The other peer's video track, non-null once their track has arrived over WebRTC. */
    val remoteVideoTrack: StateFlow<VideoTrack?>

    /** Whether the local microphone track is currently enabled (unmuted). */
    val isMicEnabled: StateFlow<Boolean>

    /** Whether the local camera track is currently enabled. */
    val isCameraEnabled: StateFlow<Boolean>

    /** true = audio plays from the main loudspeaker, false = from the top earpiece. */
    val isSpeakerOn: StateFlow<Boolean>

    /** Shared EGL context every SurfaceViewRenderer must be initialized with. */
    val eglBaseContext: EglBase.Context

    /** Connect to [serverUrl] and join [roomId]. See CallRepositoryImpl for the full flow. */
    fun joinRoom(serverUrl: String, roomId: String)

    /** Sends "leave", tears down the PeerConnection and local media, and disconnects. */
    fun endCall()

    /** Mute/unmute the local microphone by toggling AudioTrack.setEnabled(). */
    fun toggleMicrophone()

    /** Enable/disable the local camera (stops/starts the capturer too - see LocalMediaManager). */
    fun toggleCamera()

    /** Switch call audio between the main loudspeaker and the top earpiece. */
    fun toggleSpeaker()

    /** Switch between front and back camera without recreating the PeerConnection. */
    fun switchCamera()
}
