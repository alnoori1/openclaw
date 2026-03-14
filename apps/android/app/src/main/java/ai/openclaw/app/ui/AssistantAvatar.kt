package ai.openclaw.app.ui

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AssistantAvatar(
  avatarUri: String,
  size: Dp,
  modifier: Modifier = Modifier,
) {
  val image = rememberAvatarBitmap(avatarUri)
  Surface(
    modifier = modifier.size(size),
    shape = CircleShape,
    color = mobileSurfaceStrong,
    border = BorderStroke(1.dp, mobileBorderStrong),
  ) {
    if (image != null) {
      Image(
        bitmap = image,
        contentDescription = "Assistant avatar",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(
              Brush.linearGradient(
                listOf(
                  mobileAccent.copy(alpha = 0.95f),
                  mobileWarning.copy(alpha = 0.88f),
                ),
              ),
            ),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "OC",
          style = mobileHeadline.copy(fontWeight = FontWeight.Bold),
          color = mobileText,
        )
      }
    }
  }
}

@Composable
fun UserAvatar(
  label: String,
  size: Dp,
  modifier: Modifier = Modifier,
) {
  val initials = remember(label) { initialsFor(label) }
  Surface(
    modifier = modifier.size(size),
    shape = CircleShape,
    color = mobileAccentSoft,
    border = BorderStroke(1.dp, mobileAccent.copy(alpha = 0.35f)),
  ) {
    Box(
      modifier = Modifier.fillMaxSize().clip(CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = initials,
        style = mobileCallout.copy(fontWeight = FontWeight.Bold),
        color = mobileAccent,
      )
    }
  }
}

@Composable
private fun rememberAvatarBitmap(avatarUri: String): ImageBitmap? {
  val context = LocalContext.current
  var image by remember(avatarUri) { mutableStateOf<ImageBitmap?>(null) }

  LaunchedEffect(avatarUri) {
    val uriString = avatarUri.trim()
    image =
      withContext(Dispatchers.IO) {
        if (uriString.isEmpty()) return@withContext null
        runCatching {
          val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(uriString))
          ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.isMutableRequired = false
            decoder.setTargetSampleSize(2)
          }.asImageBitmap()
        }.getOrNull()
      }
  }

  return image
}

private fun initialsFor(label: String): String {
  val cleaned = label.trim()
  if (cleaned.isEmpty()) return "U"
  if (cleaned.equals("you", ignoreCase = true) || cleaned.equals("user", ignoreCase = true)) return "U"
  val parts = cleaned.split(" ").filter { it.isNotBlank() }
  return when {
    parts.size >= 2 -> (parts.first().first().toString() + parts.last().first().toString()).uppercase()
    else -> cleaned.take(2).uppercase()
  }
}
