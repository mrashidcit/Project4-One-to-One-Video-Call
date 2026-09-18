package com.mrashidcit.project4one_to_onevideocall.domain.model

/**
 * Parsed, strongly-typed representation of every message the [SignalingClient] can emit.
 * These are what the rest of the app (repository, view model) reacts to - the raw JSON /
 * kotlinx.serialization DTOs matching the wire protocol live in
 * `data/signaling/SignalingMessageMapper.kt` and never leak past [WebSocketSignalingClient].
 *
 * Naming mirrors the protocol 1:1 so the mapping between "what the server sent" and
 * "what the client does" is obvious when learning the flow:
 *  joined         -> Joined
 *  peer_joined    -> PeerJoined
 *  offer          -> OfferReceived
 *  answer         -> AnswerReceived
 *  ice_candidate  -> IceCandidateReceived
 *  peer_left      -> PeerLeft
 *  error          -> Error
 */
sealed interface SignalingEvent {

    /** Server confirmed our join and handed us our server-generated peerId. */
    data class Joined(val roomId: String, val peerId: String) : SignalingEvent

    /** Another peer joined our room. We are (by definition, see README > Caller/Callee) the caller. */
    data class PeerJoined(val peerId: String) : SignalingEvent

    /** We received an SDP offer from [from]. We are the callee. */
    data class OfferReceived(val sdp: String, val from: String) : SignalingEvent

    /** We received an SDP answer to our own offer, from [from]. */
    data class AnswerReceived(val sdp: String, val from: String) : SignalingEvent

    /** A trickled ICE candidate from [from]. There can be many of these. */
    data class IceCandidateReceived(val candidate: IceCandidateModel, val from: String) : SignalingEvent

    /** The other peer disconnected or left the room. */
    data class PeerLeft(val peerId: String) : SignalingEvent

    /** A protocol-level error reported by the server. [code] is what we branch on. */
    data class Error(val code: String, val message: String) : SignalingEvent

    /** The WebSocket closed normally (server or client initiated). */
    data object ConnectionClosed : SignalingEvent

    /** The WebSocket failed to connect, or dropped unexpectedly (network loss, server down). */
    data class ConnectionFailed(val reason: String) : SignalingEvent
}

/**
 * Maps the server's `error.code` values (see README > Existing Server Error Codes) to a short,
 * user-facing sentence. We branch on `code`, never on the free-text `message`, because the
 * server's message wording is not part of the stable contract.
 */
object SignalingErrorMessages {
    fun forCode(code: String, fallbackMessage: String): String = when (code) {
        "ROOM_FULL" -> "This room is already full."
        "INVALID_ROOM_ID" -> "Invalid room ID."
        "ALREADY_IN_ROOM" -> "You are already in a room."
        "NOT_IN_ROOM" -> "You are not currently in a room."
        "ROOM_NOT_FOUND" -> "That room does not exist."
        "INVALID_SDP" -> "A signaling error occurred (invalid session description)."
        "INVALID_ICE_CANDIDATE" -> "A signaling error occurred (invalid ICE candidate)."
        "PEER_NOT_FOUND" -> "The other participant could not be found."
        "MESSAGE_TOO_LARGE" -> "A signaling message was too large to send."
        "INVALID_JSON", "INVALID_MESSAGE" -> "A signaling protocol error occurred."
        "INTERNAL_ERROR" -> "The signaling server hit an internal error."
        else -> fallbackMessage.ifBlank { "Unknown signaling error ($code)." }
    }
}
