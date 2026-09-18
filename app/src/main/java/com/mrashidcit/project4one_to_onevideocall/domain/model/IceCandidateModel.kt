package com.mrashidcit.project4one_to_onevideocall.domain.model

/**
 * Transport-agnostic representation of a single ICE candidate, shaped exactly like the
 * signaling protocol's `candidate` object:
 * ```
 * "candidate": {
 *   "candidate": "candidate:1 1 UDP ...",
 *   "sdpMid": "0",
 *   "sdpMLineIndex": 0
 * }
 * ```
 * This is intentionally NOT org.webrtc.IceCandidate: the domain/signaling layers should not
 * need to know about WebRTC types, only the data/webrtc layer does the mapping
 * (see PeerConnectionManager / CallRepositoryImpl).
 */
data class IceCandidateModel(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int?
)
