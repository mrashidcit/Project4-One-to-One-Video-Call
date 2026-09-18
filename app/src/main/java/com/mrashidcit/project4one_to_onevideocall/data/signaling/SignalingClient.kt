package com.mrashidcit.project4one_to_onevideocall.data.signaling

import com.mrashidcit.project4one_to_onevideocall.domain.model.IceCandidateModel
import com.mrashidcit.project4one_to_onevideocall.domain.model.SignalingEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * Talks ONLY the Project #3 WebSocket JSON protocol. No WebRTC types or logic live here -
 * see README section 8 ("Do not mix WebRTC logic into the WebSocket client").
 *
 * [WebSocketSignalingClient] is the (only) implementation, using OkHttp's WebSocket.
 */
interface SignalingClient {

    /** Every inbound message, already parsed into a [SignalingEvent]. Hot, shared, replay = 0. */
    val events: SharedFlow<SignalingEvent>

    /**
     * Opens the WebSocket to [serverUrl] and, once connected (onOpen), automatically sends
     * `{"type":"join","roomId":roomId}`. Combining connect+join avoids a race where callers
     * would otherwise need to wait for a "connected" signal before sending join themselves.
     */
    fun connect(serverUrl: String, roomId: String)

    /** `{"type":"join","roomId":...}` - normally you don't need to call this directly, see [connect]. */
    fun sendJoin(roomId: String)

    /** `{"type":"offer","sdp":...}` */
    fun sendOffer(sdp: String)

    /** `{"type":"answer","sdp":...}` */
    fun sendAnswer(sdp: String)

    /** `{"type":"ice_candidate","candidate":{...}}` - called once per trickled candidate. */
    fun sendIceCandidate(candidate: IceCandidateModel)

    /** `{"type":"leave"}` */
    fun sendLeave()

    /** Closes the WebSocket. Safe to call even if not connected. */
    fun disconnect()
}
