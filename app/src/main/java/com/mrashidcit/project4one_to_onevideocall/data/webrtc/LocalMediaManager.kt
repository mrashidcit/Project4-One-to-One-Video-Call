package com.mrashidcit.project4one_to_onevideocall.data.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Owns the local capture pipeline described in README section 11:
 *
 * ```
 * Camera -> CameraVideoCapturer -> VideoSource -> VideoTrack -> PeerConnection
 * Microphone -> AudioSource -> AudioTrack -> PeerConnection
 * ```
 *
 * One instance is created per call (via [WebRtcClient.newLocalMediaManager]) and released with
 * [release] when the call ends - these are call-level resources, distinct from the
 * app-level PeerConnectionFactory/EglBase that created them (README section 32).
 */
class LocalMediaManager(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    private val eglBaseContext: org.webrtc.EglBase.Context
) {

    private companion object {
        const val TAG = "LocalMediaManager"
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val VIDEO_FPS = 30
        const val VIDEO_TRACK_ID = "ARDAMSv0"
        const val AUDIO_TRACK_ID = "ARDAMSa0"
    }

    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    var localVideoTrack: VideoTrack? = null
        private set
    var localAudioTrack: AudioTrack? = null
        private set

    var isFrontCamera = true
        private set

    /** Creates the capturer + sources + tracks. Call [startCapture] afterwards to see frames. */
    fun initLocalTracks() {
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: error("No camera available on this device")
        isFrontCamera = enumerator.isFrontFacing(deviceName)

        val capturer = enumerator.createCapturer(deviceName, object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(errorDescription: String?) {
                Log.e(TAG, "[WebRTC] Camera error: $errorDescription")
            }

            override fun onCameraDisconnected() {
                Log.w(TAG, "[WebRTC] Camera disconnected")
            }

            override fun onCameraFreezed(errorDescription: String?) {
                Log.w(TAG, "[WebRTC] Camera freezed: $errorDescription")
            }

            override fun onCameraOpening(cameraName: String?) = Unit
            override fun onFirstFrameAvailable() {
                Log.d(TAG, "[WebRTC] First local camera frame captured")
            }

            override fun onCameraClosed() = Unit
        }) ?: error("Failed to create camera capturer for $deviceName")
        videoCapturer = capturer

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
        surfaceTextureHelper = helper

        val source = factory.createVideoSource(capturer.isScreencast)
        videoSource = source
        capturer.initialize(helper, context, source.capturerObserver)

        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, source).apply { setEnabled(true) }

        val aSource = factory.createAudioSource(MediaConstraints())
        audioSource = aSource
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, aSource).apply { setEnabled(true) }

        Log.d(TAG, "[WebRTC] Local audio/video tracks created (camera=$deviceName)")
    }

    /** Starts pushing camera frames into the VideoSource. Call once, after [initLocalTracks]. */
    fun startCapture() {
        videoCapturer?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        Log.d(TAG, "[WebRTC] Camera capture started (${VIDEO_WIDTH}x$VIDEO_HEIGHT@$VIDEO_FPS)")
    }

    /**
     * Mute/unmute. We simply disable the AudioTrack rather than removing it from the
     * PeerConnection or stopping capture - see README section 27 for why this is preferred:
     * it's instant, doesn't renegotiate SDP, and the other peer just hears silence.
     */
    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        Log.d(TAG, "[WebRTC] Microphone ${if (enabled) "enabled" else "muted"}")
    }

    /**
     * Camera on/off. Unlike mic mute, we ALSO stop/start the capturer (not just disable the
     * track) so the camera hardware is actually released when off - see README section 28 for
     * the three options (track disabled vs capturer stopped vs sender removed) and why this
     * project uses the first two together rather than the third (which would require
     * renegotiation and is out of scope - section 42).
     */
    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        try {
            if (enabled) {
                videoCapturer?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
            } else {
                videoCapturer?.stopCapture()
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "[WebRTC] Failed to toggle camera capture", e)
        }
        Log.d(TAG, "[WebRTC] Camera ${if (enabled) "enabled" else "disabled"}")
    }

    /** Flips front/back camera using the capturer's own switch API - PeerConnection stays alive. */
    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCameraNow: Boolean) {
                isFrontCamera = isFrontCameraNow
                Log.d(TAG, "[WebRTC] Camera switched, front=$isFrontCameraNow")
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.e(TAG, "[WebRTC] Camera switch failed: $errorDescription")
            }
        })
    }

    /** Releases every capture-pipeline resource. Must be called exactly once per call. */
    fun release() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: InterruptedException) {
            Log.e(TAG, "[WebRTC] stopCapture() failed during release", e)
        }
        videoCapturer?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        surfaceTextureHelper?.dispose()

        videoCapturer = null
        localVideoTrack = null
        localAudioTrack = null
        videoSource = null
        audioSource = null
        surfaceTextureHelper = null
        Log.d(TAG, "[WebRTC] Local media resources released")
    }
}
