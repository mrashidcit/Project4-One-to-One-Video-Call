package com.mrashidcit.project4one_to_onevideocall.presentation.call

import com.mrashidcit.project4one_to_onevideocall.domain.model.CallState
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * Everything [com.mrashidcit.project4one_to_onevideocall.presentation.call.CallScreen] needs to
 * render, in one immutable snapshot. Produced by [CallViewModel] from
 * [com.mrashidcit.project4one_to_onevideocall.domain.repository.CallRepository]'s flows.
 */
data class CallUiState(
    val serverUrlInput: String = "",
    val roomIdInput: String = "",
    val callState: CallState = CallState.Idle,
    val statusText: String = "Disconnected",
    val errorMessage: String? = null,
    val isMicEnabled: Boolean = true,
    val isCameraEnabled: Boolean = true,
    val localVideoTrack: VideoTrack? = null,
    val remoteVideoTrack: VideoTrack? = null,
    val eglBaseContext: EglBase.Context? = null
) {
    /** True while a form to join a room should be shown instead of the in-call UI. */
    val isPreJoinScreen: Boolean
        get() = callState is CallState.Idle || callState is CallState.Ended || callState is CallState.Error
}

/** Maps the coarse [CallState] to the "Status: ..." text shown at the bottom of the screen. */
fun CallState.toStatusText(): String = when (this) {
    CallState.Idle -> "Disconnected"
    CallState.JoiningRoom -> "Joining room..."
    CallState.WaitingForPeer -> "Waiting for peer..."
    CallState.Connecting -> "Connecting..."
    CallState.Connected -> "Connected"
    CallState.Ending -> "Ending call..."
    CallState.Ended -> "Call ended"
    is CallState.Error -> "Error: $message"
}
