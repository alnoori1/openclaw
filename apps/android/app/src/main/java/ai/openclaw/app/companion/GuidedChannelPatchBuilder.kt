package ai.openclaw.app.companion

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class GuidedChannelProvider(
  val label: String,
  val channelId: String,
  val description: String,
) {
  Discord(
    label = "Discord",
    channelId = "discord",
    description = "Bot token + default account for the common bot flow.",
  ),
  Telegram(
    label = "Telegram",
    channelId = "telegram",
    description = "Bot token + group policy for the standard bot flow.",
  ),
  Slack(
    label = "Slack",
    channelId = "slack",
    description = "Socket mode bot/app token pair for Slack workspace automation.",
  ),
  WhatsApp(
    label = "WhatsApp",
    channelId = "whatsapp",
    description = "Base account config before QR login is completed on the gateway.",
  ),
}

internal fun buildGuidedChannelPatch(
  provider: GuidedChannelProvider,
  enabled: Boolean,
  groupPolicy: String,
  accountId: String,
  botToken: String,
  appToken: String,
  mode: String,
): JsonObject =
  when (provider) {
    GuidedChannelProvider.Discord ->
      buildJsonObject {
        put("enabled", enabled)
        put("groupPolicy", groupPolicy.trim().ifEmpty { "allowlist" })
        put(
          "accounts",
          buildJsonObject {
            put(
              accountId.trim().ifEmpty { "default" },
              buildJsonObject {
                put("enabled", enabled)
                putIfNotBlank("token", botToken)
              },
            )
          },
        )
      }
    GuidedChannelProvider.Telegram ->
      buildJsonObject {
        put("enabled", enabled)
        put("groupPolicy", groupPolicy.trim().ifEmpty { "allowlist" })
        putIfNotBlank("botToken", botToken)
      }
    GuidedChannelProvider.Slack ->
      buildJsonObject {
        put("enabled", enabled)
        put("mode", mode.trim().ifEmpty { "socket" })
        putIfNotBlank("botToken", botToken)
        putIfNotBlank("appToken", appToken)
      }
    GuidedChannelProvider.WhatsApp ->
      buildJsonObject {
        put("enabled", enabled)
        put("groupPolicy", groupPolicy.trim().ifEmpty { "allowlist" })
        put(
          "accounts",
          buildJsonObject {
            put(
              accountId.trim().ifEmpty { "default" },
              buildJsonObject {
                put("enabled", enabled)
              },
            )
          },
        )
      }
  }

private fun kotlinx.serialization.json.JsonObjectBuilder.putIfNotBlank(
  key: String,
  value: String,
) {
  value.trim().takeIf(String::isNotEmpty)?.let { put(key, JsonPrimitive(it)) }
}
