package com.google.ai.edge.gallery.ui.telephony

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.tts.KokoroModelManager
import com.google.ai.edge.gallery.tts.KokoroModelStatus
import com.google.ai.edge.gallery.tts.KokoroTtsEngine
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.TtsManager
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel

private val DarkBackground = Color(0xFF0D0D1A)
private val AccentPurple = Color(0xFF7C5CFC)
private val AccentPink = Color(0xFFE040FB)
private val EndCallRed = Color(0xFFFF1744)
private val SubtitleGray = Color(0xFFB0B0C0)

@Composable
fun TelephonyCallScreen(
  telephonyViewModel: TelephonyViewModel,
  holdToDictateViewModel: HoldToDictateViewModel,
  llmViewModel: LlmChatViewModel,
  model: Model,
  onSendMessage: (String) -> Unit,
  onEndCall: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by telephonyViewModel.uiState.collectAsState()
  val context = LocalContext.current

  // Ensure Kokoro TTS model is downloaded and engine is swapped in
  LaunchedEffect(Unit) {
    try {
      Log.w("TelephonyCall", "Starting Kokoro model init...")
      KokoroModelManager.ensureModelReady(context)
      Log.w("TelephonyCall", "Model status: ${KokoroModelManager.status.value}")
      if (KokoroModelManager.status.value == KokoroModelStatus.READY &&
        TtsManager.getAvailableVoices().isEmpty()
      ) {
        Log.w("TelephonyCall", "Creating KokoroTtsEngine...")
        val kokoroEngine = KokoroTtsEngine()
        kokoroEngine.init(context)
        Log.w("TelephonyCall", "Engine ready: ${kokoroEngine.isReady()}")
        if (kokoroEngine.isReady()) {
          TtsManager.setEngine(kokoroEngine)
          Log.w("TelephonyCall", "TTS engine set successfully")
        }
      }
    } catch (e: Exception) {
      Log.e("TelephonyCall", "TTS init failed", e)
      telephonyViewModel.setError("TTS init failed: ${e.message}")
    }
  }

  val conversationLoop = remember(model) {
    ConversationLoopController(
      holdToDictateViewModel = holdToDictateViewModel,
      llmViewModel = llmViewModel,
      telephonyViewModel = telephonyViewModel,
      model = model,
      onSendMessage = onSendMessage,
    )
  }

  // Start the call when this screen appears
  LaunchedEffect(Unit) {
    try {
      Log.w("TelephonyCall", "Starting call and conversation loop...")
      telephonyViewModel.startCall()
      conversationLoop.start()
      Log.w("TelephonyCall", "Conversation loop started")
    } catch (e: Exception) {
      Log.e("TelephonyCall", "Call start failed", e)
      telephonyViewModel.setError("Call failed: ${e.message}")
    }
  }

  // Clean up when leaving
  DisposableEffect(Unit) {
    onDispose {
      conversationLoop.stop()
      telephonyViewModel.endCall()
    }
  }

  // Listen for LLM response completion
  val messages = llmViewModel.uiState.collectAsState().value.messagesByModel[model.name]
  val lastAgentMsg = messages?.lastOrNull { it is ChatMessageText && it.side == ChatSide.AGENT }
  val inProgress = llmViewModel.uiState.collectAsState().value.inProgress

  LaunchedEffect(inProgress) {
    if (!inProgress && uiState.phase == CallPhase.PROCESSING && uiState.callActive) {
      conversationLoop.onLlmResponseDone()
    }
  }

  BackHandler {
    conversationLoop.stop()
    onEndCall()
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier
        .fillMaxSize()
        .padding(vertical = 64.dp),
    ) {
      // Call duration timer
      Text(
        text = telephonyViewModel.formatDuration(),
        color = SubtitleGray,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
      )

      // Center section: Avatar + Status
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Animated avatar orb
        MayaAvatar(
          phase = uiState.phase,
          amplitude = uiState.currentAmplitude,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
          text = "Maya",
          color = Color.White,
          fontSize = 28.sp,
          fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Phase status text
        Text(
          text = when (uiState.phase) {
            CallPhase.IDLE -> "Connecting..."
            CallPhase.LISTENING -> "Listening..."
            CallPhase.PROCESSING -> "Thinking..."
            CallPhase.SPEAKING -> "Speaking..."
          },
          color = SubtitleGray,
          fontSize = 14.sp,
        )

        // Show error message if speech recognition failed
        if (uiState.errorMessage != null) {
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = uiState.errorMessage!!,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 32.dp),
            textAlign = TextAlign.Center,
          )
        }

        // Show partial recognized text when listening
        if (uiState.partialRecognizedText.isNotBlank() &&
          (uiState.phase == CallPhase.LISTENING || uiState.phase == CallPhase.PROCESSING)
        ) {
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = uiState.partialRecognizedText,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 32.dp),
          )
        }
      }

      // End call button
      IconButton(
        onClick = {
          conversationLoop.stop()
          onEndCall()
        },
        modifier = Modifier
          .size(72.dp)
          .clip(CircleShape)
          .background(EndCallRed),
        colors = IconButtonDefaults.iconButtonColors(
          containerColor = EndCallRed,
          contentColor = Color.White,
        ),
      ) {
        Icon(
          imageVector = Icons.Rounded.CallEnd,
          contentDescription = "End call",
          modifier = Modifier.size(36.dp),
        )
      }
    }
  }
}

@Composable
private fun MayaAvatar(
  phase: CallPhase,
  amplitude: Int,
  modifier: Modifier = Modifier,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "avatar_pulse")

  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = when (phase) {
      CallPhase.SPEAKING -> 1.15f
      CallPhase.LISTENING -> 1.08f
      else -> 1.03f
    },
    animationSpec = infiniteRepeatable(
      animation = tween(
        durationMillis = when (phase) {
          CallPhase.SPEAKING -> 600
          CallPhase.LISTENING -> 800
          else -> 1500
        },
        easing = LinearEasing,
      ),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "pulse",
  )

  val rotationAngle by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 8000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "rotation",
  )

  // Amplitude-based extra scale
  val ampScale = 1f + (amplitude.toFloat() / 65535f) * 0.15f

  val orbSize = 140.dp

  Box(
    modifier = modifier
      .size((orbSize.value * pulseScale * ampScale).dp)
      .drawBehind {
        val gradient = Brush.radialGradient(
          colors = listOf(
            AccentPurple.copy(alpha = 0.3f),
            AccentPink.copy(alpha = 0.1f),
            Color.Transparent,
          ),
          center = Offset(size.width / 2, size.height / 2),
          radius = size.width * 0.8f,
        )
        drawCircle(brush = gradient)
      },
    contentAlignment = Alignment.Center,
  ) {
    // Inner orb
    Box(
      modifier = Modifier
        .size(orbSize)
        .clip(CircleShape)
        .background(
          Brush.linearGradient(
            colors = listOf(AccentPurple, AccentPink),
          )
        ),
    )
  }
}
