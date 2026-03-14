package ai.openclaw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.companion.ChannelAccountItem
import ai.openclaw.app.companion.ChannelStatusItem
import ai.openclaw.app.companion.GuidedChannelProvider
import ai.openclaw.app.companion.buildGuidedChannelPatch

private data class ChannelTemplate(
  val label: String,
  val channelId: String,
  val description: String,
  val patchJson: String,
)

private val channelTemplates =
  listOf(
    ChannelTemplate(
      label = "Discord",
      channelId = "discord",
      description = "Bot token + default account.",
      patchJson =
        """
        {
          "enabled": true,
          "groupPolicy": "allowlist",
          "accounts": {
            "default": {
              "enabled": true,
              "token": "PASTE_DISCORD_BOT_TOKEN"
            }
          }
        }
        """.trimIndent(),
    ),
    ChannelTemplate(
      label = "Telegram",
      channelId = "telegram",
      description = "Bot token + allowlisted groups.",
      patchJson =
        """
        {
          "enabled": true,
          "groupPolicy": "allowlist",
          "botToken": "PASTE_TELEGRAM_BOT_TOKEN"
        }
        """.trimIndent(),
    ),
    ChannelTemplate(
      label = "Slack",
      channelId = "slack",
      description = "Socket mode with bot/app tokens.",
      patchJson =
        """
        {
          "enabled": true,
          "mode": "socket",
          "botToken": "xoxb-PASTE_SLACK_BOT_TOKEN",
          "appToken": "xapp-PASTE_SLACK_APP_TOKEN"
        }
        """.trimIndent(),
    ),
    ChannelTemplate(
      label = "WhatsApp",
      channelId = "whatsapp",
      description = "Base account config; QR login still happens on the gateway.",
      patchJson =
        """
        {
          "enabled": true,
          "groupPolicy": "allowlist",
          "accounts": {
            "default": {
              "enabled": true
            }
          }
        }
        """.trimIndent(),
    ),
  )

@Composable
fun OperatorChannelsScreen(
  viewModel: MainViewModel,
  onOpenAdmin: () -> Unit,
) {
  val state by viewModel.companionState.collectAsState()
  val capability = state.capability
  val channelConfig = state.channelConfig

  var selectedProviderName by rememberSaveable { mutableStateOf(GuidedChannelProvider.Discord.name) }
  var guidedEnabled by rememberSaveable { mutableStateOf(true) }
  var guidedGroupPolicy by rememberSaveable { mutableStateOf("allowlist") }
  var guidedAccountId by rememberSaveable { mutableStateOf("default") }
  var guidedBotToken by rememberSaveable { mutableStateOf("") }
  var guidedAppToken by rememberSaveable { mutableStateOf("") }
  var guidedSlackMode by rememberSaveable { mutableStateOf("socket") }
  var editorChannelId by rememberSaveable { mutableStateOf("") }
  var patchJson by rememberSaveable { mutableStateOf("{}") }
  var pendingDeleteChannelId by remember { mutableStateOf<String?>(null) }
  val selectedProvider =
    GuidedChannelProvider.entries.firstOrNull { it.name == selectedProviderName } ?: GuidedChannelProvider.Discord
  val guidedPatch =
    buildGuidedChannelPatch(
      provider = selectedProvider,
      enabled = guidedEnabled,
      groupPolicy = guidedGroupPolicy,
      accountId = guidedAccountId,
      botToken = guidedBotToken,
      appToken = guidedAppToken,
      mode = guidedSlackMode,
    )

  LaunchedEffect(state.channels) {
    if (editorChannelId.isBlank()) {
      editorChannelId = state.channels.firstOrNull()?.id.orEmpty()
    }
  }

  LaunchedEffect(channelConfig.channelId, channelConfig.lastLoadedAtMs) {
    if (channelConfig.channelId.isNotBlank()) {
      editorChannelId = channelConfig.channelId
    }
  }

  if (pendingDeleteChannelId != null) {
    val channelId = pendingDeleteChannelId!!
    AlertDialog(
      onDismissRequest = { pendingDeleteChannelId = null },
      title = { Text("Remove channel config?") },
      text = { Text("`$channelId` will be removed from the gateway config.") },
      confirmButton = {
        TextButton(
          onClick = {
            pendingDeleteChannelId = null
            viewModel.removeChannelConfig(channelId)
          },
        ) {
          Text("Remove")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDeleteChannelId = null }) {
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
        Text("Channels", style = mobileTitle1, color = mobileText)
        Text(
          "Status, hot patches, and provider templates without digging through the ops console.",
          style = mobileCallout,
          color = mobileTextSecondary,
        )
      }
      IconActionButton(
        label = "Probe",
        icon = Icons.Default.Refresh,
        onClick = { viewModel.refreshCompanionChannels(probe = true) },
      )
    }

    NoticeBanner(
      message = state.latestNotice ?: state.channelsError ?: channelConfig.errorText,
      onDismiss = { viewModel.clearCompanionNotice() },
    )

    SectionCard(
      title = "Connect Flow",
      subtitle = "Patch config here, then finish provider auth where the channel requires it.",
    ) {
      Text(
        "Use a template or a partial JSON patch below. For QR/browser-based providers like WhatsApp, save the base config here first, then complete login from the gateway or Control UI.",
        style = mobileBody,
        color = mobileTextSecondary,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionPill(
          modifier = Modifier.weight(1f),
          icon = Icons.Default.Inventory2,
          label = "Ops Console",
          onClick = onOpenAdmin,
        )
        ActionPill(
          modifier = Modifier.weight(1f),
          icon = Icons.Default.Refresh,
          label = "Refresh Status",
          onClick = { viewModel.refreshCompanionChannels(probe = false) },
        )
      }
    }

    SectionCard(
      title = "Guided Setup",
      subtitle = "Apply the common provider fields here, then use the JSON editor only for advanced patches.",
    ) {
      Text(
        "Leave any token field blank to keep the existing secret on the gateway. Guided setup handles the common mobile-friendly fields; the editor below still covers the edge cases.",
        style = mobileBody,
        color = mobileTextSecondary,
      )
      GuidedChoiceRow(
        label = "Provider",
        options = GuidedChannelProvider.entries.map { it.label to it.name },
        selected = selectedProviderName,
        onSelect = {
          selectedProviderName = it
          editorChannelId =
            GuidedChannelProvider.entries.firstOrNull { provider -> provider.name == it }?.channelId ?: editorChannelId
        },
      )
      InfoRow("Target channel", selectedProvider.channelId)
      Text(
        selectedProvider.description,
        style = mobileBody,
        color = mobileTextSecondary,
      )
      GuidedChoiceRow(
        label = "Channel enabled",
        options = listOf("Enabled" to "true", "Disabled" to "false"),
        selected = guidedEnabled.toString(),
        onSelect = { guidedEnabled = it == "true" },
      )
      when (selectedProvider) {
        GuidedChannelProvider.Discord,
        GuidedChannelProvider.Telegram,
        GuidedChannelProvider.WhatsApp ->
          GuidedChoiceRow(
            label = "Group policy",
            options = listOf("Allowlist" to "allowlist", "Blocklist" to "blocklist"),
            selected = guidedGroupPolicy,
            onSelect = { guidedGroupPolicy = it },
          )
        GuidedChannelProvider.Slack -> {
          OutlinedTextField(
            value = guidedSlackMode,
            onValueChange = { guidedSlackMode = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Slack mode", style = mobileCallout) },
            textStyle = mobileBody.copy(color = mobileText),
            placeholder = { Text("socket", color = mobileTextTertiary, style = mobileCallout) },
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            colors = companionOutlinedColors(),
          )
        }
      }
      when (selectedProvider) {
        GuidedChannelProvider.Discord,
        GuidedChannelProvider.WhatsApp ->
          OutlinedTextField(
            value = guidedAccountId,
            onValueChange = { guidedAccountId = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Account ID", style = mobileCallout) },
            textStyle = mobileBody.copy(color = mobileText),
            placeholder = { Text("default", color = mobileTextTertiary, style = mobileCallout) },
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            colors = companionOutlinedColors(),
          )
        GuidedChannelProvider.Telegram,
        GuidedChannelProvider.Slack -> Unit
      }
      when (selectedProvider) {
        GuidedChannelProvider.Discord,
        GuidedChannelProvider.Telegram,
        GuidedChannelProvider.Slack ->
          OutlinedTextField(
            value = guidedBotToken,
            onValueChange = { guidedBotToken = it },
            modifier = Modifier.fillMaxWidth(),
            label = {
              Text(
                when (selectedProvider) {
                  GuidedChannelProvider.Slack -> "Slack bot token"
                  GuidedChannelProvider.Telegram -> "Telegram bot token"
                  else -> "Discord bot token"
                },
                style = mobileCallout,
              )
            },
            textStyle = mobileBody.copy(color = mobileText),
            placeholder = {
              Text(
                if (selectedProvider == GuidedChannelProvider.Slack) {
                  "xoxb-..."
                } else {
                  "Leave blank to keep existing secret"
                },
                color = mobileTextTertiary,
                style = mobileCallout,
              )
            },
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            colors = companionOutlinedColors(),
          )
        GuidedChannelProvider.WhatsApp -> Unit
      }
      if (selectedProvider == GuidedChannelProvider.Slack) {
        OutlinedTextField(
          value = guidedAppToken,
          onValueChange = { guidedAppToken = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Slack app token", style = mobileCallout) },
          textStyle = mobileBody.copy(color = mobileText),
          placeholder = { Text("xapp-... or leave blank to keep existing", color = mobileTextTertiary, style = mobileCallout) },
          shape = RoundedCornerShape(14.dp),
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          colors = companionOutlinedColors(),
        )
      }
      if (capability.canReadConfig) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ChannelPrimaryButton(
            modifier = Modifier.weight(1f),
            label = "Preview Patch",
            icon = Icons.Default.SettingsEthernet,
            enabled = true,
            onClick = {
              editorChannelId = selectedProvider.channelId
              patchJson = guidedPatch.toString()
            },
          )
          ChannelPrimaryButton(
            modifier = Modifier.weight(1f),
            label = if (channelConfig.isLoading && channelConfig.channelId == selectedProvider.channelId) "Loading" else "Load Current",
            icon = Icons.Default.Refresh,
            enabled = !channelConfig.isLoading,
            onClick = {
              editorChannelId = selectedProvider.channelId
              viewModel.loadChannelConfig(selectedProvider.channelId)
            },
          )
        }
      }
      ChannelPrimaryButton(
        label = "Apply Guided Setup",
        icon = Icons.Default.Save,
        modifier = Modifier.fillMaxWidth(),
        enabled = capability.canPatchConfig,
        onClick = {
          editorChannelId = selectedProvider.channelId
          patchJson = guidedPatch.toString()
          viewModel.saveChannelPatch(selectedProvider.channelId, guidedPatch.toString())
        },
      )
      if (selectedProvider == GuidedChannelProvider.WhatsApp) {
        Text(
          "WhatsApp login still finishes on the gateway after this base config is saved. Use the probe or status cards below to confirm the account becomes linked.",
          style = mobileBody,
          color = mobileTextSecondary,
        )
      }
    }

    SectionCard(
      title = "Quick Templates",
      subtitle = "Seed the raw patch editor directly when you want full manual control.",
    ) {
      channelTemplates.chunked(2).forEach { rowTemplates ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          rowTemplates.forEach { template ->
            ActionPill(
              modifier = Modifier.weight(1f),
              icon = Icons.Default.Inventory2,
              label = template.label,
              onClick = {
                editorChannelId = template.channelId
                patchJson = template.patchJson
                selectedProviderName =
                  GuidedChannelProvider.entries.firstOrNull { it.channelId == template.channelId }?.name
                    ?: selectedProviderName
              },
            )
          }
          if (rowTemplates.size == 1) {
            Spacer(modifier = Modifier.weight(1f))
          }
        }
      }
      Text(
        channelTemplates.joinToString(separator = "\n") { "${it.label}: ${it.description}" },
        style = mobileBody,
        color = mobileTextSecondary,
      )
    }

    SectionCard(
      title = "Channel Patch Editor",
      subtitle = "Applies a merge patch to `channels.<id>`. Partial JSON is safer than full exports.",
    ) {
      OutlinedTextField(
        value = editorChannelId,
        onValueChange = { editorChannelId = it },
        modifier = Modifier.fillMaxWidth(),
        textStyle = mobileBody.copy(color = mobileText),
        placeholder = { Text("discord", color = mobileTextTertiary, style = mobileCallout) },
        shape = RoundedCornerShape(14.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        colors = companionOutlinedColors(),
      )
      OutlinedTextField(
        value = patchJson,
        onValueChange = { patchJson = it },
        modifier = Modifier.fillMaxWidth(),
        textStyle = mobileBody.copy(color = mobileText),
        placeholder = { Text("{\n  \"enabled\": true\n}", color = mobileTextTertiary, style = mobileCallout) },
        shape = RoundedCornerShape(14.dp),
        minLines = 8,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        colors = companionOutlinedColors(),
      )
      if (capability.canReadConfig) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ChannelPrimaryButton(
            modifier = Modifier.weight(1f),
            label = if (channelConfig.isLoading && channelConfig.channelId == editorChannelId.trim()) "Loading" else "Load Current",
            icon = Icons.Default.Refresh,
            enabled = editorChannelId.trim().isNotEmpty() && !channelConfig.isLoading,
            onClick = { viewModel.loadChannelConfig(editorChannelId) },
          )
          ChannelPrimaryButton(
            modifier = Modifier.weight(1f),
            label = "Apply Patch",
            icon = Icons.Default.Save,
            enabled = capability.canPatchConfig,
            onClick = { viewModel.saveChannelPatch(editorChannelId, patchJson) },
          )
        }
        ChannelDangerButton(
          label = "Remove Channel",
          icon = Icons.Default.Delete,
          enabled = capability.canPatchConfig && editorChannelId.trim().isNotEmpty(),
          onClick = { pendingDeleteChannelId = editorChannelId.trim() },
        )
      } else {
        Text(
          "This session does not expose config.get/config.patch, so channel edits are unavailable.",
          style = mobileBody,
          color = mobileDanger,
        )
      }
    }

    SectionCard(
      title = "Current Config Snapshot",
      subtitle = channelConfig.channelId.ifBlank { "Load a channel to inspect the current gateway config." },
    ) {
      if (channelConfig.currentConfigJson.isNullOrBlank()) {
        Text(
          if (channelConfig.channelId.isBlank()) {
            "No channel config loaded yet."
          } else if (channelConfig.hasExistingConfig) {
            "Config loaded but empty."
          } else {
            "No existing config for `${channelConfig.channelId}` yet."
          },
          style = mobileBody,
          color = mobileTextSecondary,
        )
      } else {
        if (channelConfig.containsRedactedSecrets) {
          Text(
            "Secrets are redacted in this snapshot. Avoid pasting the full block back unchanged unless you replace every placeholder.",
            style = mobileBody,
            color = mobileWarning,
          )
        }
        channelConfig.baseHash?.let { InfoRow("Base hash", it.take(12) + "...") }
        CodeBlock(channelConfig.currentConfigJson)
      }
    }

    if (!capability.canInspectChannels) {
      SectionCard(title = "Channel status unavailable", subtitle = "Capability gated") {
        Text(
          "This gateway role does not expose `channels.status`, so live status cards are hidden.",
          style = mobileBody,
          color = mobileTextSecondary,
        )
      }
    } else if (state.channels.isEmpty()) {
      SectionCard(title = "No channels", subtitle = "Run a probe or save a patch above.") {
        Text("No channel status has been reported yet.", style = mobileBody, color = mobileTextSecondary)
      }
    } else {
      state.channels.forEach { channel ->
        ChannelStatusCard(
          viewModel = viewModel,
          channel = channel,
          onEditConfig = {
            editorChannelId = channel.id
            viewModel.loadChannelConfig(channel.id)
          },
        )
      }
    }
  }
}

@Composable
private fun ChannelStatusCard(
  viewModel: MainViewModel,
  channel: ChannelStatusItem,
  onEditConfig: () -> Unit,
) {
  SectionCard(
    title = channel.label,
    subtitle = channel.detailLabel ?: channel.id,
  ) {
    InfoRow("Accounts", channel.accounts.size.toString())
    InfoRow("Connected", channel.connectedAccounts.toString())
    InfoRow("Warnings", channel.warningCount.toString())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ActionPill(
        modifier = Modifier.weight(1f),
        icon = Icons.Default.Save,
        label = "Edit Config",
        onClick = onEditConfig,
      )
      ActionPill(
        modifier = Modifier.weight(1f),
        icon = Icons.Default.Refresh,
        label = "Probe",
        onClick = { viewModel.refreshCompanionChannels(probe = true) },
      )
    }
    if (channel.accounts.isEmpty()) {
      Text("No linked accounts on this channel.", style = mobileBody, color = mobileTextSecondary)
    } else {
      channel.accounts.forEach { account ->
        ChannelAccountCard(
          viewModel = viewModel,
          channelId = channel.id,
          account = account,
        )
      }
    }
  }
}

@Composable
private fun ChannelAccountCard(
  viewModel: MainViewModel,
  channelId: String,
  account: ChannelAccountItem,
) {
  SectionCard(
    title = account.name ?: account.accountId,
    subtitle = account.accountId,
  ) {
    InfoRow("Enabled", account.enabled?.toReadableBool() ?: "Unknown")
    InfoRow("Configured", account.configured?.toReadableBool() ?: "Unknown")
    InfoRow("Linked", account.linked?.toReadableBool() ?: "Unknown")
    InfoRow("Connected", account.connected?.toReadableBool() ?: "Unknown")
    InfoRow("Active runs", account.activeRuns?.toString() ?: "0")
    if (!account.lastError.isNullOrBlank()) {
      InfoRow("Last error", account.lastError)
    }
    ActionPill(
      icon = Icons.Default.LinkOff,
      label = "Logout",
      onClick = { viewModel.logoutChannel(channelId = channelId, accountId = account.accountId) },
    )
  }
}

@Composable
private fun ChannelPrimaryButton(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Button(
    onClick = onClick,
    modifier = modifier.fillMaxWidth(),
    enabled = enabled,
    shape = RoundedCornerShape(14.dp),
    colors =
      ButtonDefaults.buttonColors(
        containerColor = mobileAccent,
        contentColor = Color.White,
        disabledContainerColor = mobileBorderStrong,
        disabledContentColor = mobileTextSecondary,
      ),
  ) {
    androidx.compose.material3.Icon(
      imageVector = icon,
      contentDescription = null,
      modifier = Modifier.padding(end = 8.dp),
    )
    Text(label, style = mobileCallout.copy(fontWeight = FontWeight.Bold))
  }
}

@Composable
private fun ChannelDangerButton(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Button(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    enabled = enabled,
    shape = RoundedCornerShape(14.dp),
    colors =
      ButtonDefaults.buttonColors(
        containerColor = mobileDanger,
        contentColor = Color.White,
        disabledContainerColor = mobileBorderStrong,
        disabledContentColor = mobileTextSecondary,
      ),
  ) {
    androidx.compose.material3.Icon(
      imageVector = icon,
      contentDescription = null,
      modifier = Modifier.padding(end = 8.dp),
    )
    Text(label, style = mobileCallout.copy(fontWeight = FontWeight.Bold))
  }
}

private fun Boolean.toReadableBool(): String = if (this) "True" else "False"

@Composable
private fun GuidedChoiceRow(
  label: String,
  options: List<Pair<String, String>>,
  selected: String,
  onSelect: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(label, style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold), color = mobileTextSecondary)
    options.chunked(2).forEach { rowOptions ->
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rowOptions.forEach { option ->
          PanelChip(
            modifier = Modifier.weight(1f),
            label = option.first,
            active = option.second == selected,
            onClick = { onSelect(option.second) },
          )
        }
        if (rowOptions.size == 1) {
          Spacer(modifier = Modifier.weight(1f))
        }
      }
    }
  }
}
