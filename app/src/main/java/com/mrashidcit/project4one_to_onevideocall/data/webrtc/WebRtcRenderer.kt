package com.mrashidcit.project4one_to_onevideocall.data.webrtc

import android.content.Context
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Small helper around org.webrtc.SurfaceViewRenderer's init/release lifecycle so the Compose
 * side (CallScreen.kt) doesn't have to repeat this boilerplate for both the local preview and
 * the remote video view. See README section 12 for the Camera -> VideoTrack -> Renderer chain.
 *
 * A SurfaceViewRenderer is a "call-level" resource: create one per AndroidView instance, always
 * pair init() with release(), and never call init()/release() more than once each.
 */
object WebRtcRenderer {

    fun create(
        context: Context,
        eglBaseContext: EglBase.Context,
        mirror: Boolean = false
    ): SurfaceViewRenderer = SurfaceViewRenderer(context).apply {
        init(eglBaseContext, null)
        setEnableHardwareScaler(true)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        setMirror(mirror)
        setZOrderMediaOverlay(false)
    }

    fun release(renderer: SurfaceViewRenderer) {
        renderer.release()
    }
}
