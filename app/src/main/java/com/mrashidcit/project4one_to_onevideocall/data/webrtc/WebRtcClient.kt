package com.mrashidcit.project4one_to_onevideocall.data.webrtc

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the APPLICATION-LEVEL WebRTC resources: [EglBase] and [PeerConnectionFactory]. These
 * are expensive to create and are meant to be created exactly ONCE for the lifetime of the
 * process - see README section 10 and section 32 ("do not recreate expensive global WebRTC
 * resources unnecessarily").
 *
 * Per-call resources ([PeerConnectionManager], [LocalMediaManager]) are created fresh for
 * every call via [newPeerConnectionManager] / [newLocalMediaManager] and must be torn down by
 * the caller ([com.mrashidcit.project4one_to_onevideocall.data.repository.CallRepositoryImpl])
 * when the call ends.
 *
 * Hilt makes this a @Singleton, so PeerConnectionFactory.initialize() runs exactly once,
 * lazily, the first time a call is started.
 */
@Singleton
class WebRtcClient @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private companion object {
        const val TAG = "WebRtcClient"
    }

    /** Shared EGL context - every SurfaceViewRenderer AND the video encoder/decoder use this same one. */
    private val eglBase: EglBase = EglBase.create()
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    // Lazily built on first access so PeerConnectionFactory.initialize() only runs when a call
    // is actually about to start, not at app launch.
    private val peerConnectionFactory: PeerConnectionFactory by lazy { buildFactory() }

    private fun buildFactory(): PeerConnectionFactory {
        // 1) PeerConnectionFactory.InitializationOptions - global, one-time native init.
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)
        Log.d(TAG, "[WebRTC] PeerConnectionFactory.initialize() done")

        // 2) Hardware-accelerated encoder/decoder factories, sharing our EglBase context so
        //    decoded/encoded frames can be uploaded straight to/from GPU textures.
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            /* enableIntelVp8Encoder = */ true,
            /* enableH264HighProfile = */ true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        // 3) Audio device module - talks to the Android audio HAL (mic in, speaker/earpiece
        //    out). We let WebRTC use hardware AEC/NS when the device supports it.
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        // 4) PeerConnectionFactory.Options - left at defaults (no network-type filtering needed
        //    for this project).
        return PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    /** A fresh, call-scoped PeerConnectionManager wired with the default ICE server list. */
    fun newPeerConnectionManager(): PeerConnectionManager =
        PeerConnectionManager(peerConnectionFactory, IceServersProvider.default())

    /** A fresh, call-scoped LocalMediaManager (camera + mic pipeline). */
    fun newLocalMediaManager(): LocalMediaManager =
        LocalMediaManager(context, peerConnectionFactory, eglBase.eglBaseContext)
}
