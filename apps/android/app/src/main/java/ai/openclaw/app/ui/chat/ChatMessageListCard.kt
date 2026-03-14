package ai.openclaw.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.openclaw.app.chat.ChatMessage
import ai.openclaw.app.chat.ChatPendingToolCall
import ai.openclaw.app.ui.mobileAccent
import ai.openclaw.app.ui.mobileAccentSoft
import ai.openclaw.app.ui.mobileBorder
import ai.openclaw.app.ui.mobileBorderStrong
import ai.openclaw.app.ui.mobileCallout
import ai.openclaw.app.ui.mobileCaption1
import ai.openclaw.app.ui.mobileHeadline
import ai.openclaw.app.ui.mobileSurface
import ai.openclaw.app.ui.mobileText
import ai.openclaw.app.ui.mobileTextSecondary
import ai.openclaw.app.ui.overlayContainerColor
import kotlinx.coroutines.launch

@Composable
fun ChatMessageListCard(
  sessionKey: String,
  messages: List<ChatMessage>,
  pendingRunCount: Int,
  pendingToolCalls: List<ChatPendingToolCall>,
  streamingAssistantText: String?,
  healthOk: Boolean,
  assistantAvatarUri: String,
  userLabel: String,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  var anchoredToLatest by remember(sessionKey) { mutableStateOf(false) }
  val totalItems =
    messages.size +
      if (pendingToolCalls.isNotEmpty()) 1 else 0 +
      if (pendingRunCount > 0) 1 else 0 +
      if (streamingAssistantText.isNullOrBlank()) 0 else 1
  val shouldStickToBottom by
    remember {
      derivedStateOf {
        val layoutInfo = listState.layoutInfo
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        lastVisible >= layoutInfo.totalItemsCount - 3
      }
    }
  val showJumpToLatest by
    remember {
      derivedStateOf {
        totalItems > 0 && anchoredToLatest && !shouldStickToBottom
      }
    }

  LaunchedEffect(sessionKey, totalItems) {
    if (totalItems <= 0) return@LaunchedEffect
    if (!anchoredToLatest) {
      listState.scrollToItem(totalItems - 1)
      anchoredToLatest = true
      return@LaunchedEffect
    }
    if (shouldStickToBottom) {
      listState.animateScrollToItem(totalItems - 1)
    }
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = mobileSurface.copy(alpha = 0.98f),
    border = BorderStroke(1.dp, mobileBorderStrong.copy(alpha = 0.9f)),
    shadowElevation = 0.dp,
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp),
      ) {
        items(count = messages.size, key = { idx -> messages[idx].id }) { idx ->
          ChatMessageBubble(
            message = messages[idx],
            assistantAvatarUri = assistantAvatarUri,
            userLabel = userLabel,
          )
        }

        if (pendingToolCalls.isNotEmpty()) {
          item(key = "tools") {
            ChatPendingToolsBubble(
              toolCalls = pendingToolCalls,
              assistantAvatarUri = assistantAvatarUri,
            )
          }
        }

        if (pendingRunCount > 0) {
          item(key = "typing") {
            ChatTypingIndicatorBubble(assistantAvatarUri = assistantAvatarUri)
          }
        }

        val stream = streamingAssistantText?.trim()
        if (!stream.isNullOrEmpty()) {
          item(key = "stream") {
            ChatStreamingAssistantBubble(
              text = stream,
              assistantAvatarUri = assistantAvatarUri,
            )
          }
        }
      }

      if (messages.isEmpty() && pendingRunCount == 0 && pendingToolCalls.isEmpty() && streamingAssistantText.isNullOrBlank()) {
        EmptyChatHint(modifier = Modifier.align(Alignment.Center), healthOk = healthOk)
      }

      if (showJumpToLatest) {
        Surface(
          onClick = {
            scope.launch {
              listState.animateScrollToItem((totalItems - 1).coerceAtLeast(0))
            }
          },
          modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
          shape = RoundedCornerShape(999.dp),
          color = mobileAccent,
          border = BorderStroke(1.dp, mobileAccentSoft),
          shadowElevation = 4.dp,
        ) {
          androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Default.ArrowDownward,
              contentDescription = "Jump to latest",
              tint = Color.White,
            )
            Text(
              text = "Latest",
              style = mobileCaption1,
              color = Color.White,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun EmptyChatHint(modifier: Modifier = Modifier, healthOk: Boolean) {
  Surface(
    modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp),
    shape = RoundedCornerShape(20.dp),
    color = overlayContainerColor(),
    border = BorderStroke(1.dp, mobileBorder),
  ) {
    androidx.compose.foundation.layout.Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text("No messages yet", style = mobileHeadline, color = mobileText)
      Text(
        text =
          if (healthOk) {
            "Send the first prompt to start this session."
          } else {
            "Connect the gateway first, then return to chat."
          },
        style = mobileCallout,
        color = mobileTextSecondary,
      )
    }
  }
}
