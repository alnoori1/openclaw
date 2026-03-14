package ai.openclaw.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.ui.mobileAccent
import ai.openclaw.app.ui.mobileAccentSoft
import ai.openclaw.app.ui.mobileBorder
import ai.openclaw.app.ui.mobileBorderStrong
import ai.openclaw.app.ui.mobileCaption1
import ai.openclaw.app.ui.mobileCaption2
import ai.openclaw.app.ui.mobileText
import ai.openclaw.app.ui.mobileTextSecondary
import ai.openclaw.app.ui.mobileTextTertiary
import ai.openclaw.app.ui.overlayContainerColor

@Composable
fun SessionDropdownBar(
  sessionKey: String,
  sessions: List<ChatSessionEntry>,
  mainSessionKey: String,
  compactMode: Boolean,
  onSelectSession: (String) -> Unit,
  onCreateSession: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val sessionOptions = resolveSessionChoices(sessionKey, sessions, mainSessionKey = mainSessionKey)
  val current =
    sessionOptions.firstOrNull { it.key == sessionKey }
      ?: ChatSessionEntry(key = sessionKey.ifBlank { mainSessionKey.ifBlank { "main" } }, updatedAtMs = null)
  val currentLabel = friendlySessionName(current.displayName ?: current.key)
  val currentKind = if (current.key == mainSessionKey) "Main" else "Thread"
  var expanded by remember(sessionOptions, current.key) { mutableStateOf(false) }

  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(modifier = Modifier.weight(1f)) {
      Surface(
        onClick = { expanded = true },
        shape = RoundedCornerShape(if (compactMode) 16.dp else 18.dp),
        color = overlayContainerColor(),
        border = BorderStroke(1.dp, mobileBorderStrong.copy(alpha = 0.9f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compactMode) 9.dp else 10.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
          ) {
            Text(
              text = currentKind,
              style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold),
              color = mobileTextSecondary,
            )
            Text(
              text = currentLabel,
              style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
              color = mobileText,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = "Select session",
            tint = mobileTextSecondary,
          )
        }
      }

      DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
      ) {
        sessionOptions.forEach { entry ->
          val active = entry.key == current.key
          val label = friendlySessionName(entry.displayName ?: entry.key)
          DropdownMenuItem(
            text = {
              Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                  text = label,
                  style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
                  color = mobileText,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = if (entry.key == mainSessionKey) "Main thread" else "Session thread",
                  style = mobileCaption2,
                  color = mobileTextTertiary,
                )
              }
            },
            onClick = {
              expanded = false
              onSelectSession(entry.key)
            },
            trailingIcon = {
              if (active) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = null,
                  tint = mobileAccent,
                )
              }
            },
          )
        }
      }
    }

    Surface(
      onClick = onCreateSession,
      shape = RoundedCornerShape(if (compactMode) 16.dp else 18.dp),
      color = mobileAccentSoft,
      border = BorderStroke(1.dp, mobileBorder),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 11.dp, vertical = if (compactMode) 10.dp else 11.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Default.Add,
          contentDescription = "New session",
          tint = mobileAccent,
          modifier = Modifier.size(16.dp),
        )
        if (!compactMode) {
          Text(
            text = "New",
            style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
            color = mobileText,
          )
        } else {
          Spacer(modifier = Modifier.width(0.dp))
        }
      }
    }
  }
}
