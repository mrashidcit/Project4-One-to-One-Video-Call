package com.mrashidcit.project4one_to_onevideocall.presentation.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrashidcit.project4one_to_onevideocall.BuildConfig
import com.mrashidcit.project4one_to_onevideocall.domain.model.CallState
import com.mrashidcit.project4one_to_onevideocall.domain.repository.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Coordinates [CallScreen] <-> [CallRepository]. Contains NO WebRTC/WebSocket code itself -
 * see README section 25 ("the ViewModel coordinates; WebRTC classes perform WebRTC
 * operations").
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val callRepository: CallRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CallUiState(
            serverUrlInput = BuildConfig.DEFAULT_SIGNALING_SERVER_URL,
            eglBaseContext = callRepository.eglBaseContext
        )
    )
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    init {
        combine(
            callRepository.callState,
            callRepository.localVideoTrack,
            callRepository.remoteVideoTrack,
            callRepository.isMicEnabled,
            callRepository.isCameraEnabled
        ) { callState, local, remote, mic, camera ->
            _uiState.update {
                it.copy(
                    callState = callState,
                    statusText = callState.toStatusText(),
                    errorMessage = (callState as? CallState.Error)?.message,
                    localVideoTrack = local,
                    remoteVideoTrack = remote,
                    isMicEnabled = mic,
                    isCameraEnabled = camera
                )
            }
        }.onEach { }.launchIn(viewModelScope)
    }

    fun onEvent(event: CallUiEvent) {
        when (event) {
            is CallUiEvent.ServerUrlChanged -> _uiState.update { it.copy(serverUrlInput = event.value) }
            is CallUiEvent.RoomIdChanged -> _uiState.update { it.copy(roomIdInput = event.value) }

            CallUiEvent.JoinRoomClicked -> {
                val state = _uiState.value
                val roomId = state.roomIdInput.trim()
                val serverUrl = state.serverUrlInput.trim()
                if (roomId.isBlank()) {
                    _uiState.update { it.copy(errorMessage = "Please enter a room ID.") }
                    return
                }
                if (serverUrl.isBlank()) {
                    _uiState.update { it.copy(errorMessage = "Please enter the signaling server URL.") }
                    return
                }
                _uiState.update { it.copy(errorMessage = null) }
                callRepository.joinRoom(serverUrl, roomId)
            }

            CallUiEvent.ToggleMicrophone -> callRepository.toggleMicrophone()
            CallUiEvent.ToggleCamera -> callRepository.toggleCamera()
            CallUiEvent.SwitchCamera -> callRepository.switchCamera()
            CallUiEvent.EndCallClicked -> callRepository.endCall()
            CallUiEvent.ErrorDismissed -> _uiState.update { it.copy(errorMessage = null) }

            CallUiEvent.PermissionsDenied -> _uiState.update {
                it.copy(errorMessage = "Camera and microphone permissions are required to start a call.")
            }
        }
    }
}
