# Project #4 — Your First Real WebRTC 1-to-1 Video Call

A native Android app (Kotlin + Jetpack Compose + Hilt + native WebRTC) that places a real
peer-to-peer audio/video call between two Android devices, using the **existing Project #3
Node.js/TypeScript/`ws` signaling server** for SDP/ICE exchange only.

This is a learning project first, an app second. Every non-trivial file has comments that
point back to the concept it's teaching. If you only read one thing before diving into code,
read **"How to Read This Project"** near the end of this file.

---

## 1. Project Goal

Project #3 taught you how two browsers/clients exchange signaling messages through a relay
server. Project #4 completes the picture: it's the client that actually **calls
`RTCPeerConnection`** (well, `org.webrtc.PeerConnection`), captures a camera+microphone,
exchanges SDP offers/answers and ICE candidates over your existing server, and ends up with a
**direct, peer-to-peer** audio/video stream between two Android devices - the signaling
server never sees a single video frame.

```
Android Device A
      |
      | WebSocket signaling (JSON, text frames)
      v
Existing Project #3
Node.js Signaling Server
      ^
      | WebSocket signaling
      |
Android Device B

After SDP + ICE exchange:

Android Device A <==========  WebRTC  ==========> Android Device B
                    Audio + Video, peer-to-peer
```

## 2. Architecture

```
                       WebSocket
        +---------------------------------+
        |                                 |
        v                                 v

  +---------------+                 +---------------+
  | Android A     |                 | Android B     |
  |               |                 |               |
  | Compose UI    |                 | Compose UI    |
  | ViewModel     |                 | ViewModel     |
  | Repository    |                 | Repository    |
  |               |                 |               |
  | WebRTC        |                 | WebRTC        |
  +-------+-------+                 +-------+-------+
          |                                 |
          | WebSocket                       | WebSocket
          | signaling                       | signaling
          v                                 v
                  +--------------------+
                  |     Node.js        |
                  |    Project #3      |
                  | Signaling Server   |
                  |  (SIGNALING ONLY)  |
                  +--------------------+
```

After negotiation completes, media flows directly between devices - the server is never in
this path:

```
+---------------+
| Android A     |
+-------+-------+
        |
        | WebRTC Audio/Video  (MEDIA - never touches the server)
        |
        v
+---------------+
| Android B     |
+---------------+
```

### Module structure (Clean-ish Architecture, MVVM)

```
Compose UI  ->  ViewModel  ->  Repository  ->  Signaling + WebRTC
```

```
app/
 |
 +-- presentation/
 |    +-- call/
 |         CallScreen.kt        Compose UI (join form + in-call UI)
 |         CallViewModel.kt     Coordinates UI intents <-> CallRepository
 |         CallUiState.kt       Single immutable UI state
 |         CallUiEvent.kt       User intents (join, mute, end call, ...)
 |
 +-- domain/
 |    +-- model/
 |    |    CallState.kt              Idle/JoiningRoom/WaitingForPeer/Connecting/Connected/...
 |    |    SignalingMessage.kt       SignalingEvent sealed interface + error-code mapping
 |    |    SignalingMessageType.kt   The exact protocol "type" string constants
 |    |    IceCandidateModel.kt      Transport-agnostic ICE candidate shape
 |    +-- repository/
 |         CallRepository.kt         Interface the ViewModel talks to
 |
 +-- data/
 |    +-- signaling/
 |    |    SignalingClient.kt            Interface: connect/join/offer/answer/ice/leave
 |    |    WebSocketSignalingClient.kt   OkHttp WebSocket implementation
 |    |    SignalingMessageMapper.kt     JSON DTOs + parse/build functions (protocol-exact)
 |    +-- webrtc/
 |    |    WebRtcClient.kt            App-level: EglBase + PeerConnectionFactory
 |    |    PeerConnectionManager.kt   Per-call: PeerConnection, SDP, trickle ICE
 |    |    LocalMediaManager.kt       Per-call: camera + mic capture pipeline
 |    |    IceServersProvider.kt      STUN/TURN configuration (client-side only)
 |    |    WebRtcRenderer.kt          SurfaceViewRenderer create/release helper
 |    +-- repository/
 |         CallRepositoryImpl.kt     Wires SignalingClient + WebRtcClient together
 |
 +-- di/
 |    AppModule.kt      OkHttpClient, application-level CoroutineScope
 |    WebRtcModule.kt   Hilt @Binds for SignalingClient / CallRepository
 |
 +-- CallApplication.kt   @HiltAndroidApp entry point
 +-- MainActivity.kt      Single Activity, hosts CallScreen()
```

Why this shape: `presentation` never imports `org.webrtc.*` or `okhttp3.*` directly (it only
sees `CallUiState`/`CallUiEvent`/`CallViewModel`); `data/signaling` never imports
`org.webrtc.*`; `data/webrtc` never imports `okhttp3.*`. `CallRepositoryImpl` is the only class
that is allowed to know about both sides - see README section "Signaling vs WebRTC" below.

## 3. Prerequisites

* Android Studio (a recent version that supports AGP 9.x / Kotlin 2.2.x / compileSdk 37).
* Two physical Android devices (an emulator can work for one side, but camera/mic quality on
  emulators is poor - see Testing below) running Android 7.0 (API 24) or newer, on the same
  Wi-Fi network for the first test.
* Your already-completed **Project #3** Node.js signaling server, runnable locally.
* Both devices (or one device + your dev machine, for the emulator case) reachable on the same
  LAN.

## 4. Existing Signaling Server Requirement

This project does **not** include or reimplement Project #3. It is a required, external
dependency: a Node.js + TypeScript + `ws` WebSocket server that assigns peer IDs, manages
rooms of up to 2 peers, and relays `join`/`offer`/`answer`/`ice_candidate`/`leave` messages
and `joined`/`peer_joined`/`peer_left`/`error` notifications, all in-memory, with **no STUN,
no TURN, and no media**. You must start it separately (see "Running the Node.js Server"
below) before the Android app can do anything.


## 5. Signaling Protocol (exact match to Project #3)

Field names are a byte-for-byte contract with your server: `type`, `roomId`, `peerId`, `sdp`,
`candidate` (`candidate`/`sdpMid`/`sdpMLineIndex`), `from`, `code`, `message`. They are defined
once, in `data/signaling/SignalingMessageMapper.kt`, and nowhere else.

**Client -> Server**

| type            | payload                                             |
|-----------------|------------------------------------------------------|
| `join`          | `{ "type": "join", "roomId": "room-123" }`            |
| `offer`         | `{ "type": "offer", "sdp": "v=0..." }`                |
| `answer`        | `{ "type": "answer", "sdp": "v=0..." }`               |
| `ice_candidate` | `{ "type": "ice_candidate", "candidate": { "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 } }` |
| `leave`         | `{ "type": "leave" }`                                 |

**Server -> Client**

| type            | payload                                                          |
|-----------------|-------------------------------------------------------------------|
| `joined`        | `{ "type": "joined", "roomId": "...", "peerId": "peer-3f9a2b1c" }` |
| `peer_joined`   | `{ "type": "peer_joined", "peerId": "peer-9c1d0e77" }`             |
| `offer`         | `{ "type": "offer", "sdp": "...", "from": "peer-3f9a2b1c" }`       |
| `answer`        | `{ "type": "answer", "sdp": "...", "from": "peer-9c1d0e77" }`      |
| `ice_candidate` | `{ "type": "ice_candidate", "candidate": {...}, "from": "..." }`   |
| `peer_left`     | `{ "type": "peer_left", "peerId": "..." }`                         |
| `error`         | `{ "type": "error", "code": "ROOM_FULL", "message": "..." }`       |

The Android peerId is **always** server-generated - the client stores it (see
`CallRepositoryImpl.handleSignalingEvent`, the `SignalingEvent.Joined` branch) but never
invents its own.

### Error codes -> user-facing messages

`domain/model/SignalingMessage.kt` -> `SignalingErrorMessages.forCode()` maps every code from
the spec to a short sentence, and the app branches on `code`, never on the free-text
`message`:

| code                  | shown to the user                                    |
|-----------------------|-------------------------------------------------------|
| `ROOM_FULL`           | "This room is already full."                          |
| `INVALID_ROOM_ID`     | "Invalid room ID."                                     |
| `ALREADY_IN_ROOM`     | "You are already in a room."                           |
| `NOT_IN_ROOM`         | "You are not currently in a room."                     |
| `ROOM_NOT_FOUND`      | "That room does not exist."                            |
| `INVALID_SDP`         | "A signaling error occurred (invalid session description)." |
| `INVALID_ICE_CANDIDATE` | "A signaling error occurred (invalid ICE candidate)." |
| `PEER_NOT_FOUND`      | "The other participant could not be found."            |
| `MESSAGE_TOO_LARGE`   | "A signaling message was too large to send."           |
| `INVALID_JSON` / `INVALID_MESSAGE` | "A signaling protocol error occurred."   |
| `INTERNAL_ERROR`      | "The signaling server hit an internal error."          |

No stack traces or raw server text ever reach the UI.

## 6. Caller / Callee Determination

The room holds at most two peers. This client uses one deterministic rule (spec section 19):

> **The peer that was already in the room becomes the caller. The peer that joins second
> becomes the callee.**

Concretely, in `CallRepositoryImpl`:

* Receiving **`peer_joined`** means *someone else just joined the room I was already in* ->
  I am the **caller** -> `startAsCaller()`: create the PeerConnection, `createOffer()`,
  `setLocalDescription()`, send `offer`.
* Receiving an **`offer`** (with no PeerConnection of my own yet) means *I never called
  createOffer() myself, someone is calling me* -> I am the **callee** ->
  `startAsCallee(offerSdp)`: create the PeerConnection, `setRemoteDescription(offer)`,
  `createAnswer()`, `setLocalDescription()`, send `answer`.

Both branches also guard on `peerConnectionManager != null` and ignore a second
`peer_joined`/`offer` for the same pairing, so **only one side ever calls `createOffer()`** -
there is no glare, and no perfect-negotiation logic is needed (spec section 42).

## 7. WebRTC Dependency

This project uses **`io.getstream:stream-webrtc-android:1.3.9`** (see
`gradle/libs.versions.toml`, version ref `webrtc`), a maintained, pre-compiled WebRTC AAR for
Android published to Maven Central under the **same `org.webrtc` package** as Google's
original library (`org.webrtc.PeerConnection`, `org.webrtc.VideoTrack`, etc. - see its
[README](https://github.com/GetStream/webrtc-android)). The classic `org.webrtc:google-webrtc`
artifact is no longer reliably published (it lived on the now-shut-down JCenter), so nearly
every current WebRTC Android project uses this or an equivalent prebuilt fork - this project
deliberately avoids copying an outdated `org.webrtc:google-webrtc:1.0.xxxxx` snippet from an
old tutorial.

Because the package name is identical, everything below (PeerConnection, SdpObserver,
RTCConfiguration, ...) is the real native WebRTC API, not a wrapper - only the Maven
coordinate differs from very old tutorials.

`PeerConnection.Observer` in this library version has a few callbacks that don't exist in
older WebRTC snapshots (`onTrack`, `onRemoveTrack`, `onIceCandidateError`,
`onSelectedCandidatePairChanged`) - `PeerConnectionManager.kt` implements all of them; see
the comments there.


## 8. Complete WebRTC + Signaling Flow

```
DEVICE A (already in room)                DEVICE B (joins second)

Connect WebSocket                          Connect WebSocket
      |                                           |
Send JOIN                                   Send JOIN
      |                                           |
Receive JOINED (store peerId)               Receive JOINED (store peerId)
      |                                           |
Wait for PEER_JOINED  <--------- server notifies A that B joined
      |
Create PeerConnection                       Create PeerConnection (on first OFFER, below)
      |
Create Offer
      |
Set Local Description
      |
Send OFFER  ------------------------------------> Receive OFFER
                                                    |
                                             Set Remote Description
                                                    |
                                             Create Answer
                                                    |
                                             Set Local Description
                                                    |
              Receive ANSWER  <------------------- Send ANSWER
                    |
              Set Remote Description

Both sides, as soon as the PeerConnection exists (independent of offer/answer completing):

  onIceCandidate() fires (maybe many times)
        |
        v
  Send ICE Candidate immediately (trickle ICE)
        |
        v
  Receive Remote ICE Candidate  ---->  remote description set yet?
                                              |                 |
                                             yes                no
                                              |                 |
                                       addIceCandidate()   queue it, flush
                                                            once setRemoteDescription()
                                                            completes

Finally:  ICE connectivity checks succeed -> DTLS/SRTP handshake -> Remote Audio + Video flow
```

### Sequence diagram (as in the spec)

```
Android A             Server             Android B
   |                     |                    |
   |---- CONNECT -------->|                    |
   |                     |<---- CONNECT -------|
   |                     |                    |
   |---- JOIN ----------->|                    |
   |<--- JOINED ----------|                    |
   |                     |<---- JOIN ----------|
   |                     |---- JOINED --------->|
   |<--- PEER_JOINED -----|                    |
   |                     |                    |
   |---- OFFER ----------->|                    |
   |                     |---- OFFER ---------->|
   |                     |                    |
   |                     |<---- ANSWER ---------|
   |<--- ANSWER ----------|                    |
   |                     |                    |
   |---- ICE ------------->|---- ICE ------------>|
   |<--- ICE --------------|<---- ICE ------------|
   |                     |                    |
   |================ WEBRTC MEDIA =====================|
   |                     |                    |
   |<----------- AUDIO / VIDEO (peer-to-peer) ---------->|
```

*(This project's rule for who receives `peer_joined` is: only the peer already in the room
does. See "Caller / Callee Determination" above - the spec's own diagram simplifies this a
little, our implementation is defensive either way: a second `peer_joined`/`offer` for a
pairing that already has a PeerConnection is ignored, so this holds regardless of exactly how
your Project #3 server implements the notification.)*

### Step by step, mapped to code

1. **Connect WebSocket** - `WebSocketSignalingClient.connect(serverUrl, roomId)` calls
   `okHttpClient.newWebSocket(...)`.
2. **Send JOIN** - happens automatically from `onOpen()` once the socket connects.
3. **Receive JOINED** - `SignalingEvent.Joined` -> `CallRepositoryImpl` stores `ownPeerId`,
   moves to `CallState.WaitingForPeer`.
4. **Receive PEER_JOINED** - `SignalingEvent.PeerJoined` -> `startAsCaller()`.
5. **Create PeerConnection** - `PeerConnectionManager.createPeerConnection()`, using
   `PeerConnection.RTCConfiguration` + the STUN server from `IceServersProvider`.
6. **Create Offer** - `PeerConnectionManager.createOffer()` (suspend wrapper over
   `SdpObserver`).
7. **Set Local Description** - `PeerConnectionManager.setLocalDescription(offer)`.
8. **Send OFFER** - `signalingClient.sendOffer(offer.description)`.
9. **(B) Receive OFFER** - `SignalingEvent.OfferReceived` -> `startAsCallee(sdp)`.
10. **(B) Set Remote Description** - `PeerConnectionManager.setRemoteDescription(offer)`.
11. **(B) Create Answer** - `PeerConnectionManager.createAnswer()`.
12. **(B) Set Local Description** - `PeerConnectionManager.setLocalDescription(answer)`.
13. **(B) Send ANSWER** - `signalingClient.sendAnswer(answer.description)`.
14. **(A) Receive ANSWER** - `SignalingEvent.AnswerReceived` ->
    `pcManager.setRemoteDescription(answer)`.
15. **Both: ICE candidates** trickle via `PeerConnection.Observer.onIceCandidate()` ->
    `PeerConnectionManager.iceCandidates` (a `SharedFlow`) -> `CallRepositoryImpl` forwards
    each one to `signalingClient.sendIceCandidate(...)` the instant it's generated.
16. **Both: receive ICE candidates** - `SignalingEvent.IceCandidateReceived` ->
    `pcManager.addIceCandidate(...)`, queued if the remote description isn't set yet.
17. **ICE connectivity checks** run internally in WebRTC; `PeerConnection.Observer` logs every
    `iceConnectionState`/`iceGatheringState`/`connectionState` change.
18. **Connected** - `onConnectionChange(CONNECTED)` -> `CallState.Connected` -> UI shows the
    remote video full-screen.

## 9. SDP Offer / Answer

```
Caller (A)                                    Callee (B)
createOffer()                                 (has received the offer)
   |                                                |
setLocalDescription(offer)                    setRemoteDescription(offer)
   |                                                |
offer.description --(WebSocket, unmodified)-->  createAnswer()
                                                     |
                                               setLocalDescription(answer)
                                                     |
   (A) setRemoteDescription(answer) <--(WebSocket, unmodified)-- answer.description
```

Project #3 does not touch SDP at all - the Android client sends `sessionDescription.description`
exactly as WebRTC generated it, and parses the incoming `sdp` string exactly as received, with
no string manipulation ("SDP munging") anywhere in this project.

## 10. ICE Candidate Exchange (Trickle ICE)

Project #3 supports trickle ICE, so this client sends each candidate **the instant**
`PeerConnection.Observer.onIceCandidate()` fires - it never waits for
`iceGatheringState == COMPLETE`:

```
PeerConnection
      |
      v
onIceCandidate()
      |
      v
IceCandidateModel (candidate.sdp -> candidate, candidate.sdpMid -> sdpMid,
                    candidate.sdpMLineIndex -> sdpMLineIndex)
      |
      v
WebSocket  ->  Signaling Server  ->  other Android device
      |
      v
addIceCandidate()  (queued if setRemoteDescription() hasn't completed yet)
```

**Why queuing is needed** (spec section 41): a trickled candidate can legitimately arrive
before the local side has finished `setRemoteDescription()` - for example, the callee's own
ICE candidates can start flowing (once its PeerConnection exists) before it has even
processed the caller's offer, and vice versa on a slow network. Calling
`peerConnection.addIceCandidate()` before the remote description is set is undefined /
ineffective, so `PeerConnectionManager` buffers candidates in `pendingRemoteIceCandidates`
and flushes them right after `setRemoteDescription()`'s `SdpObserver.onSetSuccess()` fires.
The Android client also assumes **many** candidates may arrive per call (typically several
per media section - host, server-reflexive, sometimes relay), never just one.

## 11. STUN (and why no TURN yet)

STUN is configured **only** on the Android client, in `data/webrtc/IceServersProvider.kt`:

```kotlin
PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
```

Project #3 never sees a STUN packet - STUN traffic flows directly between each Android device
and the STUN server over UDP, completely outside the WebSocket connection. STUN's job is
narrow: it tells each device its own public-facing IP:port ("server-reflexive candidate") so
it can be included in the ICE candidate list sent to the other peer. STUN does **not**
guarantee connectivity - if both devices are behind NATs that don't cooperate with the
server-reflexive trick (common with carrier-grade NAT on mobile data, or symmetric NATs), ICE
will fail to find a direct path and the call will simply not connect. That's expected and by
design for this project (spec section 36/44):

```
Signaling Server  !=  STUN Server  !=  TURN Server
```

TURN (a relay server that forwards media when a direct/STUN path fails) is **intentionally
not implemented** here - it's the subject of a later project in the roadmap. If you test with
Device A on Wi-Fi and Device B on mobile data and the call doesn't connect, that is the
expected STUN-only limitation, not a bug in this project.


## 12. Running the Node.js Signaling Server (Project #3)

This project does **not** re-create Project #3 - start your existing server separately:

```bash
cd project-3-signaling-server
npm install
npm run dev
```

Note the port it listens on (the examples throughout assume `8080`).

### Signaling Server URL - physical device vs emulator

The URL is fully configurable in-app (the "Signaling server URL" field on the join screen) -
it is never hardcoded into the source beyond a single default in
`app/build.gradle.kts` (`DEFAULT_SIGNALING_SERVER_URL`, exposed as `BuildConfig.DEFAULT_SIGNALING_SERVER_URL`
and used as the join screen's initial text).

* **Physical Android device**: `ws://localhost:8080` will **not** work - on a physical device,
  "localhost" means the device itself, not your development machine. Use your development
  machine's **LAN IP**, e.g. `ws://192.168.1.100:8080` (find it with `ipconfig getifaddr en0`
  on macOS, or `ip addr` on Linux). Both the phone and your machine must be on the same Wi-Fi
  network.
* **Android Emulator**: the emulator maps `10.0.2.2` to your host machine's `localhost`, so
  `ws://10.0.2.2:8080` reaches a server running on your development machine. This is the
  project's build-time default.

### Local development security: `ws://` vs `wss://`

Project #3 runs locally as plain `ws://` during development - this project's
`AndroidManifest.xml` sets `android:usesCleartextTraffic="true"` specifically to allow that
(Android blocks cleartext networking by default on modern `targetSdk` values). This is a
**local-development-only** setting. A production app should talk to `wss://` (WebSocket over
TLS) exclusively and should remove `usesCleartextTraffic` (or replace it with a network
security config scoped to your dev host only). Do not ship the cleartext-enabled build.

## 13. Running the Android App

1. Start Project #3 (see above) and note its LAN IP + port.
2. Open this project in Android Studio, let Gradle sync (it will download Hilt/KSP/OkHttp/
   kotlinx.serialization/WebRTC the first time - this can take a few minutes).
3. Run the app on two devices (`Run > Run 'app'`, pick each device in turn, or build once and
   install the APK on both).
4. On both devices, set the same **Signaling server URL** and enter the **same Room ID**
   (e.g. `room-123`).
5. Grant camera + microphone permissions when prompted.
6. Tap **Join Room** on device A first, then device B. Device A becomes the caller, device B
   the callee (see "Caller / Callee Determination"); within a couple of seconds you should see
   each other's video.

## 14. Testing With Two Devices

**Test 1 - Two physical devices, same Wi-Fi.** A joins `room-123`, then B joins `room-123`.
Expected: A sees B, B sees A, audio works both ways, video works both ways, `Status: Connected`
on both screens.

**Test 2 - A third device joins the same room.** Expected: the server rejects the join with
`ROOM_FULL`, the third device sees "This room is already full." and stays on the join screen.

**Test 3 - One peer leaves.** Either device taps **End Call** (or force-closes the app).
Expected: the *other* device receives `peer_left`, closes its PeerConnection, clears the
remote video, and returns to `Status: Waiting for peer...`.

**Test 4 - Signaling server unavailable.** Stop Project #3, then try to join. Expected: a
connection error message on the join screen, no crash (`SignalingEvent.ConnectionFailed` ->
`CallState.Error`).

**Test 5 - Camera permission denied.** Deny the camera permission when prompted. Expected: a
clear "Camera and microphone permissions are required to start a call." message, no crash, the
app stays on the join screen.

**Test 6 - Microphone permission denied.** Same as Test 5 for `RECORD_AUDIO`.

### Testing across different networks

After the same-Wi-Fi test passes, try Device A on Wi-Fi and Device B on mobile data. This
**may or may not work** with STUN alone, depending on both devices' NAT/firewall behavior -
see "STUN (and why no TURN yet)" above. A failure here is expected and is exactly the
motivation for the TURN project that follows this one in the roadmap; it does not indicate a
bug in this project's signaling or WebRTC code (check Logcat's `iceConnectionState`
transitions - `CHECKING` that never reaches `CONNECTED`/`COMPLETED` and eventually goes to
`FAILED` is the signature of this specific, expected limitation).

## 15. Troubleshooting

| Symptom | Likely cause | What to check |
|---|---|---|
| Join screen never leaves "Joining room..." | Wrong signaling URL, or server not running | Confirm the server is up and the URL/port/IP are correct (`ws://` + LAN IP, not `localhost`, on a physical device) |
| "This room is already full." | A third client (or a previous run that didn't send `leave`) is still in the room | Restart the signaling server, or use a fresh room ID |
| I see my own local preview but never the other person's video | ICE never connected | Check Logcat for `[WebRTC] ICE connection state = ...` - if it's stuck on `CHECKING` or hits `FAILED`, it's most likely a NAT/network issue (see STUN section); confirm both devices actually reached `Connected`/`joined` on the signaling side first |
| Video connects but there's no audio | Very rare with WebRTC's built-in audio pipeline | Confirm `RECORD_AUDIO` was granted; check `[WebRTC] Remote audio track received` appears in Logcat |
| App crashes on rotate | Shouldn't happen - `MainActivity` declares `android:configChanges` for orientation/screenSize | File an issue against this project if it does |
| Gradle sync fails downloading dependencies | No network, or a corporate proxy blocking Maven Central / Google's repo | Check your network; all dependencies come from `google()`/`mavenCentral()` (see `settings.gradle.kts`) |


## 16. WebRTC Connection States

`PeerConnectionManager` logs every state transition WebRTC exposes, but the UI only ever shows
the coarse `CallState` (Idle/JoiningRoom/WaitingForPeer/Connecting/Connected/Ending/Ended/Error)
- see `CallUiState.toStatusText()`. The detailed states are for *you*, in Logcat, while
learning:

* **`signalingState`** (`PeerConnection.SignalingState`) - where the SDP offer/answer exchange
  is: `STABLE` (nothing pending), `HAVE_LOCAL_OFFER`, `HAVE_REMOTE_OFFER`, etc. Goes back to
  `STABLE` once both descriptions are set.
* **`iceGatheringState`** - `NEW` -> `GATHERING` -> `COMPLETE`. With trickle ICE we don't wait
  for `COMPLETE`; candidates are sent throughout `GATHERING`.
* **`iceConnectionState`** - the classic ICE state machine: `NEW` -> `CHECKING` (trying candidate
  pairs) -> `CONNECTED` (found a working pair) -> `COMPLETED` (finished checking all pairs) /
  `FAILED` (no working pair found) / `DISCONNECTED` (was connected, lost it - may recover) /
  `CLOSED`.
* **`connectionState`** (`PeerConnection.PeerConnectionState`) - the aggregate state (ICE +
  DTLS certificate exchange combined) that this project actually keys `CallState` off of:
  `NEW` -> `CONNECTING` -> `CONNECTED` (this is what flips the UI to `Status: Connected`) /
  `FAILED` (mapped to `CallState.Error`) / `DISCONNECTED` (transient - mapped back to
  `Connecting`, may self-heal) / `CLOSED`.

## 17. Remote Audio

There is no custom audio playback pipeline in this project, on purpose (spec section 22):

```
Remote Peer -> WebRTC -> Remote AudioTrack -> WebRTC's JavaAudioDeviceModule -> Android Speaker/Earpiece
```

Once `PeerConnection.Observer.onAddTrack()` reports a remote `AudioTrack`
(`PeerConnectionManager` logs `[WebRTC] Remote audio track received`), WebRTC's own
`JavaAudioDeviceModule` (configured once in `WebRtcClient.buildFactory()`) automatically
renders it to the device's active audio output. There is nothing else to wire up - this is
exactly why `PeerConnection.Observer` and the audio device module exist as native building
blocks rather than something you'd hand-roll with `AudioTrack`/`AudioRecord` yourself.

## 18. Microphone / Camera Control

* **Mute/unmute** (`LocalMediaManager.setMicEnabled`): toggles `AudioTrack.setEnabled(false)`.
  This is instant, requires no SDP renegotiation, and the other peer just receives silence
  (their `RtpReceiver` keeps running) - the alternative, stopping/removing the track from the
  `PeerConnection`, would trigger `onRenegotiationNeeded()` and require a whole new
  offer/answer round, which this project intentionally avoids (see "No Perfect Negotiation").
* **Camera on/off** (`LocalMediaManager.setCameraEnabled`): both disables `VideoTrack.setEnabled(false)`
  **and** stops the `CameraVideoCapturer` (`videoCapturer.stopCapture()`), so the camera
  hardware itself is released (and the camera-in-use indicator turns off) rather than just
  sending black frames. This is a deliberate middle ground between the three options the spec
  calls out:
  * *VideoTrack disabled* alone - cheapest, instant, but the camera keeps running.
  * *Capturer stopped* (what we add) - releases the hardware, still instant, no renegotiation.
  * *Sender removed* - would also require renegotiation; not used here.
* **Camera switch** (`LocalMediaManager.switchCamera`): uses
  `CameraVideoCapturer.switchCamera(CameraSwitchHandler)` - the `PeerConnection` and its tracks
  are untouched; WebRTC just starts receiving frames from a different physical camera on the
  same `VideoSource`/`VideoTrack`.

## 19. End Call / peer_left / Unexpected Disconnect

**User taps End Call** (`CallRepository.endCall()`):

```
Android
   |
   send { "type": "leave" }
   |
   close PeerConnection (PeerConnectionManager.close())
   |
   release local media (LocalMediaManager.release())
   |
   close WebSocket (SignalingClient.disconnect())
   |
   CallState -> Ending -> Ended -> UI returns to the join screen
```

**Other peer sends `leave` / disconnects** (`SignalingEvent.PeerLeft`): Project #3 already
detects a dropped WebSocket connection server-side and sends `peer_left` to the remaining
peer - this app reacts by closing just the `PeerConnectionManager` (keeping local media and
the signaling connection alive) and returning to `CallState.WaitingForPeer`, so you can either
wait for someone new to join the same room or tap End Call yourself.

**Unexpected WebSocket close / failure** (`SignalingEvent.ConnectionClosed` /
`ConnectionFailed`): both are handled without crashing - all call resources are released and
`CallState.Error` is shown with a human-readable message.

**PeerConnection failure** (`connectionState == FAILED`): mapped to `CallState.Error` (see
"WebRTC Connection States" above) rather than silently hanging.

## 20. Android Lifecycle & Resource Ownership

| Resource | Scope | Created by | Released by |
|---|---|---|---|
| `OkHttpClient` | Application (Hilt `@Singleton`) | `AppModule.provideOkHttpClient()` | Never explicitly (process death) |
| `EglBase` / `PeerConnectionFactory` | Application (Hilt `@Singleton`) | `WebRtcClient` (lazy, first call) | Never explicitly (process death) - see spec section 32: don't recreate expensive global WebRTC resources |
| `SignalingClient` (WebSocket) | Application (Hilt `@Singleton`) | `WebSocketSignalingClient` | `disconnect()`, called from `CallRepositoryImpl.endCall()`/cleanup paths |
| `CallRepositoryImpl` | Application (Hilt `@Singleton`) | Hilt, at first injection | Never explicitly - it just sits idle in `CallState.Idle` between calls |
| `PeerConnectionManager` (one PeerConnection) | Per call | `WebRtcClient.newPeerConnectionManager()` | `close()`, on end-call / peer-left / connection failure |
| `LocalMediaManager` (camera+mic pipeline) | Per call | `WebRtcClient.newLocalMediaManager()` | `release()`, same triggers as above |
| `SurfaceViewRenderer` (local + remote views) | Per Compose composition | `WebRtcRenderer.create()` inside `VideoRendererView` | `WebRtcRenderer.release()` inside a `DisposableEffect`, and the video track's sink is added/removed in a separate `DisposableEffect` keyed on the track identity |

Because `CallRepositoryImpl` is a Hilt singleton (an application-level resource) while
`PeerConnectionManager`/`LocalMediaManager` are call-level (created fresh per call, released on
end), a screen rotation or other configuration change **does not** interrupt an in-progress
call - `MainActivity` also declares `android:configChanges` for orientation/screenSize so the
Activity isn't even recreated, avoiding any renderer flicker.


## 21. WebRTC API Learning Guide

For each API: what it is, why we need it, who creates it, what it creates, what it talks to,
when it's used, and when it's released. All of these live in `data/webrtc/*.kt`.

**`PeerConnectionFactory`**
What: the root factory for every other WebRTC object (tracks, sources, the PeerConnection
itself). Why: WebRTC's native (C++) engine needs one-time global initialization before
anything else can be created. Who creates it: `WebRtcClient.buildFactory()`, lazily, once per
process. What it creates: `PeerConnection`, `VideoSource`, `AudioSource`, `VideoTrack`,
`AudioTrack`. Talks to: the native WebRTC engine (JNI), the configured encoder/decoder
factories, the audio device module. When used: once, to build every other object. When
released: never explicitly in this project (application-level, released implicitly on
process death) - see spec section 32.

**`PeerConnection`**
What: one peer-to-peer media/data connection. Why: it's the object that actually does SDP
negotiation, ICE, DTLS/SRTP, and carries the media. Who creates it:
`PeerConnectionFactory.createPeerConnection(RTCConfiguration, Observer)`, wrapped by
`PeerConnectionManager.createPeerConnection()`. What it creates: nothing directly, but
produces `SessionDescription`s (via `createOffer`/`createAnswer`) and reports `IceCandidate`s
and remote tracks through its `Observer`. Talks to: the network (ICE/STUN/DTLS/SRTP), and its
`Observer` callbacks. When used: one instance per call. When released: `close()` +
`dispose()`, in `PeerConnectionManager.close()`.

**`RTCConfiguration` / `IceServer`**
What: `RTCConfiguration` is the PeerConnection's settings object (ICE servers, gathering
policy, SDP semantics); `IceServer` describes one STUN/TURN server. Why: WebRTC needs to know
which STUN/TURN servers to use before it starts gathering ICE candidates. Who creates it:
`PeerConnectionManager.createPeerConnection()` builds the `RTCConfiguration`;
`IceServersProvider.default()` builds the `IceServer` list. What it creates: nothing (a
config object). Talks to: passed once into `createPeerConnection()`. When used: at
PeerConnection creation time only (not mutable afterwards in this project). When released:
n/a - a plain data object, garbage collected normally.

**`AudioSource` / `AudioTrack`**
What: `AudioSource` wraps the microphone capture pipeline; `AudioTrack` is the sendable/
receivable track built on top of it. Why: WebRTC needs a `MediaStreamTrack` to add to the
`PeerConnection` and negotiate in SDP. Who creates it:
`factory.createAudioSource(MediaConstraints())` / `factory.createAudioTrack(id, source)` in
`LocalMediaManager.initLocalTracks()`. What it creates: nothing further. Talks to: the Android
audio HAL (via the audio device module) for local tracks; the network for remote ones. When
used: for the whole call, added to the `PeerConnection` once via `addTrack()`. When released:
`AudioTrack.dispose()` / `AudioSource.dispose()` in `LocalMediaManager.release()`.

**`VideoSource` / `VideoTrack`**
What: `VideoSource` receives frames from a `CameraVideoCapturer`; `VideoTrack` is the
sendable/receivable track. Why/who/what/talks-to: mirrors `AudioSource`/`AudioTrack` above,
but frames come from the camera capturer instead of the microphone, and are rendered via a
`SurfaceViewRenderer` locally and remotely. When released: `VideoTrack.dispose()` /
`VideoSource.dispose()` in `LocalMediaManager.release()`.

**`CameraVideoCapturer`**
What: drives the Camera2 API and pushes frames into a `VideoSource`. Why: WebRTC doesn't talk
to Android's camera APIs directly - this bridges them. Who creates it:
`Camera2Enumerator(context).createCapturer(deviceName, CameraEventsHandler)` in
`LocalMediaManager.initLocalTracks()`. What it creates: nothing (an intermediate pipeline
stage). Talks to: the Android `Camera2` subsystem, and the `VideoSource`'s
`CapturerObserver`. When used: `startCapture()`/`stopCapture()` around camera on/off,
`switchCamera()` to flip front/back. When released: `dispose()` in
`LocalMediaManager.release()`.

**`SurfaceViewRenderer`**
What: a `SurfaceView` that WebRTC can render decoded (or raw local) video frames into
directly, GPU-accelerated via the shared `EglBase.Context`. Why: turns a `VideoTrack` into
actual on-screen pixels. Who creates it: `WebRtcRenderer.create()`, called from Compose's
`AndroidView` factory in `CallScreen.VideoRendererView`. What it creates: nothing - it's a
sink. Talks to: whatever `VideoTrack` is currently attached via `track.addSink(renderer)`.
When used: for as long as the composable showing it is on screen. When released:
`WebRtcRenderer.release()` in a `DisposableEffect(Unit)` when the composable leaves
composition - always paired with removing it as a sink first.

**`SessionDescription`**
What: an SDP offer or answer (`type` + `description` string). Why: this is literally what
gets negotiated and sent over signaling. Who creates it: `PeerConnection.createOffer()` /
`createAnswer()` (wrapped as suspend functions in `PeerConnectionManager`). What it creates:
nothing. Talks to: `setLocalDescription()`/`setRemoteDescription()` locally, and the
`SignalingClient` (as a plain `sdp` string) over the network. When used: once per offer, once
per answer, per call. When released: n/a - a plain data object.

**`IceCandidate`**
What: one candidate network path (`sdp`, `sdpMid`, `sdpMLineIndex`). Why: this is the unit
ICE negotiation works in. Who creates it: WebRTC internally, delivered via
`PeerConnection.Observer.onIceCandidate()`; reconstructed on the receiving side from
`IceCandidateModel` via `IceCandidate(sdpMid, sdpMLineIndex, sdp)`. What it creates: nothing.
Talks to: `peerConnection.addIceCandidate()` locally, `SignalingClient.sendIceCandidate()`
over the network. When used: potentially many per call, trickled continuously while ICE
gathers. When released: n/a - a plain data object.

**`PeerConnection.Observer`**
What: the callback interface through which the native WebRTC engine tells your app what's
happening (`onIceCandidate`, `onAddTrack`, `onConnectionChange`, `onIceConnectionChange`,
etc.). Why: WebRTC's core is asynchronous/event-driven by nature. Who creates it: one anonymous
implementation per `PeerConnectionManager`, passed into `createPeerConnection()`. What it
creates: nothing - it only reacts. Talks to: forwards events into this project's
`Flow`/`StateFlow`s (`iceCandidates`, `remoteVideoTrack`, `connectionState`) so the rest of
the app never touches the Observer directly. When used: for the entire lifetime of one
`PeerConnection`. When released: implicitly, when the `PeerConnection` is closed/disposed.

## 22. Critical Learning Questions

**Q1. Why doesn't the Node.js signaling server carry audio/video?**
Because it was never asked to - `WebSocketSignalingClient` only ever sends JSON text frames
(`join`/`offer`/`answer`/`ice_candidate`/`leave`). The actual audio/video bytes are SRTP
packets that `PeerConnection` sends over its own UDP sockets directly to the other device,
established only after ICE + DTLS complete. WebSocket and WebRTC's media transport are two
completely separate network connections.

**Q2. Why does WebRTC need SDP?**
Because two peers need to agree on *what* they're going to send before they send it: which
codecs (VP8/H264/Opus/...), how many media sections (audio + video here), which
transport/security parameters (ICE ufrag/pwd, DTLS fingerprint), etc. `createOffer()`/
`createAnswer()` produce that description; `setLocalDescription()`/`setRemoteDescription()`
are what actually configure the local media engine to match it.

**Q3. Why does WebRTC need ICE?**
Because "the other device's IP address" isn't a simple, known fact on mobile networks - both
sides are almost always behind NAT. ICE (with STUN's help) discovers every viable network
path (host, server-reflexive, sometimes relay) on both sides, then tries pairs of them until
it finds one that actually works, all without either side needing a public IP.

**Q4. What does the signaling server actually do?**
Exactly four things, all in `data/signaling/`'s mirror image of Project #3: assigns/reports
peer IDs, manages room membership (`join`/`joined`/`peer_joined`/`peer_left`), and relays two
kinds of opaque payloads - SDP strings and ICE candidates - between exactly two peers. It
never inspects, modifies, or understands their contents.

**Q5. Why do we need WebSocket?**
Because signaling needs a persistent, bidirectional, low-latency channel: the server must be
able to push `peer_joined`/`offer`/`answer`/`ice_candidate`/`peer_left` to a client at any
time, not just in response to a request (which plain HTTP request/response can't do
naturally). `WebSocketSignalingClient` uses OkHttp's WebSocket for exactly this.

**Q6. What happens when `createOffer()` is called?**
WebRTC inspects the tracks currently added to the `PeerConnection` (our local audio + video
tracks, added via `addLocalTracks()` before `createOffer()` is called) and produces a
`SessionDescription` describing them - codecs, directions, ICE/DTLS parameters - *without*
yet applying it to the connection. It's just a proposal at this point.

**Q7. Why must we call `setLocalDescription()`?**
Because `createOffer()`/`createAnswer()` only generate a description - they don't commit it.
`setLocalDescription()` is what actually configures the local media engine (starts ICE
gathering, prepares to send matching this SDP) to match what you're about to send.

**Q8. Why does the other peer call `setRemoteDescription()`?**
So its own `PeerConnection` knows what the far side proposed/agreed to - which codecs it will
receive, which m-lines correspond to audio vs video (needed to correctly route incoming ICE
candidates and RTP packets), and what security parameters to expect during the DTLS
handshake.

**Q9. Why does the callee create an Answer?**
Because SDP negotiation is a strict offer/answer model (RFC 3264): one side proposes, the
other side must respond with a compatible description before media can flow. `createAnswer()`
can only be called meaningfully after `setRemoteDescription(offer)`, because the answer needs
to reference what was offered.

**Q10. Why are ICE candidates exchanged separately (not embedded in the offer/answer)?**
Because gathering all candidates can take time (STUN round-trips, sometimes seconds), and
waiting for every candidate before sending the offer/answer would needlessly delay call
setup. Trickle ICE (RFC 8838) sends the offer/answer immediately with whatever's known so
far, then streams candidates as they're discovered.

**Q11. What is trickle ICE?**
Exactly what section 10/15 above describe: sending each `IceCandidate` over signaling the
instant `onIceCandidate()` fires, instead of batching them into the SDP and waiting for
`iceGatheringState == COMPLETE`. It's why `PeerConnectionManager.addIceCandidate()` has to
handle candidates arriving before `setRemoteDescription()` completes.

**Q12. What happens after ICE connectivity succeeds?**
WebRTC performs a DTLS handshake over the winning candidate pair (establishing encryption
keys), derives SRTP/SRTCP keys from it, and then starts sending/receiving encrypted RTP media
packets directly between the two devices - this is exactly the moment
`connectionState` becomes `CONNECTED` and `CallState.Connected` is reached.

**Q13. Where does STUN fit?**
During ICE gathering, before any of the above - see "STUN (and why no TURN yet)". It's a
one-shot UDP request/response to discover a server-reflexive candidate; it plays no further
role once candidates have been gathered.

**Q14. Where would TURN fit?**
As a fallback candidate type ("relay") gathered at the same stage as STUN's server-reflexive
candidates, used only when ICE connectivity checks fail for every direct path - media would
then flow Device A -> TURN server -> Device B instead of peer-to-peer. Not implemented in
this project (see `IceServersProvider` - only a STUN server is configured); that's the next
project in the roadmap.

**Q15. Does the Node.js server know anything about the actual video stream?**
No. It only ever sees `IceCandidateModel`/SDP strings as opaque JSON payloads it relays
verbatim between two `WebSocket` connections - it has no PeerConnection, no media pipeline,
and (per the spec) explicitly no STUN/TURN of its own. The entire video/audio stream, once
connected, never touches it.


## 23. Logging

Every meaningful step logs a short, tagged line (never a full SDP blob or credentials) -
search Logcat for `[Signaling]` and `[WebRTC]`:

```
[Signaling] Connecting
[Signaling] Connected
[Signaling] Sending JOIN roomId=room-123
[Signaling] JOINED: peerId=peer-3f9a2b1c
[Signaling] PEER_JOINED: peerId=peer-9c1d0e77
[Signaling] Sending OFFER
[Signaling] Received OFFER from=peer-3f9a2b1c
[Signaling] Sending ANSWER
[Signaling] Received ANSWER from=peer-9c1d0e77
[WebRTC] PeerConnection created
[WebRTC] Local audio/video tracks added to PeerConnection
[WebRTC] Local description set
[WebRTC] Remote description set
[WebRTC] ICE candidate generated
[WebRTC] ICE candidate received and added
[WebRTC] ICE connection state = CONNECTED
[WebRTC] Remote video track received
[WebRTC] Peer connection state = CONNECTED
```

## 24. Do Not Overengineer (what this project deliberately skips)

Per the spec, this project stays scoped to exactly: **Project #3 signaling server + native
Android WebRTC client + STUN + one room, two peers, one call.** It intentionally does not
include authentication, a database, push notifications, call history, group calls/SFU, a TURN
server, screen sharing, recording, background calling, call notifications, production user
accounts, Redis, or Firebase. All of those are either out of scope for a "first real call"
project or are explicitly later items in the roadmap (TURN in particular - see section 11/14).

## 25. WebRTC Learning Checklist

- [ ] I can connect Android to the existing WebSocket signaling server.
- [ ] I understand the Project #3 signaling protocol.
- [ ] I understand JOIN.
- [ ] I understand PEER_JOINED.
- [ ] I understand server-generated peerId.
- [ ] I can create a PeerConnection.
- [ ] I can create an SDP Offer.
- [ ] I understand setLocalDescription().
- [ ] I understand setRemoteDescription().
- [ ] I can create an SDP Answer.
- [ ] I understand ICE candidates.
- [ ] I understand trickle ICE.
- [ ] I can send ICE candidates through WebSocket.
- [ ] I can receive ICE candidates.
- [ ] I can call addIceCandidate().
- [ ] I understand STUN.
- [ ] I understand that the signaling server is not a media server.
- [ ] I understand the difference between signaling and WebRTC.
- [ ] I can display local video.
- [ ] I can display remote video.
- [ ] I can transmit microphone audio.
- [ ] I can mute/unmute.
- [ ] I can enable/disable the camera.
- [ ] I can switch cameras.
- [ ] I can end a call.
- [ ] I understand peer_left.
- [ ] I can troubleshoot a failed ICE connection.
- [ ] I can explain the complete WebRTC call flow without looking at the code.

## 26. How to Read This Project

Don't read top to bottom - the codebase is intentionally ordered to teach the flow
incrementally. Recommended order, and what to understand before moving to the next stage:

1. **`domain/model/SignalingMessage.kt` + `SignalingMessageType.kt` + `IceCandidateModel.kt`**
   Understand the shape of the protocol first - what messages exist, in both directions, and
   what data each carries. You should be able to describe every message type from memory
   before moving on.

2. **`data/signaling/WebSocketSignalingClient.kt`** (+ `SignalingMessageMapper.kt`)
   See how those messages become real JSON over a real WebSocket - `connect()` -> `onOpen()`
   auto-sends `join`, `onMessage()` parses and emits a `SignalingEvent`. Understand that this
   class knows *nothing* about WebRTC.

3. **`data/webrtc/PeerConnectionManager.kt`**
   The heart of the project. Read `createPeerConnection()`, then `createOffer()`/
   `createAnswer()`/`setLocalDescription()`/`setRemoteDescription()` (notice the
   `suspendCancellableCoroutine` wrapper around each callback-based WebRTC call), then
   `addIceCandidate()`/`flushPendingIceCandidates()` (the queuing logic). Don't move on until
   you can explain why candidates might need queuing.

4. **`data/webrtc/LocalMediaManager.kt`**
   The camera/mic capture pipeline: `Camera2Enumerator` -> `CameraVideoCapturer` ->
   `VideoSource` -> `VideoTrack`, and `AudioSource` -> `AudioTrack` alongside it.

5. **SDP Offer** - re-read `CallRepositoryImpl.startAsCaller()` now that you know what each
   call inside it does.

6. **SDP Answer** - re-read `CallRepositoryImpl.startAsCallee()`.

7. **ICE candidate exchange** - `CallRepositoryImpl.observePeerConnection()` (forwarding local
   candidates out) and the `SignalingEvent.IceCandidateReceived` branch of
   `handleSignalingEvent()` (feeding remote candidates in).

8. **Remote track handling** - `PeerConnectionManager`'s `Observer.onAddTrack()`, and how
   `remoteVideoTrack` (a `StateFlow`) flows all the way up into `CallUiState.remoteVideoTrack`
   and finally into `VideoRendererView` in `CallScreen.kt`.

9. **`data/repository/CallRepositoryImpl.kt`**
   Now read the whole file top to bottom - every piece above is wired together here, and the
   "Caller / Callee Determination" logic should now make complete sense.

10. **`presentation/call/CallViewModel.kt`**
    See how repository `Flow`s become one `CallUiState`, and how UI intents
    (`CallUiEvent`) become repository calls. Notice there is no WebRTC/WebSocket code here at
    all.

11. **`presentation/call/CallScreen.kt`**
    Finally, the UI: the join form vs. in-call switch (`CallUiState.isPreJoinScreen`), the
    `VideoRendererView` composable (SurfaceViewRenderer lifecycle inside Compose via
    `AndroidView`), and the controls row.

Do not expect to read the entire project start to finish in one sitting - the goal is to come
away from each stage able to explain that stage's concept out loud before moving to the next
one, while ending up with a complete, working two-device video call.

---

*Generated as Project #4 of a personal WebRTC learning roadmap. Project #3 (the Node.js/
TypeScript/`ws` signaling server this app depends on) is a separate, already-completed
project and is intentionally not reproduced here.*
