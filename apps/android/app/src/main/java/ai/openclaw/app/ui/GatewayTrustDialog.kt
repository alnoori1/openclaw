package ai.openclaw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.NodeRuntime
import ai.openclaw.app.gateway.parseGatewayFingerprint

@Composable
fun GatewayTrustDialog(
  viewModel: MainViewModel,
  prompt: NodeRuntime.GatewayTrustPrompt,
) {
  var fingerprintInput by
    rememberSaveable(prompt.endpoint.stableId, prompt.fingerprintSha256, prompt.allowFingerprintOverride) {
      mutableStateOf(prompt.fingerprintSha256.orEmpty())
    }

  val normalizedFingerprint by remember(prompt.allowFingerprintOverride, fingerprintInput, prompt.fingerprintSha256) {
    mutableStateOf(
      parseGatewayFingerprint(
        if (prompt.allowFingerprintOverride) {
          fingerprintInput
        } else {
          prompt.fingerprintSha256
        },
      ),
    )
  }

  AlertDialog(
    onDismissRequest = { viewModel.declineGatewayTrustPrompt() },
    title = { Text("Trust this gateway?") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(prompt.bodyText)
        if (!prompt.fingerprintSha256.isNullOrBlank()) {
          CodeBlock(prompt.fingerprintSha256)
        }
        if (prompt.allowFingerprintOverride) {
          OutlinedTextField(
            value = fingerprintInput,
            onValueChange = { fingerprintInput = it },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            textStyle = mobileBody.copy(color = mobileText),
            placeholder = {
              Text(
                "sha256:001122... or 64 hex chars",
                style = mobileCallout,
                color = mobileTextTertiary,
              )
            },
            colors = companionOutlinedColors(),
          )
          if (normalizedFingerprint == null) {
            Text(
              "Paste the SHA-256 fingerprint with or without colons and optional `sha256:` prefix.",
              style = mobileCaption1,
              color = mobileWarning,
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          viewModel.acceptGatewayTrustPrompt(
            fingerprintOverride = if (prompt.allowFingerprintOverride) fingerprintInput else null,
          )
        },
        enabled = normalizedFingerprint != null,
      ) {
        Text("Trust and continue")
      }
    },
    dismissButton = {
      TextButton(onClick = { viewModel.declineGatewayTrustPrompt() }) {
        Text("Cancel")
      }
    },
  )
}
