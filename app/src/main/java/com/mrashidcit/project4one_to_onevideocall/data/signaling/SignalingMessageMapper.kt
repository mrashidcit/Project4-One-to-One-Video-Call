package com.mrashidcit.project4one_to_onevideocall.data.signaling

import android.util.Log
import com.mrashidcit.project4one_to_onevideocall.domain.model.IceCandidateModel
import com.mrashidcit.project4one_to_onevideocall.domain.model.SignalingEvent
import com.mrashidcit.project4one_to_onevideocall.domain.model.SignalingMessageType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire format for the Project #3 protocol, and nothing else. Field names below are a
 * DELIBERATE, EXACT match to the server (type, roomId, peerId, sdp, candidate, sdpMid,
 * sdpMLineIndex, from, code, message) - see README section 9. Do not rename them.
 */

// ---- Client -> Server -------------------------------------------------------------------

@Serializable
private data class JoinMessage(val type: String = SignalingMessageType.JOIN, val roomId: String)

@Serializable
private data class OfferMessage(val type: String = SignalingMessageType.OFFER, val sdp: String)

@Serializable
private data class AnswerMessage(val type: String = SignalingMessageType.ANSWER, val sdp: String)

@Serializable
data class IceCandidatePayloadDto(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null
)

@Serializable
private data class IceCandidateMessage(
    val type: String = SignalingMessageType.ICE_CANDIDATE,
    val candidate: IceCandidatePayloadDto
)

@Serializable
private data class LeaveMessage(val type: String = SignalingMessageType.LEAVE)

// ---- Server -> Client --------------------------------------------------------------------

@Serializable
private data class TypeOnlyMessage(val type: String)

@Serializable
private data class JoinedMessage(val type: String, val roomId: String, val peerId: String)

@Serializable
private data class PeerJoinedMessage(val type: String, val peerId: String)

@Serializable
private data class OfferReceivedDto(val type: String, val sdp: String, val from: String)

@Serializable
private data class AnswerReceivedDto(val type: String, val sdp: String, val from: String)

@Serializable
private data class IceCandidateReceivedDto(
    val type: String,
    val candidate: IceCandidatePayloadDto,
    val from: String
)

@Serializable
private data class PeerLeftMessage(val type: String, val peerId: String)

@Serializable
private data class ErrorMessageDto(val type: String, val code: String, val message: String = "")

/**
 * Pure functions turning [SignalingEvent]/domain values into wire JSON and back. Kept
 * dependency-free (no WebSocket, no coroutines) so it's trivially testable on its own.
 */
object SignalingMessageMapper {

    private const val TAG = "SignalingMapper"
    // encodeDefaults = true is REQUIRED here: every outgoing DTO (JoinMessage, OfferMessage,
    // ...) declares its "type" field with a default value (e.g. `val type: String = "join"`)
    // so callers don't have to repeat it. kotlinx.serialization's default Json config omits
    // any property that still equals its declared default, which would silently drop "type"
    // from every outgoing message (the server then rejects it as "Unknown or missing message
    // type: undefined"). encodeDefaults = true forces it to always be written.
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    // ---- outgoing ----

    fun buildJoin(roomId: String): String =
        json.encodeToString(JoinMessage.serializer(), JoinMessage(roomId = roomId))

    fun buildOffer(sdp: String): String =
        json.encodeToString(OfferMessage.serializer(), OfferMessage(sdp = sdp))

    fun buildAnswer(sdp: String): String =
        json.encodeToString(AnswerMessage.serializer(), AnswerMessage(sdp = sdp))

    fun buildIceCandidate(candidate: IceCandidateModel): String = json.encodeToString(
        IceCandidateMessage.serializer(),
        IceCandidateMessage(
            candidate = IceCandidatePayloadDto(
                candidate = candidate.candidate,
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex
            )
        )
    )

    fun buildLeave(): String = json.encodeToString(LeaveMessage.serializer(), LeaveMessage())

    // ---- incoming ----

    /**
     * Parses one raw WebSocket text frame into a [SignalingEvent], or null if it's not a
     * message type we understand (never crashes the app on an unexpected payload).
     */
    fun parseIncoming(text: String): SignalingEvent? = try {
        when (val type = json.decodeFromString(TypeOnlyMessage.serializer(), text).type) {
            SignalingMessageType.JOINED -> {
                val msg = json.decodeFromString(JoinedMessage.serializer(), text)
                SignalingEvent.Joined(roomId = msg.roomId, peerId = msg.peerId)
            }

            SignalingMessageType.PEER_JOINED -> {
                val msg = json.decodeFromString(PeerJoinedMessage.serializer(), text)
                SignalingEvent.PeerJoined(peerId = msg.peerId)
            }

            SignalingMessageType.OFFER -> {
                val msg = json.decodeFromString(OfferReceivedDto.serializer(), text)
                SignalingEvent.OfferReceived(sdp = msg.sdp, from = msg.from)
            }

            SignalingMessageType.ANSWER -> {
                val msg = json.decodeFromString(AnswerReceivedDto.serializer(), text)
                SignalingEvent.AnswerReceived(sdp = msg.sdp, from = msg.from)
            }

            SignalingMessageType.ICE_CANDIDATE -> {
                val msg = json.decodeFromString(IceCandidateReceivedDto.serializer(), text)
                SignalingEvent.IceCandidateReceived(
                    candidate = IceCandidateModel(
                        candidate = msg.candidate.candidate,
                        sdpMid = msg.candidate.sdpMid,
                        sdpMLineIndex = msg.candidate.sdpMLineIndex
                    ),
                    from = msg.from
                )
            }

            SignalingMessageType.PEER_LEFT -> {
                val msg = json.decodeFromString(PeerLeftMessage.serializer(), text)
                SignalingEvent.PeerLeft(peerId = msg.peerId)
            }

            SignalingMessageType.ERROR -> {
                val msg = json.decodeFromString(ErrorMessageDto.serializer(), text)
                SignalingEvent.Error(code = msg.code, message = msg.message)
            }

            else -> {
                Log.w(TAG, "[Signaling] Unknown message type: $type")
                null
            }
        }
    } catch (e: Exception) {
        // Deliberately never let a malformed frame crash the app - just drop it and log.
        Log.e(TAG, "[Signaling] Failed to parse incoming message", e)
        null
    }
}
