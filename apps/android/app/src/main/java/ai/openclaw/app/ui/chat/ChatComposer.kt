package ai.openclaw.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.app.ui.mobileAccent
import ai.openclaw.app.ui.mobileAccentSoft
import ai.openclaw.app.ui.mobileBorder
import ai.openclaw.app.ui.mobileBorderStrong
import ai.openclaw.app.ui.mobileCallout
import ai.openclaw.app.ui.mobileCaption1
import ai.openclaw.app.ui.mobileSurface
import ai.openclaw.app.ui.mobileSurfaceStrong
import ai.openclaw.app.ui.mobileText
import ai.openclaw.app.ui.mobileTextSecondary
import ai.openclaw.app.ui.mobileTextTertiary
import ai.openclaw.app.ui.mobileWarning

@Composable
fun ChatComposer(
  modifier: Modifier = Modifier,
  compactMode: Boolean,
  healthOk: Boolean,
  thinkingLevel: String,
  pendingRunCount: Int,
  attachments: List<PendingImageAttachment>,
  onPickImages: () -> Unit,
  onRemoveAttachment: (id: String) -> Unit,
  onSetThinkingLevel: (level: String) -> Unit,
  onRefresh: () -> Unit,
  onAbort: () -> Unit,
  onSend: (text: String) -> Unit,
) {
  var input by rememberSaveable { mutableStateOf("") }
  var showThinkingMenu by remember { mutableStateOf(false) }

  val canSend = (input.trim().isNotEmpty() || attachments.isNotEmpty()) && healthOk
  val sendBusy = pendingRunCount > 0

  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (attachments.isNotEmpty()) {
      AttachmentsStrip(attachments = attachments, onRemoveAttachment = onRemoveAttachment)
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(if (compactMode) 6.dp else 8.dp),
      verticalAlignment = Alignment.Bottom,
    ) {
      Surface(
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(if (compactMode) 22.dp else 24.dp),
        color = mobileSurfaceStrong,
        border = BorderStroke(1.dp, mobileBorderStrong),
        shadowElevation = 0.dp,
      ) {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(
                start = if (compactMode) 4.dp else 6.dp,
                end = 8.dp,
                top = if (compactMode) 4.dp else 6.dp,
                bottom = if (compactMode) 4.dp else 6.dp,
              ),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.Bottom,
        ) {
          ComposerMiniAction(
            icon = Icons.Default.AttachFile,
            label = "Attach image",
            onClick = onPickImages,
          )
          OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier =
              Modifier
                .weight(1f)
                .heightIn(min = if (compactMode) 48.dp else 52.dp, max = if (compactMode) 96.dp else 132.dp),
            placeholder = {
              Text(
                text = "Message",
                style = mobileBodyStyle(),
                color = mobileTextTertiary,
              )
            },
            minLines = 1,
            maxLines = 5,
            textStyle = mobileBodyStyle().copy(color = mobileText),
            shape = RoundedCornerShape(18.dp),
            colors = chatTextFieldColors(),
          )
          Box {
            ComposerThinkingButton(
              thinkingLevel = thinkingLevel,
              onClick = { showThinkingMenu = true },
            )
            DropdownMenu(expanded = showThinkingMenu, onDismissRequest = { showThinkingMenu = false }) {
              ThinkingMenuItem("off", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
              ThinkingMenuItem("low", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
              ThinkingMenuItem("medium", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
              ThinkingMenuItem("high", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
            }
          }
        }
      }

      Button(
        onClick = {
          if (sendBusy) {
            onAbort()
          } else {
            val text = input
            input = ""
            onSend(text)
          }
        },
        enabled = if (sendBusy) true else canSend,
        modifier = Modifier.size(if (compactMode) 52.dp else 56.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = if (sendBusy) mobileWarning else mobileAccent,
            contentColor = Color.White,
            disabledContainerColor = mobileSurfaceStrong,
            disabledContentColor = mobileTextTertiary,
          ),
        border =
          BorderStroke(
            1.dp,
            when {
              sendBusy -> mobileWarning
              canSend -> mobileAccent
              else -> mobileBorderStrong
            },
          ),
      ) {
        Icon(
          imageVector = if (sendBusy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
          contentDescription = if (sendBusy) "Abort run" else "Send",
          modifier = Modifier.size(if (compactMode) 19.dp else 20.dp),
        )
      }
    }

    if (!healthOk) {
      Text(
        text = "Gateway is offline. Reconnect in the Connect tab before sending.",
        style = mobileCallout,
        color = mobileWarning,
      )
    }
  }
}

@Composable
private fun ComposerThinkingButton(
  thinkingLevel: String,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    modifier = Modifier.size(34.dp),
    shape = CircleShape,
    color = if (thinkingLevel == "off") mobileSurface else mobileAccentSoft,
    border = BorderStroke(1.dp, if (thinkingLevel == "off") mobileBorderStrong else mobileBorder),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = Icons.Default.Psychology,
        contentDescription = "Select thinking level",
        tint = if (thinkingLevel == "off") mobileTextSecondary else mobileAccent,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

@Composable
private fun ComposerMiniAction(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  enabled: Boolean = true,
  tint: Color = mobileTextSecondary,
  onClick: () -> Unit,
) {
  IconButton(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.size(32.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = label,
      tint = if (enabled) tint else mobileTextTertiary,
      modifier = Modifier.size(18.dp),
    )
  }
}

@Composable
private fun ThinkingMenuItem(
  value: String,
  current: String,
  onSet: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  DropdownMenuItem(
    text = { Text(thinkingLabel(value), style = mobileCallout, color = mobileText) },
    onClick = {
      onSet(value)
      onDismiss()
    },
    trailingIcon = {
      if (value == current.trim().lowercase()) {
        Icon(
          imageVector = Icons.Default.Check,
          contentDescription = null,
          tint = mobileAccent,
        )
      }
    },
  )
}

private fun thinkingLabel(raw: String): String {
  return when (raw.trim().lowercase()) {
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    else -> "Off"
  }
}

@Composable
private fun AttachmentsStrip(
  attachments: List<PendingImageAttachment>,
  onRemoveAttachment: (id: String) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for (att in attachments) {
      AttachmentChip(fileName = att.fileName, onRemove = { onRemoveAttachment(att.id) })
    }
  }
}

@Composable
private fun AttachmentChip(fileName: String, onRemove: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = mobileAccentSoft,
    border = BorderStroke(1.dp, mobileBorderStrong),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = fileName,
        style = mobileCaption1,
        color = mobileText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      IconButton(onClick = onRemove, modifier = Modifier.size(18.dp)) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "Remove attachment",
          tint = mobileTextSecondary,
          modifier = Modifier.size(14.dp),
        )
      }
    }
  }
}

@Composable
private fun chatTextFieldColors() =
  OutlinedTextFieldDefaults.colors(
    focusedContainerColor = mobileSurface,
    unfocusedContainerColor = mobileSurface,
    focusedBorderColor = mobileAccent,
    unfocusedBorderColor = mobileBorderStrong,
    focusedTextColor = mobileText,
    unfocusedTextColor = mobileText,
    cursorColor = mobileAccent,
    focusedPlaceholderColor = mobileTextTertiary,
    unfocusedPlaceholderColor = mobileTextTertiary,
  )

@Composable
private fun mobileBodyStyle() =
  MaterialTheme.typography.bodyMedium.copy(
    fontFamily = ai.openclaw.app.ui.mobileFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    lineHeight = 22.sp,
  )
