package com.mrashidcit.project4one_to_onevideocall.domain.model

/**
 * The exact "type" string values used by the Project #3 signaling protocol. Kept as plain
 * String constants (not a Kotlin enum) because they are serialized verbatim into/out of JSON
 * and must match the Node.js server byte-for-byte.
 */
object SignalingMessageType {
    // Client -> Server
    const val JOIN = "join"
    const val OFFER = "offer"
    const val ANSWER = "answer"
    const val ICE_CANDIDATE = "ice_candidate"
    const val LEAVE = "leave"

    // Server -> Client
    const val JOINED = "joined"
    const val PEER_JOINED = "peer_joined"
    const val PEER_LEFT = "peer_left"
    const val ERROR = "error"
}
