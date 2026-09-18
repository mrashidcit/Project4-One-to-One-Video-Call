package com.mrashidcit.project4one_to_onevideocall.data.webrtc

import org.webrtc.PeerConnection

/**
 * STUN/TURN configuration lives ONLY on the Android client - Project #3's signaling server
 * has no idea these servers exist and never sees a byte of STUN traffic (see README section 14
 * / 36: "Signaling Server != STUN Server != TURN Server").
 *
 * For this project we configure a single public STUN server for learning/testing. No TURN is
 * configured on purpose (README section 36 / 44): STUN alone can fail when both peers are
 * behind restrictive/symmetric NATs, most commonly when one device is on cellular data. That's
 * expected and is exactly the motivation for a future TURN project.
 */
object IceServersProvider {

    /** Change/extend this list to point at your own STUN/TURN servers later. */
    fun default(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )
}
