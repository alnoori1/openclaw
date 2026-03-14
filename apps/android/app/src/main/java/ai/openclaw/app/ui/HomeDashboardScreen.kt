package ai.openclaw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.companion.OperatorSessionItem
import java.util.Locale

private enum class SessionActionKind {
  Reset,
  Compact,
  Archive,
}

private data class PendingSessionAction(
  val kind: SessionActionKind,
  val session: OperatorSessionItem,
)

@Composable
fun HomeDashboardScreen(
  viewModel: MainViewModel,
  onOpenSessions: () -> Unit,
  onOpenChat: () -> Unit,
  onOpenVoice: () -> Unit,
  onOpenChannels: () -> Unit,
  onOpenAdmin: () -> Unit,
  onOpenConnect: () -> Unit,
) {
  val state by viewModel.companionState.collectAsState()
  val statusText by viewModel.statusText.collectAsState()
  val serverName by viewModel.serverName.collectAsState()
  val remoteAddress by viewModel.remoteAddress.collectAsState()
  val mainSessionKey by viewModel.mainSessionKey.collectAsState()

  var confirmResetMain by rememberSaveable { mutableStateOf(false) }

  if (confirmResetMain) {
    AlertDialog(
      onDismissRequest = { confirmResetMain = false },
      title = { Text("Reset main session?") },
      text = { Text("This clears the active main session state and starts it fresh.") },
      confirmButton = {
        TextButton(
          onClick = {
            confirmResetMain = false
            viewModel.resetSession(mainSessionKey)
          },
        ) {
          Text("Reset")
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmResetMain = false }) {
          Text("Cancel")
        }
      },
    )
  }

  Column(
    modifier =
      Modifier
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Operator Deck", style = mobileCaption1.copy(fontWeight = FontWeight.Bold), color = mobileAccent)
        Text("Gateway Dashboard", style = mobileTitle1, color = mobileText)
        Text(
          "Live status, quick actions, and a fast read on what the gateway is doing.",
          style = mobileCallout,
          color = mobileTextSecondary,
        )
      }
      IconActionButton(
        label = "Refresh",
        icon = Icons.Default.Refresh,
        onClick = { viewModel.refreshCompanion() },
      )
    }

    NoticeBanner(
      message = state.latestNotice,
      onDismiss = { viewModel.clearCompanionNotice() },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      StatTile(modifier = Modifier.weight(1f), label = "Sessions", value = state.sessions.size.toString(), tone = mobileAccent)
      StatTile(modifier = Modifier.weight(1f), label = "Agents", value = state.agents.size.toString(), tone = mobileSuccess)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      StatTile(
        modifier = Modifier.weight(1f),
        label = "Channels",
        value = state.channels.size.toString(),
        tone = if (state.channels.any { it.warningCount > 0 }) mobileWarning else mobileAccent,
      )
      StatTile(
        modifier = Modifier.weight(1f),
        label = "Pairing",
        value = state.pendingPairs.size.toString(),
        tone = if (state.pendingPairs.isNotEmpty()) mobileWarning else mobileTextSecondary,
      )
    }

    SectionCard(
      title = "Connection",
      subtitle = "Derived from the gateway hello handshake and current transport state.",
    ) {
      InfoRow("Status", statusText)
      InfoRow("Gateway", serverName ?: "Unknown")
      InfoRow("Remote", remoteAddress ?: "Not connected")
      InfoRow("Version", state.capability.serverVersion ?: "Unavailable")
      InfoRow("Role", state.capability.authRole ?: "Unknown")
      if (state.capability.grantedScopes.isNotEmpty()) {
        Text("Granted scopes", style = mobileCaption1, color = mobileTextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          state.capability.grantedScopes.sorted().take(4).forEach { scope ->
            Capsule(text = scope.removePrefix("operator."))
          }
        }
      }
    }

    SectionCard(
      title = "Shortcut Deck",
      subtitle = "The actions you are likely to use most from a phone.",
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionPill(modifier = Modifier.weight(1f), icon = Icons.Default.Route, label = "Sessions", onClick = onOpenSessions)
        ActionPill(modifier = Modifier.weight(1f), icon = Icons.Default.ChatBubble, label = "Chat", onClick = onOpenChat)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionPill(modifier = Modifier.weight(1f), icon = Icons.Default.Mic, label = "Voice", onClick = onOpenVoice)
        ActionPill(modifier = Modifier.weight(1f), icon = Icons.Default.Inventory2, label = "Channels", onClick = onOpenChannels)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionPill(modifier = Modifier.weight(1f), icon = Icons.Default.AdminPanelSettings, label = "Ops", onClick = onOpenAdmin)
        ActionPill(modifier = Modifier.weight(1f), icon = Icons.Default.Link, label = "Connect", onClick = onOpenConnect)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionPill(modifier = Modifier.weight(1f), icon = Icons.Default.Refresh, label = "Reset Main", onClick = { confirmResetMain = true })
      }
    }

    SectionCard(
      title = "Recent Logs",
      subtitle = state.logs.file ?: "Latest gateway tail",
    ) {
      if (state.logs.lines.isEmpty()) {
        Text("No logs available yet.", style = mobileBody, color = mobileTextSecondary)
      } else {
        state.logs.lines.takeLast(6).forEach { line ->
          CodeBlock(line)
        }
      }
    }
  }
}

@Composable
fun OperatorSessionsScreen(
  viewModel: MainViewModel,
  onOpenChat: () -> Unit,
) {
  val state by viewModel.companionState.collectAsState()
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var pendingAction by remember { mutableStateOf<PendingSessionAction?>(null) }

  val filteredSessions =
    remember(state.sessions, searchQuery) {
      val query = searchQuery.trim().lowercase(Locale.ROOT)
      if (query.isEmpty()) {
        state.sessions
      } else {
        state.sessions.filter { session ->
          listOf(session.title, session.key, session.agentId, session.label, session.lastMessagePreview)
            .filterNotNull()
            .any { it.lowercase(Locale.ROOT).contains(query) }
        }
      }
    }

  if (pendingAction != null) {
    val action = pendingAction!!
    AlertDialog(
      onDismissRequest = { pendingAction = null },
      title = {
        Text(
          when (action.kind) {
            SessionActionKind.Reset -> "Reset session?"
            SessionActionKind.Compact -> "Compact session?"
            SessionActionKind.Archive -> "Archive session?"
          },
        )
      },
      text = {
        Text(
          when (action.kind) {
            SessionActionKind.Reset -> "This clears the working state for `${action.session.key}`."
            SessionActionKind.Compact -> "This asks the gateway to compact `${action.session.key}`."
            SessionActionKind.Archive -> "This removes `${action.session.key}` from the active list without deleting its transcript."
          },
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            when (action.kind) {
              SessionActionKind.Reset -> viewModel.resetSession(action.session.key)
              SessionActionKind.Compact -> viewModel.compactSession(action.session.key)
              SessionActionKind.Archive -> viewModel.archiveSession(action.session.key)
            }
            pendingAction = null
          },
        ) {
          Text("Confirm")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingAction = null }) {
          Text("Cancel")
        }
      },
    )
  }

  Column(
    modifier =
      Modifier
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Sessions", style = mobileTitle1, color = mobileText)
        Text(
          "Search, inspect, and act on session state without dropping into raw RPC.",
          style = mobileCallout,
          color = mobileTextSecondary,
        )
      }
      IconActionButton(
        label = "Refresh",
        icon = Icons.Default.Refresh,
        onClick = { viewModel.refreshCompanionSessions() },
      )
    }

    NoticeBanner(
      message = state.sessionsError ?: state.latestNotice,
      onDismiss = { viewModel.clearCompanionNotice() },
    )

    OutlinedTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(14.dp),
      textStyle = mobileBody.copy(color = mobileText),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
      placeholder = { Text("Search title, key, agent, label, or preview", color = mobileTextTertiary, style = mobileCallout) },
      colors = companionOutlinedColors(),
    )

    if (filteredSessions.isEmpty()) {
      SectionCard(title = "No sessions", subtitle = "The current filter did not match anything.") {
        Text("Try refreshing or clearing the search.", style = mobileBody, color = mobileTextSecondary)
      }
    } else {
      filteredSessions.forEach { session ->
        SessionCard(
          session = session,
          onOpenChat = {
            viewModel.openSessionInChat(session)
            onOpenChat()
          },
          onReset = { pendingAction = PendingSessionAction(SessionActionKind.Reset, session) },
          onCompact = { pendingAction = PendingSessionAction(SessionActionKind.Compact, session) },
          onArchive = { pendingAction = PendingSessionAction(SessionActionKind.Archive, session) },
        )
      }
    }
  }
}

@Composable
private fun SessionCard(
  session: OperatorSessionItem,
  onOpenChat: () -> Unit,
  onReset: () -> Unit,
  onCompact: () -> Unit,
  onArchive: () -> Unit,
) {
  SectionCard(title = session.title, subtitle = session.key) {
    InfoRow("Agent", session.agentId ?: "Unknown")
    InfoRow("Label", session.label ?: "None")
    InfoRow("Model", session.model ?: "Default")
    InfoRow("Updated", formatRelativeTimestamp(session.updatedAtMs))
    if (!session.lastMessagePreview.isNullOrBlank()) {
      Text("Last message", style = mobileCaption1, color = mobileTextSecondary)
      Text(session.lastMessagePreview, style = mobileBody, color = mobileText)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ActionPill(modifier = Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.Launch, label = "Chat", onClick = onOpenChat)
      ActionPill(modifier = Modifier.weight(1f), icon = Icons.Default.Refresh, label = "Reset", onClick = onReset)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ActionPill(modifier = Modifier.weight(1f), icon = Icons.Default.Inventory2, label = "Compact", onClick = onCompact)
      ActionPill(modifier = Modifier.weight(1f), icon = Icons.Default.Delete, label = "Archive", onClick = onArchive)
    }
  }
}
