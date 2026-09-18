package com.mrashidcit.project4one_to_onevideocall.data.signaling

import android.util.Log
import com.mrashidcit.project4one_to_onevideocall.di.ApplicationScope
import com.mrashidcit.project4one_to_onevideocall.domain.model.IceCandidateModel
import com.mrashidcit.project4one_to_onevideocall.domain.model.SignalingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp-based implementation of [SignalingClient]. This class understands ONLY the Project #3
 * WebSocket protocol (via [SignalingMessageMapper]) - it never touches org.webrtc.
 *
 * Lifecycle: this is a Hilt @Singleton, i.e. an application-level resource. A single
 * OkHttpClient / WebSocket instance is reused for the life of the process; [connect] /
 * [disconnect] open and close individual WebSocket *connections* on top of it, they do not
 * recreate the OkHttpClient (see README section 32, "Application-level vs call-level resources").
 */
@Singleton
class WebSocketSignalingClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationScope private val externalScope: CoroutineScope
) : SignalingClient {

    private companion object {
        const val TAG = "SignalingClient"
    }

    private var webSocket: WebSocket? = null
    private var pendingRoomId: String? = null

    // extraBufferCapacity so a burst of ICE candidates (or a message arriving before a
    // collector is attached) is never silently dropped.
    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "[Signaling] Connected")
            pendingRoomId?.let { roomId ->
                sendJoin(roomId)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Deliberately not logging the raw payload (may contain full SDP) - see
            // README > Logging: "log the event, not huge payloads".
            val event = SignalingMessageMapper.parseIncoming(text) ?: return
            logInbound(event)
            _events.tryEmit(event)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "[Signaling] Closing: code=$code reason=$reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "[Signaling] Closed: code=$code reason=$reason")
            _events.tryEmit(SignalingEvent.ConnectionClosed)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "[Signaling] Connection failed", t)
            _events.tryEmit(SignalingEvent.ConnectionFailed(t.message ?: t.javaClass.simpleName))
        }
    }

    override fun connect(serverUrl: String, roomId: String) {
        Log.d(TAG, "[Signaling] Connecting to $serverUrl")
        pendingRoomId = roomId
        val request = Request.Builder().url(serverUrl).build()
        webSocket = okHttpClient.newWebSocket(request, listener)
    }

    override fun sendJoin(roomId: String) {
        Log.d(TAG, "[Signaling] Sending JOIN roomId=$roomId")
        webSocket?.send(SignalingMessageMapper.buildJoin(roomId))
    }

    override fun sendOffer(sdp: String) {
        Log.d(TAG, "[Signaling] Sending OFFER")
        webSocket?.send(SignalingMessageMapper.buildOffer(sdp))
    }

    override fun sendAnswer(sdp: String) {
        Log.d(TAG, "[Signaling] Sending ANSWER")
        webSocket?.send(SignalingMessageMapper.buildAnswer(sdp))
    }

    override fun sendIceCandidate(candidate: IceCandidateModel) {
        Log.d(TAG, "[Signaling] Sending ICE candidate")
        webSocket?.send(SignalingMessageMapper.buildIceCandidate(candidate))
    }

    override fun sendLeave() {
        Log.d(TAG, "[Signaling] Sending LEAVE")
        webSocket?.send(SignalingMessageMapper.buildLeave())
    }

    override fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        pendingRoomId = null
    }

    private fun logInbound(event: SignalingEvent) {
        when (event) {
            is SignalingEvent.Joined -> Log.d(TAG, "[Signaling] JOINED: peerId=${event.peerId}")
            is SignalingEvent.PeerJoined -> Log.d(TAG, "[Signaling] PEER_JOINED: peerId=${event.peerId}")
            is SignalingEvent.OfferReceived -> Log.d(TAG, "[Signaling] Received OFFER from=${event.from}")
            is SignalingEvent.AnswerReceived -> Log.d(TAG, "[Signaling] Received ANSWER from=${event.from}")
            is SignalingEvent.IceCandidateReceived -> Log.d(TAG, "[Signaling] Received ICE candidate from=${event.from}")
            is SignalingEvent.PeerLeft -> Log.d(TAG, "[Signaling] PEER_LEFT: peerId=${event.peerId}")
            is SignalingEvent.Error -> Log.e(TAG, "[Signaling] ERROR code=${event.code} message=${event.message}")
            else -> Unit
        }
    }
}
