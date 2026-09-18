package com.mrashidcit.project4one_to_onevideocall.presentation.call

/** User intents from [CallScreen], handled by [CallViewModel.onEvent]. */
sealed interface CallUiEvent {
    data class ServerUrlChanged(val value: String) : CallUiEvent
    data class RoomIdChanged(val value: String) : CallUiEvent
    data object JoinRoomClicked : CallUiEvent
    data object ToggleMicrophone : CallUiEvent
    data object ToggleCamera : CallUiEvent
    data object SwitchCamera : CallUiEvent
    data object EndCallClicked : CallUiEvent
    data object ErrorDismissed : CallUiEvent
    /** Camera/microphone permission was denied - shown as a friendly error instead of crashing. */
    data object PermissionsDenied : CallUiEvent
}
