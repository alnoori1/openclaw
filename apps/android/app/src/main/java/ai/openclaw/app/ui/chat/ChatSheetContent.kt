package ai.openclaw.app.ui.chat

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.chat.OutgoingAttachment
import ai.openclaw.app.ui.mobileBorderStrong
import ai.openclaw.app.ui.mobileCallout
import ai.openclaw.app.ui.mobileCaption1
import ai.openclaw.app.ui.mobileCaption2
import ai.openclaw.app.ui.mobileDanger
import ai.openclaw.app.ui.mobileText
import ai.openclaw.app.ui.mobileTextSecondary
import ai.openclaw.app.ui.overlayContainerColor
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatSheetContent(viewModel: MainViewModel) {
  val messages by viewModel.chatMessages.collectAsState()
  val errorText by viewModel.chatError.collectAsState()
  val pendingRunCount by viewModel.pendingRunCount.collectAsState()
  val healthOk by viewModel.chatHealthOk.collectAsState()
  val sessionKey by viewModel.chatSessionKey.collectAsState()
  val mainSessionKey by viewModel.mainSessionKey.collectAsState()
  val thinkingLevel by viewModel.chatThinkingLevel.collectAsState()
  val streamingAssistantText by viewModel.chatStreamingAssistantText.collectAsState()
  val pendingToolCalls by viewModel.chatPendingToolCalls.collectAsState()
  val sessions by viewModel.chatSessions.collectAsState()
  val assistantAvatarUri by viewModel.assistantAvatarUri.collectAsState()
  val turnDiagnostics by viewModel.chatTurnDiagnostics.collectAsState()

  LaunchedEffect(Unit) {
    viewModel.refreshChatSessions(limit = 200)
    if (messages.isEmpty() && pendingRunCount == 0 && streamingAssistantText.isNullOrBlank()) {
      viewModel.loadChat(sessionKey.ifBlank { mainSessionKey })
    }
  }

  val context = androidx.compose.ui.platform.LocalContext.current
  val resolver = context.contentResolver
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val imeVisible = WindowInsets.ime.getBottom(density) > 0
  val attachments = remember { mutableStateListOf<PendingImageAttachment>() }

  val pickImages =
    rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
      if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
      scope.launch(Dispatchers.IO) {
        val next =
          uris.take(8).mapNotNull { uri ->
            try {
              loadImageAttachment(resolver, uri)
            } catch (_: Throwable) {
              null
            }
          }
        withContext(Dispatchers.Main) {
          attachments.addAll(next)
        }
      }
    }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .imePadding()
        .padding(horizontal = if (imeVisible) 10.dp else 16.dp, vertical = if (imeVisible) 6.dp else 10.dp),
    verticalArrangement = Arrangement.spacedBy(if (imeVisible) 6.dp else 8.dp),
  ) {
    if (!imeVisible) {
      SessionDropdownBar(
        sessionKey = sessionKey,
        sessions = sessions,
        mainSessionKey = mainSessionKey,
        compactMode = false,
        onSelectSession = { key -> viewModel.switchChatSession(key) },
        onCreateSession = { viewModel.createChatSession() },
      )
    }

    if (!errorText.isNullOrBlank()) {
      ChatErrorRail(errorText = errorText!!)
    }

    ChatMessageListCard(
      sessionKey = sessionKey,
      messages = messages,
      pendingRunCount = pendingRunCount,
      pendingToolCalls = pendingToolCalls,
      streamingAssistantText = streamingAssistantText,
      healthOk = healthOk,
      assistantAvatarUri = assistantAvatarUri,
      userLabel = "You",
      modifier = Modifier.weight(1f, fill = true),
    )

    turnDiagnostics?.summaryLine()?.let {
      Text(
        text = turnDiagnostics!!.statusLine(),
        style = mobileCaption1,
        color = mobileTextSecondary,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
    }

    ChatComposer(
      modifier = Modifier.fillMaxWidth(),
      compactMode = imeVisible,
      healthOk = healthOk,
      thinkingLevel = thinkingLevel,
      pendingRunCount = pendingRunCount,
      attachments = attachments,
      onPickImages = { pickImages.launch("image/*") },
      onRemoveAttachment = { id -> attachments.removeAll { it.id == id } },
      onSetThinkingLevel = { level -> viewModel.setChatThinkingLevel(level) },
      onRefresh = {
        viewModel.refreshChat()
        viewModel.refreshChatSessions(limit = 200)
      },
      onAbort = { viewModel.abortChat() },
      onSend = { text ->
        val outgoing =
          attachments.map { att ->
            OutgoingAttachment(
              type = "image",
              mimeType = att.mimeType,
              fileName = att.fileName,
              base64 = att.base64,
            )
          }
        viewModel.sendChat(message = text, thinking = thinkingLevel, attachments = outgoing)
        attachments.clear()
      },
    )
  }
}

@Composable
private fun ChatErrorRail(errorText: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = overlayContainerColor(),
    shape = RoundedCornerShape(16.dp),
    border = BorderStroke(1.dp, mobileDanger.copy(alpha = 0.45f)),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = "CHAT ERROR",
        style = mobileCaption2.copy(letterSpacing = 0.6.sp),
        color = mobileDanger,
      )
      Text(text = errorText, style = mobileCallout, color = mobileText)
    }
  }
}

data class PendingImageAttachment(
  val id: String,
  val fileName: String,
  val mimeType: String,
  val base64: String,
)

private suspend fun loadImageAttachment(resolver: ContentResolver, uri: Uri): PendingImageAttachment {
  val mimeType = resolver.getType(uri) ?: "image/*"
  val fileName = (uri.lastPathSegment ?: "image").substringAfterLast('/')
  val bytes =
    withContext(Dispatchers.IO) {
      resolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        input.copyTo(out)
        out.toByteArray()
      } ?: ByteArray(0)
    }
  if (bytes.isEmpty()) throw IllegalStateException("empty attachment")
  val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
  return PendingImageAttachment(
    id = uri.toString() + "#" + System.currentTimeMillis().toString(),
    fileName = fileName,
    mimeType = mimeType,
    base64 = base64,
  )
}
