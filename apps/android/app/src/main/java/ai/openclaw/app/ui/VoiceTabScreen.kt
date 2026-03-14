package ai.openclaw.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.VoiceInputMode
import ai.openclaw.app.ui.chat.SessionDropdownBar
import ai.openclaw.app.voice.VoiceConversationEntry
import ai.openclaw.app.voice.VoiceConversationRole
import kotlin.math.max

@Composable
fun VoiceTabScreen(viewModel: MainViewModel) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val activity = remember(context) { context.findActivity() }
  val listState = rememberLazyListState()

  val assistantAvatarUri by viewModel.assistantAvatarUri.collectAsState()
  val chatSessionKey by viewModel.chatSessionKey.collectAsState()
  val mainSessionKey by viewModel.mainSessionKey.collectAsState()
  val chatSessions by viewModel.chatSessions.collectAsState()
  val voiceInputMode by viewModel.voiceInputMode.collectAsState()
  val voiceThinkingLevel by viewModel.voiceThinkingLevel.collectAsState()
  val micEnabled by viewModel.micEnabled.collectAsState()
  val micCooldown by viewModel.micCooldown.collectAsState()
  val micStatusText by viewModel.micStatusText.collectAsState()
  val speakerEnabled by viewModel.speakerEnabled.collectAsState()
  val micLiveTranscript by viewModel.micLiveTranscript.collectAsState()
  val micConversation by viewModel.micConversation.collectAsState()
  val micInputLevel by viewModel.micInputLevel.collectAsState()
  val micAssistantPlaybackActive by viewModel.micAssistantPlaybackActive.collectAsState()
  val voiceTurnDiagnostics by viewModel.voiceTurnDiagnostics.collectAsState()

  var hasMicPermission by remember { mutableStateOf(context.hasRecordAudioPermission()) }
  var activateMicAfterPermission by remember { mutableStateOf(false) }

  DisposableEffect(lifecycleOwner, context) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          hasMicPermission = context.hasRecordAudioPermission()
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      viewModel.setVoiceScreenActive(false)
    }
  }

  val requestMicPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasMicPermission = granted
      if (granted && activateMicAfterPermission) {
        viewModel.setMicEnabled(true)
      }
      activateMicAfterPermission = false
    }

  fun requestMicrophone(startAfterGrant: Boolean) {
    activateMicAfterPermission = startAfterGrant
    requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
  }

  fun selectVoiceMode(mode: VoiceInputMode) {
    if (voiceInputMode != mode) {
      viewModel.setMicEnabled(false)
      viewModel.setVoiceInputMode(mode)
    }
  }

  LaunchedEffect(micConversation.size, micLiveTranscript) {
    val total = micConversation.size + if (micLiveTranscript.isNullOrBlank()) 0 else 1
    if (total > 0) {
      listState.animateScrollToItem(total - 1)
    }
  }

  LaunchedEffect(Unit) {
    viewModel.refreshChatSessions(limit = 200)
  }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(mobileBackgroundGradient)
        .imePadding()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        .padding(horizontal = 18.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(18.dp),
      color = overlayContainerColor(),
      border = BorderStroke(1.dp, mobileBorderStrong.copy(alpha = 0.9f)),
      shadowElevation = 0.dp,
    ) {
      SessionDropdownBar(
        sessionKey = chatSessionKey.ifBlank { mainSessionKey.ifBlank { "main" } },
        sessions = chatSessions,
        mainSessionKey = mainSessionKey.ifBlank { "main" },
        compactMode = false,
        onSelectSession = { key -> viewModel.switchChatSession(key) },
        onCreateSession = {
          viewModel.setMicEnabled(false)
          viewModel.createChatSession()
        },
        modifier = Modifier.padding(10.dp),
      )
    }

    Surface(
      modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
      shape = RoundedCornerShape(24.dp),
      color = overlayContainerColor(),
      border = BorderStroke(1.dp, mobileBorderStrong.copy(alpha = 0.9f)),
      shadowElevation = 0.dp,
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (micConversation.isEmpty()) {
          item {
            Box(
              modifier = Modifier.fillParentMaxHeight().fillMaxWidth(),
              contentAlignment = Alignment.Center,
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Surface(
                  modifier = Modifier.size(66.dp),
                  shape = CircleShape,
                  color = mobileAccentSoft,
                  border = BorderStroke(1.dp, mobileAccent.copy(alpha = 0.28f)),
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector = Icons.Default.GraphicEq,
                      contentDescription = null,
                    modifier = Modifier.size(26.dp),
                      tint = mobileAccent,
                    )
                  }
                }
                Text(
                  text = if (voiceInputMode == VoiceInputMode.Live) "Tap the voice control to begin" else "Hold the voice control to talk",
                  style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                  color = mobileText,
                )
                Text(
                  text = if (voiceInputMode == VoiceInputMode.Live) "Live mode sends each pause automatically." else "Push to Talk only listens while your finger is down.",
                  style = mobileCaption1,
                  color = mobileTextSecondary,
                  textAlign = TextAlign.Center,
                )
              }
            }
          }
        }

        items(items = micConversation, key = { it.id }) { entry ->
          VoiceTurnBubble(
            entry = entry,
            assistantAvatarUri = assistantAvatarUri,
            userLabel = "You",
          )
        }

        if (!micLiveTranscript.isNullOrBlank()) {
          item(key = "live-transcript") {
            VoiceLiveTranscriptCard(text = micLiveTranscript!!.trim())
          }
        }

      }
    }

    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(18.dp),
      color = overlayContainerColor(),
      border = BorderStroke(1.dp, mobileBorderStrong.copy(alpha = 0.9f)),
      shadowElevation = 0.dp,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          VoiceMiniActionButton(
            icon = if (speakerEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
            active = speakerEnabled,
            tint = if (speakerEnabled) mobileAccent else mobileDanger,
            onClick = { viewModel.setSpeakerEnabled(!speakerEnabled) },
            contentDescription = if (speakerEnabled) "Mute assistant playback" else "Enable assistant playback",
          )
          VoiceThinkingButton(
            voiceThinkingLevel = voiceThinkingLevel,
            onToggleThinking = {
              viewModel.setVoiceThinkingLevel(
                if (voiceThinkingLevel.equals("off", ignoreCase = true)) {
                  "low"
                } else {
                  "off"
                },
              )
            },
          )
          Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
          ) {
            VoiceControlButton(
              voiceInputMode = voiceInputMode,
              micEnabled = micEnabled,
              micCooldown = micCooldown,
              assistantPlaybackActive = micAssistantPlaybackActive,
              hasMicPermission = hasMicPermission,
              micInputLevel = micInputLevel,
              onToggleLive = {
                if (micCooldown) return@VoiceControlButton
                if (it) {
                  if (hasMicPermission) {
                    viewModel.setMicEnabled(true)
                  } else {
                    requestMicrophone(startAfterGrant = true)
                  }
                } else {
                  viewModel.setMicEnabled(false)
                }
              },
              onInterruptPlayback = { viewModel.setMicEnabled(true) },
              onPushToTalkRequestPermission = { requestMicrophone(startAfterGrant = false) },
              onPushToTalkPressStart = { viewModel.startPushToTalkCapture() },
              onPushToTalkRelease = { viewModel.finishPushToTalkCaptureAndSend() },
              onPushToTalkCancel = { viewModel.cancelPushToTalkCapture() },
            )
          }
          VoiceMiniActionButton(
            icon = Icons.Default.Stop,
            active = false,
            tint = mobileDanger,
            onClick = { viewModel.stopVoiceInteraction() },
            contentDescription = "Stop voice interaction",
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        )
        {
          VoiceModeChip(
            label = "Live",
            selected = voiceInputMode == VoiceInputMode.Live,
            modifier = Modifier.weight(1f),
            onClick = { selectVoiceMode(VoiceInputMode.Live) },
          )
          VoiceModeChip(
            label = "Push to Talk",
            selected = voiceInputMode == VoiceInputMode.PushToTalk,
            modifier = Modifier.weight(1f),
            onClick = { selectVoiceMode(VoiceInputMode.PushToTalk) },
          )
        }

        Text(
          text = voiceTurnDiagnostics?.statusLine() ?: micStatusText,
          style = mobileCaption1,
          color = mobileTextSecondary,
          textAlign = TextAlign.Center,
          maxLines = 1,
        )

        if (!hasMicPermission) {
          val showRationale =
            if (activity == null) {
              false
            } else {
              ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            }
          Text(
            text =
              if (showRationale) {
                "Microphone permission is required for voice mode."
              } else {
                "Microphone access is blocked. Open app settings to enable it."
              },
            style = mobileCaption1,
            color = mobileWarning,
            textAlign = TextAlign.Center,
          )
          Button(
            onClick = { openAppSettings(context) },
            shape = RoundedCornerShape(14.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = mobileSurfaceStrong,
                contentColor = mobileText,
              ),
          ) {
            Text("Open settings", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
          }
        }
      }
    }
  }
}

@Composable
private fun VoiceModeChip(
  label: String,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  Surface(
    modifier = modifier,
    onClick = onClick,
    shape = RoundedCornerShape(14.dp),
    color = if (selected) mobileAccent else mobileSurface,
    border = BorderStroke(1.dp, if (selected) mobileAccent else mobileBorderStrong),
    shadowElevation = 0.dp,
  ) {
    Text(
      text = label,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
      style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
      color = if (selected) Color.White else mobileText,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun VoiceThinkingButton(
  voiceThinkingLevel: String,
  onToggleThinking: () -> Unit,
) {
  val thinkingEnabled = !voiceThinkingLevel.equals("off", ignoreCase = true)
  Surface(
    onClick = onToggleThinking,
    modifier = Modifier.size(42.dp),
    shape = CircleShape,
    color = if (thinkingEnabled) mobileWarningSoft else mobileSurface,
    border = BorderStroke(1.dp, if (thinkingEnabled) mobileWarning.copy(alpha = 0.35f) else mobileBorderStrong),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = Icons.Default.Psychology,
        contentDescription = if (thinkingEnabled) "Voice thinking on" else "Voice thinking off",
        tint = if (thinkingEnabled) mobileWarning else mobileTextSecondary,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

@Composable
private fun VoiceMiniActionButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  active: Boolean,
  tint: Color,
  onClick: () -> Unit,
  contentDescription: String,
) {
  Surface(
    onClick = onClick,
    modifier = Modifier.size(42.dp),
    shape = CircleShape,
    color = if (active) mobileAccentSoft else mobileSurface,
    border = BorderStroke(1.dp, mobileBorderStrong),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

@Composable
private fun VoiceControlButton(
  voiceInputMode: VoiceInputMode,
  micEnabled: Boolean,
  micCooldown: Boolean,
  assistantPlaybackActive: Boolean,
  hasMicPermission: Boolean,
  micInputLevel: Float,
  onToggleLive: (Boolean) -> Unit,
  onInterruptPlayback: () -> Unit,
  onPushToTalkRequestPermission: () -> Unit,
  onPushToTalkPressStart: () -> Unit,
  onPushToTalkRelease: () -> Unit,
  onPushToTalkCancel: () -> Unit,
) {
  val ringScale by animateFloatAsState(targetValue = if (micEnabled) 1f + 0.26f * max(micInputLevel, 0.12f) else 0.82f, label = "voiceRing")
  val ringAlpha by animateFloatAsState(targetValue = if (micEnabled) 0.24f + 0.18f * max(micInputLevel, 0.08f) else 0.12f, label = "voiceRingAlpha")
  val controlColor by animateColorAsState(
    targetValue =
      when {
        micCooldown -> mobileTextTertiary
        micEnabled -> mobileDanger
        voiceInputMode == VoiceInputMode.PushToTalk -> mobileWarning
        else -> mobileAccent
      },
    label = "voiceControlColor",
  )

  val interactionModifier =
    if (voiceInputMode == VoiceInputMode.PushToTalk) {
      Modifier.pointerInput(micCooldown, hasMicPermission) {
        detectTapGestures(
          onPress = {
            if (micCooldown) return@detectTapGestures
            if (!hasMicPermission) {
              onPushToTalkRequestPermission()
              return@detectTapGestures
            }
            onPushToTalkPressStart()
            val released =
              try {
                tryAwaitRelease()
              } catch (_: Throwable) {
                false
              }
            if (released) {
              onPushToTalkRelease()
            } else {
              onPushToTalkCancel()
            }
          },
        )
      }
    } else {
      Modifier.clickable(enabled = !micCooldown) {
        if (assistantPlaybackActive) {
          onInterruptPlayback()
        } else {
          onToggleLive(!micEnabled)
        }
      }
    }

  Box(
    modifier = Modifier.size(78.dp),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      modifier = Modifier.size(60.dp * ringScale).alpha(ringAlpha),
      shape = CircleShape,
      color = controlColor,
    ) {}
    Surface(
      modifier =
        Modifier
          .size(50.dp)
          .then(interactionModifier),
      shape = CircleShape,
      color = controlColor,
      border = BorderStroke(1.dp, controlColor.copy(alpha = 0.45f)),
      shadowElevation = 0.dp,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector =
            when {
              assistantPlaybackActive -> Icons.Default.Mic
              micEnabled -> Icons.Default.MicOff
              voiceInputMode == VoiceInputMode.PushToTalk -> Icons.Default.GraphicEq
              else -> Icons.Default.Mic
            },
          contentDescription = "Voice control",
          modifier = Modifier.size(20.dp),
          tint = Color.White,
        )
      }
    }
  }
}

@Composable
private fun VoiceLiveTranscriptCard(text: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(0.86f),
    shape = RoundedCornerShape(20.dp),
    color = mobileAccentSoft,
    border = BorderStroke(1.dp, mobileAccent.copy(alpha = 0.26f)),
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = "Listening",
        style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
        color = mobileAccent,
      )
      Text(
        text = text,
        style = mobileCallout,
        color = mobileText,
      )
    }
  }
}

@Composable
private fun VoiceTurnBubble(
  entry: VoiceConversationEntry,
  assistantAvatarUri: String,
  userLabel: String,
) {
  val isUser = entry.role == VoiceConversationRole.User
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    verticalAlignment = Alignment.Top,
  ) {
    if (!isUser) {
      AssistantAvatar(
        avatarUri = assistantAvatarUri,
        size = 34.dp,
        modifier = Modifier.padding(top = 4.dp, end = 10.dp),
      )
    }

    Surface(
      modifier = Modifier.fillMaxWidth(0.86f),
      shape = RoundedCornerShape(20.dp),
      color = if (isUser) mobileAccentSoft else mobileSurface,
      border = BorderStroke(1.dp, if (isUser) mobileAccent.copy(alpha = 0.34f) else mobileBorderStrong),
      shadowElevation = 0.dp,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text(
          text = if (isUser) "You" else if (entry.isStreaming) "Assistant live" else "Assistant",
          style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
          color = if (isUser) mobileAccent else if (entry.isStreaming) mobileWarning else mobileTextSecondary,
        )
        Text(
          text = if (entry.isStreaming && entry.text.isBlank()) "Listening for response..." else entry.text,
          style = mobileCallout,
          color = mobileText,
        )
      }
    }

    if (isUser) {
      UserAvatar(
        label = userLabel,
        size = 34.dp,
        modifier = Modifier.padding(top = 4.dp, start = 10.dp),
      )
    }
  }
}

private fun Context.hasRecordAudioPermission(): Boolean {
  return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

private fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

private fun openAppSettings(context: Context) {
  val intent =
    Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.fromParts("package", context.packageName, null),
    )
  context.startActivity(intent)
}
