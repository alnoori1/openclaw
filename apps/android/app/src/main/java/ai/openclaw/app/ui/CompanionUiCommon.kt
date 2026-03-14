package ai.openclaw.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun NoticeBanner(
  message: String?,
  onDismiss: () -> Unit,
) {
  if (message.isNullOrBlank()) return
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    color = mobileWarningSoft,
    border = BorderStroke(1.dp, Color(0xFFEED8B8)),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(message, modifier = Modifier.weight(1f), style = mobileCallout, color = mobileWarning)
      TextButton(onClick = onDismiss) {
        Text("Dismiss")
      }
    }
  }
}

@Composable
internal fun SectionCard(
  title: String,
  subtitle: String,
  modifier: Modifier = Modifier,
  action: @Composable (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = mobileSurface,
    border = BorderStroke(1.dp, mobileBorder),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      content = {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Top,
        ) {
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = mobileHeadline, color = mobileText)
            Text(subtitle, style = mobileCaption1, color = mobileTextSecondary)
          }
          action?.invoke()
        }
        content()
      },
    )
  }
}

@Composable
internal fun InfoRow(
  label: String,
  value: String,
) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(label, style = mobileCaption1, color = mobileTextSecondary)
    Text(value, style = mobileBody, color = mobileText)
  }
}

@Composable
internal fun StatTile(
  modifier: Modifier = Modifier,
  label: String,
  value: String,
  tone: Color,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(18.dp),
    color = mobileSurface,
    border = BorderStroke(1.dp, mobileBorder),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(label, style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold), color = mobileTextSecondary)
      Text(value, style = mobileTitle1, color = tone)
    }
  }
}

@Composable
internal fun Capsule(text: String) {
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = mobileAccentSoft,
    border = BorderStroke(1.dp, Color(0xFFD5E2FA)),
  ) {
    Text(
      text = text,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      style = mobileCaption2.copy(fontWeight = FontWeight.Bold),
      color = mobileAccent,
    )
  }
}

@Composable
internal fun ActionPill(
  modifier: Modifier = Modifier,
  icon: ImageVector,
  label: String,
  onClick: () -> Unit,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    color = mobileSurface,
    border = BorderStroke(1.dp, mobileBorder),
    onClick = onClick,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      androidx.compose.material3.Icon(icon, contentDescription = null, tint = mobileAccent)
      Text(label, style = mobileCallout.copy(fontWeight = FontWeight.SemiBold), color = mobileText)
    }
  }
}

@Composable
internal fun IconActionButton(
  label: String,
  icon: ImageVector,
  onClick: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = mobileSurface,
    border = BorderStroke(1.dp, mobileBorder),
    onClick = onClick,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      androidx.compose.material3.Icon(icon, contentDescription = null, tint = mobileAccent)
      Text(label, style = mobileCaption1.copy(fontWeight = FontWeight.Bold), color = mobileAccent)
    }
  }
}

@Composable
internal fun PanelChip(
  modifier: Modifier = Modifier,
  label: String,
  active: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    color = if (active) mobileAccentSoft else mobileSurface,
    border = BorderStroke(1.dp, if (active) Color(0xFFD5E2FA) else mobileBorder),
    onClick = onClick,
  ) {
    Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
      Text(
        label,
        style = mobileCaption1.copy(fontWeight = FontWeight.Bold),
        color = if (active) mobileAccent else mobileTextSecondary,
      )
    }
  }
}

@Composable
internal fun CodeBlock(code: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = mobileCodeBg,
  ) {
    Text(
      text = code,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      style = mobileCaption1.copy(fontFamily = FontFamily.Monospace),
      color = mobileCodeText,
    )
  }
}

@Composable
internal fun companionOutlinedColors() =
  OutlinedTextFieldDefaults.colors(
    focusedBorderColor = mobileAccent,
    unfocusedBorderColor = mobileBorderStrong,
    focusedContainerColor = mobileSurface,
    unfocusedContainerColor = mobileSurface,
    cursorColor = mobileAccent,
  )

internal fun formatRelativeTimestamp(value: Long?): String {
  val timestamp = value ?: return "Unknown"
  val diffMs = System.currentTimeMillis() - timestamp
  val diffMinutes = diffMs / 60_000
  return when {
    diffMinutes < 1 -> "just now"
    diffMinutes < 60 -> "${diffMinutes}m ago"
    diffMinutes < 24 * 60 -> "${diffMinutes / 60}h ago"
    else -> "${diffMinutes / (24 * 60)}d ago"
  }
}

internal fun formatAbsoluteTimestamp(value: Long): String {
  return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(value))
}
