package com.mrashidcit.project4one_to_onevideocall.domain.model

/**
 * High-level state machine for the whole call screen.
 *
 * This is intentionally coarse-grained: it is what Compose renders. The much more detailed
 * WebRTC-internal states (signalingState, iceConnectionState, iceGatheringState,
 * connectionState - see [PeerConnectionManager]) are logged for learning purposes but are
 * mapped down into this simpler state machine rather than shown directly to the user
 * (see README > WebRTC Connection States).
 */
sealed interface CallState {

    /** Not connected to the signaling server, not in a room. Initial / resting state. */
    data object Idle : CallState

    /** WebSocket connecting and "join" has been (or is about to be) sent. */
    data object JoiningRoom : CallState

    /** We are in the room alone, "joined" was received, waiting for "peer_joined". */
    data object WaitingForPeer : CallState

    /**
     * A PeerConnection exists and SDP/ICE exchange is underway (offer/answer sent or
     * received, ICE candidates trickling) but WebRTC has not yet reached the CONNECTED
     * PeerConnectionState.
     */
    data object Connecting : CallState

    /** PeerConnection.PeerConnectionState.CONNECTED - audio/video is flowing peer-to-peer. */
    data object Connected : CallState

    /** User tapped "End Call"; cleanup of PeerConnection/media/signaling is in progress. */
    data object Ending : CallState

    /** Cleanup finished. The call is over; the UI returns to the join screen. */
    data object Ended : CallState

    /** Something went wrong (signaling error, ICE failure, permission denial, etc). */
    data class Error(val message: String, val code: String? = null) : CallState
}
