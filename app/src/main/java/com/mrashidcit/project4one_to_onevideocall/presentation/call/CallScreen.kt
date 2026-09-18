package com.mrashidcit.project4one_to_onevideocall.presentation.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrashidcit.project4one_to_onevideocall.data.webrtc.WebRtcRenderer
import com.mrashidcit.project4one_to_onevideocall.domain.model.CallState
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * The one and only screen in this app. Switches between the pre-join form and the in-call UI
 * purely based on [CallUiState.isPreJoinScreen] - see README section 23 for the mockups this
 * is based on.
 */
@Composable
fun CallScreen(viewModel: CallViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.CAMERA] == true &&
            grants[Manifest.permission.RECORD_AUDIO] == true
        if (granted) {
            viewModel.onEvent(CallUiEvent.JoinRoomClicked)
        } else {
            viewModel.onEvent(CallUiEvent.PermissionsDenied)
        }
    }
    val context = LocalContext.current

    val requestJoin: () -> Unit = requestJoin@{
        val cameraGranted = context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (cameraGranted && micGranted) {
            viewModel.onEvent(CallUiEvent.JoinRoomClicked)
            return@requestJoin
        }
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    if (uiState.isPreJoinScreen) {
        JoinRoomScreen(
            uiState = uiState,
            onEvent = viewModel::onEvent,
            onJoinClick = requestJoin
        )
    } else {
        InCallScreen(uiState = uiState, onEvent = viewModel::onEvent)
    }
}

@Composable
private fun JoinRoomScreen(
    uiState: CallUiState,
    onEvent: (CallUiEvent) -> Unit,
    onJoinClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "WebRTC Call",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = uiState.serverUrlInput,
            onValueChange = { onEvent(CallUiEvent.ServerUrlChanged(it)) },
            label = { Text("Signaling server URL") },
            placeholder = { Text("ws://192.168.1.100:8080") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = uiState.roomIdInput,
            onValueChange = { onEvent(CallUiEvent.RoomIdChanged(it)) },
            label = { Text("Room ID") },
            placeholder = { Text("room-123") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onJoinClick, modifier = Modifier.fillMaxWidth()) {
            Text("Join Room")
        }

        uiState.errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Status: ${uiState.statusText}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InCallScreen(uiState: CallUiState, onEvent: (CallUiEvent) -> Unit) {
    val eglBaseContext = uiState.eglBaseContext

    Box(modifier = Modifier.fillMaxSize()) {
        // Remote video: full screen, primary content (README section 21).
        if (eglBaseContext != null) {
            VideoRendererView(
                eglBaseContext = eglBaseContext,
                videoTrack = uiState.remoteVideoTrack,
                mirror = false,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (uiState.remoteVideoTrack == null) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Text(
                    text = uiState.statusText,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // Local preview: small overlay, top-end corner (README section 23 mockup).
        if (eglBaseContext != null && uiState.localVideoTrack != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(width = 120.dp, height = 160.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                VideoRendererView(
                    eglBaseContext = eglBaseContext,
                    videoTrack = uiState.localVideoTrack,
                    // Front camera is conventionally mirrored so it behaves like a mirror.
                    mirror = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Status text (top-start).
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Text(
                text = "Status: ${uiState.statusText}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // Controls row (README section 23 mockup: mic, camera, switch-camera, end call).
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ControlButton(
                    emoji = if (uiState.isMicEnabled) "🎤" else "🔇",
                    contentDescription = if (uiState.isMicEnabled) "Mute microphone" else "Unmute microphone",
                    isActive = uiState.isMicEnabled,
                    onClick = { onEvent(CallUiEvent.ToggleMicrophone) }
                )
                ControlButton(
                    emoji = if (uiState.isCameraEnabled) "📹" else "📷",
                    contentDescription = if (uiState.isCameraEnabled) "Turn camera off" else "Turn camera on",
                    isActive = uiState.isCameraEnabled,
                    onClick = { onEvent(CallUiEvent.ToggleCamera) }
                )
                ControlButton(
                    emoji = "🔄",
                    contentDescription = "Switch camera",
                    isActive = true,
                    onClick = { onEvent(CallUiEvent.SwitchCamera) }
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            ControlButton(
                emoji = "📞",
                contentDescription = "End call",
                isActive = false,
                background = MaterialTheme.colorScheme.error,
                onClick = { onEvent(CallUiEvent.EndCallClicked) }
            )
        }
    }
}

@Composable
private fun ControlButton(
    emoji: String,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit,
    background: Color = Color.DarkGray.copy(alpha = 0.85f)
) {
    val bg = if (isActive) background else MaterialTheme.colorScheme.errorContainer
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bg,
        modifier = Modifier
            .size(56.dp)
            .semantics { this.contentDescription = contentDescription }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(text = emoji, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * Attaches [videoTrack] to a [SurfaceViewRenderer] wrapped in Compose's AndroidView, handling
 * init/addSink/removeSink/release correctly (README section 12 - "handle renderer lifecycle
 * correctly").
 */
@Composable
private fun VideoRendererView(
    eglBaseContext: EglBase.Context,
    videoTrack: VideoTrack?,
    mirror: Boolean,
    modifier: Modifier = Modifier
) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(0.dp)),
        factory = { ctx ->
            WebRtcRenderer.create(ctx, eglBaseContext, mirror).also { renderer = it }
        }
    )

    // Attach/detach the sink whenever the track identity changes (e.g. remote track arrives
    // later, or local track is recreated for a new call).
    DisposableEffect(videoTrack, renderer) {
        val currentRenderer = renderer
        if (currentRenderer != null && videoTrack != null) {
            videoTrack.addSink(currentRenderer)
        }
        onDispose {
            if (currentRenderer != null && videoTrack != null) {
                videoTrack.removeSink(currentRenderer)
            }
        }
    }

    // Release the SurfaceViewRenderer itself exactly once, when this composable leaves.
    DisposableEffect(Unit) {
        onDispose {
            renderer?.let { WebRtcRenderer.release(it) }
            renderer = null
        }
    }
}
