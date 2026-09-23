package com.mrashidcit.project4one_to_onevideocall.data.webrtc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes call audio to the phone's main LOUDSPEAKER (the one used for music/video playback)
 * instead of the top EARPIECE.
 *
 * WebRTC's [org.webrtc.audio.JavaAudioDeviceModule] plays audio with
 * USAGE_VOICE_COMMUNICATION, which Android sends to the earpiece by default - like a normal
 * phone call held to the ear. For a video call the phone is held in front of the face, so we
 * switch the communication route to the built-in speaker for the duration of the call and
 * restore the previous audio state afterwards.
 */
@Singleton
class AudioRouteManager @Inject constructor(
    @ApplicationContext context: Context
) {

    private companion object {
        const val TAG = "AudioRouteManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var active = false
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeakerphoneOn = false

    private val _isSpeakerOn = MutableStateFlow(true)
    /** true = main loudspeaker, false = top earpiece. */
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    /** Call when a call starts (local media acquired). Safe to call more than once. */
    fun start() {
        if (active) return
        active = true

        savedMode = audioManager.mode
        @Suppress("DEPRECATION")
        savedSpeakerphoneOn = audioManager.isSpeakerphoneOn

        // Communication mode keeps hardware AEC/NS working while we play through the speaker.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Every call starts on the loudspeaker (video-call default).
        _isSpeakerOn.value = true
        applyRoute(speakerOn = true)
    }

    /** Switches between the main loudspeaker and the top earpiece. */
    fun setSpeakerOn(on: Boolean) {
        _isSpeakerOn.value = on
        // Outside a call just remember the choice; start() resets to speaker anyway.
        if (active) applyRoute(on)
    }

    private fun applyRoute(speakerOn: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wantedType = if (speakerOn) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == wantedType }
            val ok = device != null && audioManager.setCommunicationDevice(device)
            Log.d(TAG, "[Audio] setCommunicationDevice(${if (speakerOn) "SPEAKER" else "EARPIECE"}) -> $ok")
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speakerOn
            Log.d(TAG, "[Audio] Speakerphone ${if (speakerOn) "ON" else "OFF"}")
        }
    }

    /** Call when the call ends. Restores the audio state that existed before [start]. */
    fun stop() {
        if (!active) return
        active = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = savedSpeakerphoneOn
        }
        audioManager.mode = savedMode
        Log.d(TAG, "[Audio] Audio route restored")
    }
}
