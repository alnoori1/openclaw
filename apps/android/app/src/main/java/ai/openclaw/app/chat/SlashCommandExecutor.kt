package ai.openclaw.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class SlashCommandExecutionResult(
  val content: String,
  val refreshChat: Boolean = false,
)

private data class GatewaySessionRow(
  val key: String,
  val spawnedBy: String?,
  val model: String?,
  val modelProvider: String?,
  val thinkingLevel: String?,
  val verboseLevel: String?,
  val fastMode: Boolean?,
  val inputTokens: Long?,
  val outputTokens: Long?,
  val totalTokens: Long?,
  val contextTokens: Long?,
)

private data class SessionsListSnapshot(
  val defaultModel: String?,
  val sessions: List<GatewaySessionRow>,
)

private data class AgentsListSnapshot(
  val defaultId: String?,
  val agents: List<AgentListEntry>,
)

private data class AgentListEntry(
  val id: String,
  val name: String?,
  val identityName: String?,
)

private data class ParsedAgentSessionKey(
  val agentId: String,
)

class SlashCommandExecutor(
  private val json: Json,
  private val request: suspend (method: String, paramsJson: String?, timeoutMs: Long) -> String,
) {
  suspend fun execute(sessionKey: String, command: ParsedSlashCommand): SlashCommandExecutionResult {
    return when (command.command.name) {
      "help" -> executeHelp()
      "status" -> executeStatus()
      "compact" -> executeCompact(sessionKey)
      "model" -> executeModel(sessionKey, command.args)
      "think" -> executeThink(sessionKey, command.args)
      "verbose" -> executeVerbose(sessionKey, command.args)
      "fast" -> executeFast(sessionKey, command.args)
      "usage" -> executeUsage(sessionKey)
      "agents" -> executeAgents()
      "kill" -> executeKill(sessionKey, command.args)
      "focus" -> SlashCommandExecutionResult("Focus mode is not available in Android yet.")
      "export" -> SlashCommandExecutionResult("Export is not available in Android yet.")
      "clear" -> SlashCommandExecutionResult("Chat history cleared.", refreshChat = true)
      else -> SlashCommandExecutionResult("Unsupported slash command `/${command.command.name}`.")
    }
  }

  private fun executeHelp(): SlashCommandExecutionResult {
    val lines =
      buildList {
        add("**Available Commands**")
        add("")
        for (command in slashCommands) {
          val suffix = command.args?.let { " $it" }.orEmpty()
          val localSuffix = if (command.executeLocal) "" else " *(gateway)*"
          add("`/${command.name}$suffix` - ${command.description}$localSuffix")
        }
      }
    return SlashCommandExecutionResult(lines.joinToString("\n"))
  }

  private suspend fun executeStatus(): SlashCommandExecutionResult {
    return try {
      val root = requestObject("health", emptyObject())
      val ok = root["ok"].asBooleanOrNull() ?: true
      val agentCount = root["agents"].asArrayOrNull()?.size ?: 0
      val sessionCount =
        root["sessions"].asObjectOrNull()?.get("count").asLongOrNull()?.toInt()
          ?: root["sessions"].asArrayOrNull()?.size
          ?: 0
      val defaultAgent = root["defaultAgentId"].asStringOrNull()?.trim().orEmpty().ifEmpty { "none" }
      val responseMs = root["durationMs"].asLongOrNull()
      val lines =
        buildList {
          add("**System Status:** ${if (ok) "Healthy" else "Degraded"}")
          add("**Agents:** $agentCount")
          add("**Sessions:** $sessionCount")
          add("**Default Agent:** $defaultAgent")
          if (responseMs != null) add("**Response:** ${responseMs}ms")
        }
      SlashCommandExecutionResult(lines.joinToString("\n"))
    } catch (err: Throwable) {
      SlashCommandExecutionResult("Failed to fetch status: ${err.message ?: err::class.simpleName}")
    }
  }

  private suspend fun executeCompact(sessionKey: String): SlashCommandExecutionResult {
    return try {
      requestObject(
        "sessions.compact",
        buildJsonObject {
          put("key", JsonPrimitive(sessionKey))
        },
      )
      SlashCommandExecutionResult("Context compacted successfully.", refreshChat = true)
    } catch (err: Throwable) {
      SlashCommandExecutionResult("Compaction failed: ${err.message ?: err::class.simpleName}")
    }
  }

  private suspend fun executeModel(sessionKey: String, args: String): SlashCommandExecutionResult {
    val trimmedArgs = args.trim()
    return if (trimmedArgs.isEmpty()) {
      try {
        val sessions = loadSessions()
        val modelsRoot = requestObject("models.list", emptyObject())
        val current = resolveCurrentSession(sessions, sessionKey)
        val currentModel = current?.model ?: sessions.defaultModel ?: "default"
        val available =
          modelsRoot["models"].asArrayOrNull()
            ?.mapNotNull { it.asObjectOrNull()?.get("id").asStringOrNull()?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()
        val lines =
          buildList {
            add("**Current model:** `$currentModel`")
            if (available.isNotEmpty()) {
              val shown = available.take(10).joinToString(", ") { "`$it`" }
              val extra = if (available.size > 10) " +${available.size - 10} more" else ""
              add("**Available:** $shown$extra")
            }
          }
        SlashCommandExecutionResult(lines.joinToString("\n"))
      } catch (err: Throwable) {
        SlashCommandExecutionResult("Failed to get model info: ${err.message ?: err::class.simpleName}")
      }
    } else {
      try {
        requestObject(
          "sessions.patch",
          buildJsonObject {
            put("key", JsonPrimitive(sessionKey))
            put("model", JsonPrimitive(trimmedArgs))
          },
        )
        SlashCommandExecutionResult("Model set to `$trimmedArgs`.", refreshChat = true)
      } catch (err: Throwable) {
        SlashCommandExecutionResult("Failed to set model: ${err.message ?: err::class.simpleName}")
      }
    }
  }

  private suspend fun executeThink(sessionKey: String, args: String): SlashCommandExecutionResult {
    val trimmedArgs = args.trim()
    return if (trimmedArgs.isEmpty()) {
      try {
        val session = resolveCurrentSession(loadSessions(), sessionKey)
        val level = normalizeThinkingLevel(session?.thinkingLevel) ?: "off"
        SlashCommandExecutionResult(
          "Current thinking level: $level.\nOptions: off, minimal, low, medium, high, adaptive, xhigh.",
        )
      } catch (err: Throwable) {
        SlashCommandExecutionResult("Failed to get thinking level: ${err.message ?: err::class.simpleName}")
      }
    } else {
      val normalized = normalizeThinkingLevel(trimmedArgs)
      if (normalized == null) {
        SlashCommandExecutionResult(
          "Unrecognized thinking level \"$trimmedArgs\". Valid levels: off, minimal, low, medium, high, adaptive, xhigh.",
        )
      } else {
        try {
          requestObject(
            "sessions.patch",
            buildJsonObject {
              put("key", JsonPrimitive(sessionKey))
              put("thinkingLevel", JsonPrimitive(normalized))
            },
          )
          SlashCommandExecutionResult("Thinking level set to **$normalized**.", refreshChat = true)
        } catch (err: Throwable) {
          SlashCommandExecutionResult("Failed to set thinking level: ${err.message ?: err::class.simpleName}")
        }
      }
    }
  }

  private suspend fun executeVerbose(sessionKey: String, args: String): SlashCommandExecutionResult {
    val trimmedArgs = args.trim()
    return if (trimmedArgs.isEmpty()) {
      try {
        val session = resolveCurrentSession(loadSessions(), sessionKey)
        val level = normalizeVerboseLevel(session?.verboseLevel) ?: "off"
        SlashCommandExecutionResult("Current verbose level: $level.\nOptions: on, full, off.")
      } catch (err: Throwable) {
        SlashCommandExecutionResult("Failed to get verbose level: ${err.message ?: err::class.simpleName}")
      }
    } else {
      val normalized = normalizeVerboseLevel(trimmedArgs)
      if (normalized == null) {
        SlashCommandExecutionResult("Unrecognized verbose level \"$trimmedArgs\". Valid levels: on, full, off.")
      } else {
        try {
          requestObject(
            "sessions.patch",
            buildJsonObject {
              put("key", JsonPrimitive(sessionKey))
              put("verboseLevel", JsonPrimitive(normalized))
            },
          )
          SlashCommandExecutionResult("Verbose mode set to **$normalized**.", refreshChat = true)
        } catch (err: Throwable) {
          SlashCommandExecutionResult("Failed to set verbose mode: ${err.message ?: err::class.simpleName}")
        }
      }
    }
  }

  private suspend fun executeFast(sessionKey: String, args: String): SlashCommandExecutionResult {
    val trimmedArgs = args.trim().lowercase()
    return if (trimmedArgs.isEmpty() || trimmedArgs == "status") {
      try {
        val session = resolveCurrentSession(loadSessions(), sessionKey)
        SlashCommandExecutionResult(
          "Current fast mode: ${if (session?.fastMode == true) "on" else "off"}.\nOptions: status, on, off.",
        )
      } catch (err: Throwable) {
        SlashCommandExecutionResult("Failed to get fast mode: ${err.message ?: err::class.simpleName}")
      }
    } else if (trimmedArgs != "on" && trimmedArgs != "off") {
      SlashCommandExecutionResult("Unrecognized fast mode \"$trimmedArgs\". Valid levels: status, on, off.")
    } else {
      try {
        requestObject(
          "sessions.patch",
          buildJsonObject {
            put("key", JsonPrimitive(sessionKey))
            put("fastMode", JsonPrimitive(trimmedArgs == "on"))
          },
        )
        SlashCommandExecutionResult(
          if (trimmedArgs == "on") "Fast mode enabled." else "Fast mode disabled.",
          refreshChat = true,
        )
      } catch (err: Throwable) {
        SlashCommandExecutionResult("Failed to set fast mode: ${err.message ?: err::class.simpleName}")
      }
    }
  }

  private suspend fun executeUsage(sessionKey: String): SlashCommandExecutionResult {
    return try {
      val session = resolveCurrentSession(loadSessions(), sessionKey)
      if (session == null) {
        SlashCommandExecutionResult("No active session.")
      } else {
        val input = session.inputTokens ?: 0L
        val output = session.outputTokens ?: 0L
        val total = session.totalTokens ?: (input + output)
        val context = session.contextTokens ?: 0L
        val percentage = if (context > 0L) ((input * 100L) / context).toInt() else null
        val lines =
          buildList {
            add("**Session Usage**")
            add("Input: **${formatTokens(input)}** tokens")
            add("Output: **${formatTokens(output)}** tokens")
            add("Total: **${formatTokens(total)}** tokens")
            if (percentage != null) {
              add("Context: **${percentage}%** of ${formatTokens(context)}")
            }
            session.model?.let { add("Model: `$it`") }
          }
        SlashCommandExecutionResult(lines.joinToString("\n"))
      }
    } catch (err: Throwable) {
      SlashCommandExecutionResult("Failed to get usage: ${err.message ?: err::class.simpleName}")
    }
  }

  private suspend fun executeAgents(): SlashCommandExecutionResult {
    return try {
      val root = requestObject("agents.list", emptyObject())
      val snapshot = parseAgentsList(root)
      if (snapshot.agents.isEmpty()) {
        SlashCommandExecutionResult("No agents configured.")
      } else {
        val lines =
          buildList {
            add("**Agents** (${snapshot.agents.size})")
            add("")
            for (agent in snapshot.agents) {
              val displayName = agent.identityName ?: agent.name ?: agent.id
              val defaultSuffix = if (agent.id == snapshot.defaultId) " *(default)*" else ""
              add("- `${agent.id}` - $displayName$defaultSuffix")
            }
          }
        SlashCommandExecutionResult(lines.joinToString("\n"))
      }
    } catch (err: Throwable) {
      SlashCommandExecutionResult("Failed to list agents: ${err.message ?: err::class.simpleName}")
    }
  }

  private suspend fun executeKill(sessionKey: String, args: String): SlashCommandExecutionResult {
    val target = args.trim()
    if (target.isEmpty()) {
      return SlashCommandExecutionResult("Usage: `/kill <id|all>`")
    }
    return try {
      val sessions = loadSessions().sessions
      val matched = resolveKillTargets(sessions, sessionKey, target)
      if (matched.isEmpty()) {
        return SlashCommandExecutionResult(
          if (target.equals("all", ignoreCase = true)) {
            "No active sub-agent sessions found."
          } else {
            "No matching sub-agent sessions found for `$target`."
          },
        )
      }

      var successCount = 0
      for (key in matched) {
        val response =
          requestObject(
            "chat.abort",
            buildJsonObject {
              put("sessionKey", JsonPrimitive(key))
            },
          )
        if (response["aborted"].asBooleanOrNull() != false) {
          successCount += 1
        }
      }
      if (successCount == 0) {
        return SlashCommandExecutionResult(
          if (target.equals("all", ignoreCase = true)) {
            "No active sub-agent runs to abort."
          } else {
            "No active runs matched `$target`."
          },
        )
      }
      if (target.equals("all", ignoreCase = true)) {
        val label = if (successCount == matched.size) successCount.toString() else "$successCount of ${matched.size}"
        return SlashCommandExecutionResult("Aborted $label sub-agent session${if (successCount == 1) "" else "s"}.")
      }
      val label = if (successCount == matched.size) successCount.toString() else "$successCount of ${matched.size}"
      SlashCommandExecutionResult(
        "Aborted $label matching sub-agent session${if (successCount == 1) "" else "s"} for `$target`.",
      )
    } catch (err: Throwable) {
      SlashCommandExecutionResult("Failed to abort: ${err.message ?: err::class.simpleName}")
    }
  }

  private suspend fun loadSessions(): SessionsListSnapshot {
    val root = requestObject("sessions.list", emptyObject())
    return parseSessionsList(root)
  }

  private suspend fun requestObject(method: String, params: JsonObject?, timeoutMs: Long = 15_000L): JsonObject {
    val response = request(method, params?.toString(), timeoutMs)
    return json.parseToJsonElement(response).asObjectOrNull() ?: JsonObject(emptyMap())
  }

  private fun parseSessionsList(root: JsonObject): SessionsListSnapshot {
    val defaultModel = root["defaults"].asObjectOrNull()?.get("model").asStringOrNull()?.trim()
    val sessions =
      root["sessions"].asArrayOrNull()
        ?.mapNotNull { item ->
          val obj = item.asObjectOrNull() ?: return@mapNotNull null
          val key = obj["key"].asStringOrNull()?.trim().orEmpty()
          if (key.isEmpty()) return@mapNotNull null
          GatewaySessionRow(
            key = key,
            spawnedBy = obj["spawnedBy"].asStringOrNull()?.trim(),
            model = obj["model"].asStringOrNull()?.trim(),
            modelProvider = obj["modelProvider"].asStringOrNull()?.trim(),
            thinkingLevel = obj["thinkingLevel"].asStringOrNull()?.trim(),
            verboseLevel = obj["verboseLevel"].asStringOrNull()?.trim(),
            fastMode = obj["fastMode"].asBooleanOrNull(),
            inputTokens = obj["inputTokens"].asLongOrNull(),
            outputTokens = obj["outputTokens"].asLongOrNull(),
            totalTokens = obj["totalTokens"].asLongOrNull(),
            contextTokens = obj["contextTokens"].asLongOrNull(),
          )
        }
        .orEmpty()
    return SessionsListSnapshot(defaultModel = defaultModel, sessions = sessions)
  }

  private fun parseAgentsList(root: JsonObject): AgentsListSnapshot {
    val defaultId = root["defaultId"].asStringOrNull()?.trim()
    val agents =
      root["agents"].asArrayOrNull()
        ?.mapNotNull { item ->
          val obj = item.asObjectOrNull() ?: return@mapNotNull null
          val id = obj["id"].asStringOrNull()?.trim().orEmpty()
          if (id.isEmpty()) return@mapNotNull null
          AgentListEntry(
            id = id,
            name = obj["name"].asStringOrNull()?.trim(),
            identityName = obj["identity"].asObjectOrNull()?.get("name").asStringOrNull()?.trim(),
          )
        }
        .orEmpty()
    return AgentsListSnapshot(defaultId = defaultId, agents = agents)
  }

  private fun resolveCurrentSession(snapshot: SessionsListSnapshot, sessionKey: String): GatewaySessionRow? {
    val normalizedSessionKey = normalizeSessionKey(sessionKey) ?: return null
    val currentAgentId =
      parseAgentSessionKey(normalizedSessionKey)?.agentId
        ?: if (normalizedSessionKey == DEFAULT_MAIN_KEY) DEFAULT_AGENT_ID else null
    val aliases = resolveEquivalentSessionKeys(normalizedSessionKey, currentAgentId)
    return snapshot.sessions.firstOrNull { aliases.contains(normalizeSessionKey(it.key)) }
  }

  private fun resolveKillTargets(
    sessions: List<GatewaySessionRow>,
    currentSessionKey: String,
    target: String,
  ): List<String> {
    val normalizedTarget = normalizeSessionKey(target) ?: return emptyList()
    val normalizedCurrentSessionKey = normalizeSessionKey(currentSessionKey) ?: return emptyList()
    val currentAgentId =
      parseAgentSessionKey(normalizedCurrentSessionKey)?.agentId
        ?: if (normalizedCurrentSessionKey == DEFAULT_MAIN_KEY) DEFAULT_AGENT_ID else null
    val index = sessions.associateBy { normalizeSessionKey(it.key) }
    val keys = linkedSetOf<String>()
    for (session in sessions) {
      val key = session.key.trim()
      val normalizedKey = normalizeSessionKey(key) ?: continue
      if (!isSubagentSessionKey(normalizedKey)) continue
      val candidateAgentId = parseAgentSessionKey(normalizedKey)?.agentId
      val belongsToCurrent = isWithinCurrentSessionSubtree(normalizedKey, normalizedCurrentSessionKey, index, currentAgentId, candidateAgentId)
      val matches =
        (normalizedTarget == "all" && belongsToCurrent) ||
          (belongsToCurrent && normalizedKey == normalizedTarget) ||
          (belongsToCurrent &&
            ((candidateAgentId ?: "") == normalizedTarget ||
              normalizedKey.endsWith(":subagent:$normalizedTarget") ||
              normalizedKey == "subagent:$normalizedTarget"))
      if (matches) {
        keys += key
      }
    }
    return keys.toList()
  }

  private fun isWithinCurrentSessionSubtree(
    candidateSessionKey: String,
    currentSessionKey: String,
    index: Map<String?, GatewaySessionRow>,
    currentAgentId: String?,
    candidateAgentId: String?,
  ): Boolean {
    if (currentAgentId == null || candidateAgentId != currentAgentId) return false

    val currentAliases = resolveEquivalentSessionKeys(currentSessionKey, currentAgentId)
    val seen = linkedSetOf<String>()
    var parent = normalizeSessionKey(index[candidateSessionKey]?.spawnedBy)
    while (parent != null && seen.add(parent)) {
      if (currentAliases.contains(parent)) return true
      parent = normalizeSessionKey(index[parent]?.spawnedBy)
    }

    return if (isSubagentSessionKey(currentSessionKey)) {
      candidateSessionKey.startsWith("$currentSessionKey:subagent:")
    } else {
      false
    }
  }

  private fun resolveEquivalentSessionKeys(currentSessionKey: String, currentAgentId: String?): Set<String> {
    val aliases = linkedSetOf(currentSessionKey)
    if (currentAgentId == DEFAULT_AGENT_ID) {
      val canonical = "agent:$DEFAULT_AGENT_ID:main"
      if (currentSessionKey == DEFAULT_MAIN_KEY) {
        aliases += canonical
      } else if (currentSessionKey == canonical) {
        aliases += DEFAULT_MAIN_KEY
      }
    }
    return aliases
  }

  private fun parseAgentSessionKey(sessionKey: String): ParsedAgentSessionKey? {
    val parts = sessionKey.trim().lowercase().split(':')
    if (parts.size < 3 || parts[0] != "agent") return null
    val agentId = parts.getOrNull(1)?.trim().orEmpty()
    if (agentId.isEmpty()) return null
    return ParsedAgentSessionKey(agentId = agentId)
  }

  private fun isSubagentSessionKey(sessionKey: String): Boolean = sessionKey.contains(":subagent:")

  private fun normalizeSessionKey(key: String?): String? {
    val normalized = key?.trim()?.lowercase()
    return normalized?.takeIf { it.isNotEmpty() }
  }

  private fun normalizeThinkingLevel(raw: String?): String? {
    return when (raw?.trim()?.lowercase()) {
      "off", "minimal", "low", "medium", "high", "adaptive", "xhigh" -> raw.trim().lowercase()
      else -> null
    }
  }

  private fun normalizeVerboseLevel(raw: String?): String? {
    return when (raw?.trim()?.lowercase()) {
      "on", "off", "full" -> raw.trim().lowercase()
      else -> null
    }
  }

  private fun formatTokens(value: Long): String {
    return when {
      value >= 1_000_000L -> "${formatWithSingleDecimal(value / 1_000_000.0)}M"
      value >= 1_000L -> "${formatWithSingleDecimal(value / 1_000.0)}k"
      else -> value.toString()
    }
  }

  private fun formatWithSingleDecimal(value: Double): String {
    val formatted = "%.1f".format(value)
    return if (formatted.endsWith(".0")) formatted.dropLast(2) else formatted
  }

  private fun emptyObject(): JsonObject = JsonObject(emptyMap())

  private companion object {
    const val DEFAULT_AGENT_ID = "main"
    const val DEFAULT_MAIN_KEY = "main"
  }
}

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonElement?.asStringOrNull(): String? =
  when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
  }

private fun JsonElement?.asLongOrNull(): Long? =
  when (this) {
    is JsonPrimitive -> content.toLongOrNull()
    else -> null
  }

private fun JsonElement?.asBooleanOrNull(): Boolean? =
  when (this) {
    is JsonPrimitive -> content.toBooleanStrictOrNull()
    else -> null
  }
