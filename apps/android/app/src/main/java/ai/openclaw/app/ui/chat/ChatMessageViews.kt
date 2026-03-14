package ai.openclaw.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.app.chat.ChatMessage
import ai.openclaw.app.chat.ChatMessageContent
import ai.openclaw.app.chat.ChatPendingToolCall
import ai.openclaw.app.tools.ToolDisplayRegistry
import ai.openclaw.app.ui.AssistantAvatar
import ai.openclaw.app.ui.UserAvatar
import ai.openclaw.app.ui.mobileAccent
import ai.openclaw.app.ui.mobileAccentSoft
import ai.openclaw.app.ui.mobileBorder
import ai.openclaw.app.ui.mobileBorderStrong
import ai.openclaw.app.ui.mobileCallout
import ai.openclaw.app.ui.mobileCaption1
import ai.openclaw.app.ui.mobileCaption2
import ai.openclaw.app.ui.mobileCodeBg
import ai.openclaw.app.ui.mobileCodeText
import ai.openclaw.app.ui.mobileHeadline
import ai.openclaw.app.ui.mobileSurface
import ai.openclaw.app.ui.mobileText
import ai.openclaw.app.ui.mobileTextSecondary
import ai.openclaw.app.ui.mobileWarning
import ai.openclaw.app.ui.mobileWarningSoft
import java.text.DateFormat
import java.util.Locale

private data class ChatBubbleStyle(
  val alignEnd: Boolean,
  val containerColor: Color,
  val borderColor: Color,
  val roleColor: Color,
)

@Composable
fun ChatMessageBubble(
  message: ChatMessage,
  assistantAvatarUri: String,
  userLabel: String,
) {
  val role = message.role.trim().lowercase(Locale.US)
  val style = bubbleStyle(role)
  val displayableContent =
    message.content.filter { part ->
      when (part.type) {
        "text" -> !part.text.isNullOrBlank()
        else -> part.base64 != null
      }
    }

  if (displayableContent.isEmpty()) return

  ChatBubbleContainer(
    style = style,
    roleLabel = roleLabel(role),
    timestampMs = message.timestampMs,
    assistantAvatarUri = assistantAvatarUri,
    userLabel = userLabel,
    showHeader = role == "system",
  ) {
    ChatMessageBody(content = displayableContent, textColor = mobileText)
  }
}

@Composable
private fun ChatBubbleContainer(
  style: ChatBubbleStyle,
  roleLabel: String,
  timestampMs: Long?,
  assistantAvatarUri: String,
  userLabel: String,
  showHeader: Boolean = true,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = if (style.alignEnd) Arrangement.End else Arrangement.Start,
    verticalAlignment = Alignment.Top,
  ) {
    if (!style.alignEnd) {
      AssistantAvatar(
        avatarUri = assistantAvatarUri,
        size = 30.dp,
        modifier = Modifier.padding(top = 4.dp, end = 8.dp),
      )
    }

    Surface(
      shape = RoundedCornerShape(20.dp),
      border = BorderStroke(1.dp, style.borderColor),
      color = style.containerColor,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      modifier = Modifier.fillMaxWidth(0.92f),
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        if (showHeader) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = roleLabel,
              style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
              color = style.roleColor,
            )
            timestampMs?.let { timestamp ->
              Text(
                text = rememberFormattedTime(timestamp),
                style = mobileCaption2,
                color = mobileTextSecondary,
              )
            }
          }
        }
        content()
        if (!showHeader && timestampMs != null) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
          ) {
            Text(
              text = rememberFormattedTime(timestampMs),
              style = mobileCaption2,
              color = mobileTextSecondary,
            )
          }
        }
      }
    }

    if (style.alignEnd) {
      UserAvatar(
        label = userLabel,
        size = 30.dp,
        modifier = Modifier.padding(top = 4.dp, start = 8.dp),
      )
    }
  }
}

@Composable
private fun ChatMessageBody(content: List<ChatMessageContent>, textColor: Color) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    for (part in content) {
      when (part.type) {
        "text" -> {
          val text = part.text ?: continue
          ChatMarkdown(text = text, textColor = textColor)
        }
        else -> {
          val b64 = part.base64 ?: continue
          ChatBase64Image(base64 = b64, mimeType = part.mimeType)
        }
      }
    }
  }
}

@Composable
fun ChatTypingIndicatorBubble(assistantAvatarUri: String) {
  ChatBubbleContainer(
    style = bubbleStyle("assistant"),
    roleLabel = roleLabel("assistant"),
    timestampMs = null,
    assistantAvatarUri = assistantAvatarUri,
    userLabel = "You",
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      DotPulse(color = mobileTextSecondary)
      Text("Working...", style = mobileCallout, color = mobileTextSecondary)
    }
  }
}

@Composable
fun ChatPendingToolsBubble(
  toolCalls: List<ChatPendingToolCall>,
  assistantAvatarUri: String,
) {
  val context = LocalContext.current
  val summary =
    remember(toolCalls, context) {
      toolCalls
        .take(2)
        .map { ToolDisplayRegistry.resolve(context, it.name, it.args).label }
        .joinToString(", ")
    }

  ChatBubbleContainer(
    style = bubbleStyle("assistant"),
    roleLabel = "WORKING",
    timestampMs = toolCalls.maxOfOrNull { it.startedAtMs },
    assistantAvatarUri = assistantAvatarUri,
    userLabel = "You",
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("Working in the background...", style = mobileCallout, color = mobileTextSecondary)
      if (summary.isNotBlank()) {
        Text(
          text = summary + if (toolCalls.size > 2) " +${toolCalls.size - 2} more" else "",
          style = mobileCaption1,
          color = mobileTextSecondary,
        )
      }
    }
  }
}

@Composable
fun ChatStreamingAssistantBubble(
  text: String,
  assistantAvatarUri: String,
) {
  ChatBubbleContainer(
    style = bubbleStyle("assistant").copy(borderColor = mobileAccent),
    roleLabel = "ASSISTANT",
    timestampMs = null,
    assistantAvatarUri = assistantAvatarUri,
    userLabel = "You",
  ) {
    ChatMarkdown(text = text, textColor = mobileText)
  }
}

private fun bubbleStyle(role: String): ChatBubbleStyle {
  return when (role) {
    "user" ->
      ChatBubbleStyle(
        alignEnd = true,
        containerColor = mobileAccentSoft,
        borderColor = mobileAccent.copy(alpha = 0.35f),
        roleColor = mobileAccent,
      )

    "system" ->
      ChatBubbleStyle(
        alignEnd = false,
        containerColor = mobileWarningSoft,
        borderColor = mobileWarning.copy(alpha = 0.45f),
        roleColor = mobileWarning,
      )

    else ->
      ChatBubbleStyle(
        alignEnd = false,
        containerColor = mobileSurface,
        borderColor = mobileBorderStrong,
        roleColor = mobileTextSecondary,
      )
  }
}

private fun roleLabel(role: String): String {
  return when (role) {
    "user" -> "U"
    "system" -> "SYSTEM"
    else -> "ASSISTANT"
  }
}

@Composable
private fun ChatBase64Image(base64: String, mimeType: String?) {
  val imageState = rememberBase64ImageState(base64)
  val image = imageState.image

  if (image != null) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, mobileBorder),
      color = Color.Transparent,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Image(
        bitmap = image,
        contentDescription = mimeType ?: "attachment",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  } else if (imageState.failed) {
    Text("Unsupported attachment", style = mobileCaption1, color = mobileTextSecondary)
  }
}

@Composable
private fun DotPulse(color: Color) {
  Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
    PulseDot(alpha = 0.38f, color = color)
    PulseDot(alpha = 0.62f, color = color)
    PulseDot(alpha = 0.90f, color = color)
  }
}

@Composable
private fun PulseDot(alpha: Float, color: Color) {
  Surface(
    modifier = Modifier.size(6.dp).alpha(alpha),
    shape = CircleShape,
    color = color,
  ) {}
}

@Composable
fun ChatCodeBlock(code: String, language: String?) {
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = mobileCodeBg,
    border = BorderStroke(1.dp, Color(0xFF2B2E35)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (!language.isNullOrBlank()) {
        Text(
          text = language.uppercase(Locale.US),
          style = mobileCaption2.copy(letterSpacing = 0.4.sp),
          color = mobileTextSecondary,
        )
      }
      Text(
        text = code.trimEnd(),
        fontFamily = FontFamily.Monospace,
        style = mobileCallout,
        color = mobileCodeText,
      )
    }
  }
}

@Composable
private fun rememberFormattedTime(timestampMs: Long): String {
  return remember(timestampMs) {
    DateFormat.getTimeInstance(DateFormat.SHORT).format(timestampMs)
  }
}
