package ai.openclaw.app.companion

import android.content.Context
import androidx.core.content.edit
import ai.openclaw.app.gateway.GatewayHelloSnapshot
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

class OperatorCompanionController(
  context: Context,
  private val scope: CoroutineScope,
  private val isConnected: StateFlow<Boolean>,
  private val helloSnapshot: StateFlow<GatewayHelloSnapshot?>,
  private val request: suspend (method: String, paramsJson: String?, timeoutMs: Long) -> String,
) {
  private companion object {
    const val PREFS_NAME = "openclaw_companion"
    const val PREF_CUSTOM_SNIPPETS = "custom_rpc_snippets"
    const val HISTORY_LIMIT = 18
    const val DEFAULT_TIMEOUT_MS = 15_000L
  }

  private val json = Json { ignoreUnknownKeys = true }
  private val prettyJson =
    Json {
      ignoreUnknownKeys = true
      prettyPrint = true
      prettyPrintIndent = "  "
    }
  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val builtInTemplates = defaultTemplates()
  private val _state =
    MutableStateFlow(
      OperatorCompanionState(
        snippets = builtInTemplates + loadCustomTemplates(),
      ),
    )

  val state: StateFlow<OperatorCompanionState> = _state.asStateFlow()

  init {
    scope.launch {
      helloSnapshot.collect { snapshot ->
        _state.update { current ->
          current.copy(capability = snapshot.toCapability())
        }
      }
    }

    scope.launch {
      isConnected.collectLatest { connected ->
        if (connected) {
          refreshAllInternal()
        } else {
          _state.update { current ->
            current.copy(
              isRefreshing = false,
              sessions = emptyList(),
              agents = emptyList(),
              channels = emptyList(),
              pendingPairs = emptyList(),
              pairedDevices = emptyList(),
              nodes = emptyList(),
              approvals = null,
              logs = LogTailSnapshot(file = null, cursor = null, lines = emptyList()),
              sessionsError = null,
              agentsError = null,
              channelsError = null,
              pairingError = null,
              approvalsError = null,
              logsError = null,
              nodesError = null,
            )
          }
        }
      }
    }
  }

  fun refreshAll() {
    scope.launch { refreshAllInternal() }
  }

  fun refreshSessions() {
    scope.launch { refreshSessionsInternal() }
  }

  fun refreshAgents() {
    scope.launch { refreshAgentsInternal() }
  }

  fun refreshChannels(probe: Boolean = false) {
    scope.launch { refreshChannelsInternal(probe = probe) }
  }

  fun refreshPairing() {
    scope.launch { refreshPairingInternal() }
  }

  fun refreshNodes() {
    scope.launch { refreshNodesInternal() }
  }

  fun refreshApprovals() {
    scope.launch { refreshApprovalsInternal() }
  }

  fun refreshLogs() {
    scope.launch { refreshLogsInternal() }
  }

  fun loadChannelConfig(channelId: String) {
    scope.launch { loadChannelConfigInternal(channelId) }
  }

  fun saveChannelPatch(channelId: String, patchJson: String) {
    scope.launch { saveChannelPatchInternal(channelId, patchJson) }
  }

  fun removeChannelConfig(channelId: String) {
    scope.launch { removeChannelConfigInternal(channelId) }
  }

  fun clearNotice() {
    _state.update { it.copy(latestNotice = null) }
  }

  fun resetSession(key: String) {
    scope.launch {
      val params =
        buildJsonObject {
          put("key", JsonPrimitive(key))
          put("reason", JsonPrimitive("reset"))
        }
      executeAction(
        method = "sessions.reset",
        paramsJson = params.toString(),
        successNotice = "Session reset.",
      )
      refreshSessionsInternal()
      refreshLogsInternal()
    }
  }

  fun compactSession(key: String) {
    scope.launch {
      val params =
        buildJsonObject {
          put("key", JsonPrimitive(key))
        }
      executeAction(
        method = "sessions.compact",
        paramsJson = params.toString(),
        successNotice = "Session compacted.",
      )
      refreshSessionsInternal()
    }
  }

  fun archiveSession(key: String) {
    scope.launch {
      val params =
        buildJsonObject {
          put("key", JsonPrimitive(key))
          put("deleteTranscript", JsonPrimitive(false))
        }
      executeAction(
        method = "sessions.delete",
        paramsJson = params.toString(),
        successNotice = "Session archived from the active list.",
      )
      refreshSessionsInternal()
    }
  }

  fun createAgent(name: String, workspace: String) {
    scope.launch {
      val trimmedName = name.trim()
      val trimmedWorkspace = workspace.trim()
      if (trimmedName.isEmpty() || trimmedWorkspace.isEmpty()) {
        postNotice("Agent name and workspace are required.")
        return@launch
      }
      val params =
        buildJsonObject {
          put("name", JsonPrimitive(trimmedName))
          put("workspace", JsonPrimitive(trimmedWorkspace))
        }
      executeAction(
        method = "agents.create",
        paramsJson = params.toString(),
        successNotice = "Agent created.",
      )
      refreshAgentsInternal()
    }
  }

  fun duplicateAgent(agent: OperatorAgentItem) {
    val workspace = agent.workspace?.trim().orEmpty()
    if (workspace.isEmpty()) {
      postNotice("This agent does not expose a workspace path to duplicate.")
      return
    }
    createAgent(name = "${agent.name} Copy", workspace = workspace)
  }

  fun deleteAgent(agentId: String) {
    scope.launch {
      val params =
        buildJsonObject {
          put("agentId", JsonPrimitive(agentId))
        }
      executeAction(
        method = "agents.delete",
        paramsJson = params.toString(),
        successNotice = "Agent deleted.",
      )
      refreshAgentsInternal()
      refreshSessionsInternal()
    }
  }

  fun logoutChannel(channelId: String, accountId: String?) {
    scope.launch {
      val params =
        buildJsonObject {
          put("channel", JsonPrimitive(channelId))
          accountId?.trim()?.takeIf(String::isNotEmpty)?.let { put("accountId", JsonPrimitive(it)) }
        }
      executeAction(
        method = "channels.logout",
        paramsJson = params.toString(),
        successNotice = "Channel logout requested.",
      )
      refreshChannelsInternal(probe = true)
    }
  }

  fun approvePendingPair(requestId: String) {
    scope.launch {
      val params =
        buildJsonObject {
          put("requestId", JsonPrimitive(requestId))
        }
      executeAction(
        method = "device.pair.approve",
        paramsJson = params.toString(),
        successNotice = "Device pairing approved.",
      )
      refreshPairingInternal()
    }
  }

  fun rejectPendingPair(requestId: String) {
    scope.launch {
      val params =
        buildJsonObject {
          put("requestId", JsonPrimitive(requestId))
        }
      executeAction(
        method = "device.pair.reject",
        paramsJson = params.toString(),
        successNotice = "Device pairing rejected.",
      )
      refreshPairingInternal()
    }
  }

  fun removePairedDevice(deviceId: String) {
    scope.launch {
      val params =
        buildJsonObject {
          put("deviceId", JsonPrimitive(deviceId))
        }
      executeAction(
        method = "device.pair.remove",
        paramsJson = params.toString(),
        successNotice = "Paired device removed.",
      )
      refreshPairingInternal()
    }
  }

  fun runGatewayUpdate() {
    scope.launch {
      executeAction(
        method = "update.run",
        paramsJson = "{}",
        timeoutMs = 45_000L,
        successNotice = "Gateway update requested. Expect a reconnect if the gateway restarts.",
      )
    }
  }

  fun executeRpc(method: String, paramsJson: String) {
    scope.launch {
      val trimmedMethod = method.trim()
      if (trimmedMethod.isEmpty()) {
        postNotice("RPC method is required.")
        return@launch
      }
      val normalizedParams = normalizeParams(paramsJson) ?: return@launch
      executeAction(
        method = trimmedMethod,
        paramsJson = normalizedParams,
        timeoutMs = 25_000L,
        successNotice = "`${trimmedMethod}` completed.",
      )
      refreshAfterMutation(trimmedMethod)
    }
  }

  fun saveCustomSnippet(
    title: String,
    description: String,
    method: String,
    paramsJson: String,
  ) {
    val trimmedTitle = title.trim()
    val trimmedMethod = method.trim()
    if (trimmedTitle.isEmpty() || trimmedMethod.isEmpty()) {
      postNotice("Snippet title and method are required.")
      return
    }
    val normalizedParams = normalizeParams(paramsJson) ?: return
    val customTemplates =
      loadCustomTemplates().filterNot { it.title.equals(trimmedTitle, ignoreCase = true) } +
        ActionTemplate(
          id = "custom-${UUID.randomUUID()}",
          title = trimmedTitle,
          description = description.trim().ifEmpty { "Saved RPC snippet" },
          method = trimmedMethod,
          paramsJson = normalizedParams,
          category = "Saved",
          builtIn = false,
        )
    persistCustomTemplates(customTemplates)
    _state.update { current ->
      current.copy(
        snippets = builtInTemplates + customTemplates,
        latestNotice = "Snippet saved.",
      )
    }
  }

  fun deleteCustomSnippet(snippetId: String) {
    val customTemplates = loadCustomTemplates().filterNot { it.id == snippetId }
    persistCustomTemplates(customTemplates)
    _state.update { current ->
      current.copy(
        snippets = builtInTemplates + customTemplates,
        latestNotice = "Snippet deleted.",
      )
    }
  }

  private suspend fun refreshAllInternal() {
    if (!isConnected.value) {
      return
    }
    _state.update {
      it.copy(
        isRefreshing = true,
        sessionsError = null,
        agentsError = null,
        channelsError = null,
        pairingError = null,
        approvalsError = null,
        logsError = null,
        nodesError = null,
      )
    }

    refreshSessionsInternal()
    refreshAgentsInternal()
    refreshChannelsInternal(probe = false)
    refreshPairingInternal()
    refreshNodesInternal()
    refreshApprovalsInternal()
    refreshLogsInternal()

    _state.update {
      it.copy(
        isRefreshing = false,
        lastUpdatedMs = System.currentTimeMillis(),
      )
    }
  }

  private suspend fun refreshSessionsInternal() {
    val capability = _state.value.capability
    if (!capability.canManageSessions) {
      _state.update { it.copy(sessions = emptyList(), sessionsError = null) }
      return
    }
    try {
      val params =
        buildJsonObject {
          put("includeGlobal", JsonPrimitive(true))
          put("includeUnknown", JsonPrimitive(false))
          put("includeDerivedTitles", JsonPrimitive(true))
          put("includeLastMessage", JsonPrimitive(true))
          put("limit", JsonPrimitive(80))
        }
      val root = requestObject("sessions.list", params.toString())
      val sessions = root["sessions"].asArrayOrNull().orEmpty().mapNotNull(::parseSessionItem)
      _state.update { it.copy(sessions = sessions, sessionsError = null) }
    } catch (err: Throwable) {
      _state.update { it.copy(sessionsError = err.message ?: "Unable to load sessions") }
    }
  }

  private suspend fun refreshAgentsInternal() {
    val capability = _state.value.capability
    if (!capability.canManageAgents) {
      _state.update { it.copy(agents = emptyList(), agentsError = null) }
      return
    }
    try {
      val root = requestObject("agents.list", "{}")
      val defaultId = root.string("defaultId")
      val agents =
        root["agents"].asArrayOrNull().orEmpty().mapNotNull { element ->
          val obj = element.asObjectOrNull() ?: return@mapNotNull null
          val id = obj.string("id") ?: return@mapNotNull null
          val identity = obj["identity"].asObjectOrNull()
          val workspace =
            identity?.string("workspace")
              ?: identity?.string("cwd")
              ?: identity?.string("workdir")
          OperatorAgentItem(
            id = id,
            name = obj.string("name") ?: id,
            workspace = workspace,
            summary = identity?.string("description") ?: identity?.string("systemPromptLabel"),
            isDefault = id == defaultId,
          )
        }
      _state.update { it.copy(agents = agents, agentsError = null) }
    } catch (err: Throwable) {
      _state.update { it.copy(agentsError = err.message ?: "Unable to load agents") }
    }
  }

  private suspend fun refreshChannelsInternal(probe: Boolean) {
    val capability = _state.value.capability
    if (!capability.canInspectChannels) {
      _state.update { it.copy(channels = emptyList(), channelsError = null) }
      return
    }
    try {
      val params =
        if (probe) {
          """{"probe":true,"timeoutMs":4000}"""
        } else {
          "{}"
        }
      val root = requestObject("channels.status", params)
      val labels = root["channelLabels"].asObjectOrNull() ?: JsonObject(emptyMap())
      val detailLabels = root["channelDetailLabels"].asObjectOrNull() ?: JsonObject(emptyMap())
      val accountsRoot = root["channelAccounts"].asObjectOrNull() ?: JsonObject(emptyMap())
      val order =
        root["channelOrder"].asArrayOrNull().orEmpty().mapNotNull { it.asStringOrNull()?.trim()?.takeIf(String::isNotEmpty) }

      val channels =
        order.map { channelId ->
          val accounts =
            accountsRoot[channelId].asArrayOrNull().orEmpty().mapNotNull { element ->
              val obj = element.asObjectOrNull() ?: return@mapNotNull null
              ChannelAccountItem(
                accountId = obj.string("accountId") ?: return@mapNotNull null,
                name = obj.string("name"),
                enabled = obj.boolean("enabled"),
                configured = obj.boolean("configured"),
                linked = obj.boolean("linked"),
                running = obj.boolean("running"),
                connected = obj.boolean("connected"),
                activeRuns = obj.int("activeRuns"),
                lastError = obj.string("lastError"),
              )
            }
          ChannelStatusItem(
            id = channelId,
            label = labels[channelId].asStringOrNull() ?: channelId,
            detailLabel = detailLabels[channelId].asStringOrNull(),
            accounts = accounts,
          )
        }
      _state.update { it.copy(channels = channels, channelsError = null) }
    } catch (err: Throwable) {
      _state.update { it.copy(channelsError = err.message ?: "Unable to load channels") }
    }
  }

  private suspend fun refreshPairingInternal() {
    val capability = _state.value.capability
    if (!capability.canManagePairing) {
      _state.update {
        it.copy(
          pendingPairs = emptyList(),
          pairedDevices = emptyList(),
          pairingError = null,
        )
      }
      return
    }
    try {
      val root = requestObject("device.pair.list", "{}")
      val pending =
        root["pending"].asArrayOrNull().orEmpty().mapNotNull { element ->
          val obj = element.asObjectOrNull() ?: return@mapNotNull null
          PendingDevicePairItem(
            requestId = obj.string("requestId") ?: return@mapNotNull null,
            deviceId = obj.string("deviceId") ?: return@mapNotNull null,
            displayName = obj.string("displayName"),
            role = obj.string("role"),
            scopes = obj["scopes"].asArrayOrNull().orEmpty().mapNotNull { it.asStringOrNull() },
            remoteIp = obj.string("remoteIp"),
            requestedAtMs = obj.long("ts"),
            isRepair = obj.boolean("isRepair") == true,
          )
        }
      val paired =
        root["paired"].asArrayOrNull().orEmpty().mapNotNull { element ->
          val obj = element.asObjectOrNull() ?: return@mapNotNull null
          val roles = obj["roles"].asArrayOrNull().orEmpty().mapNotNull { it.asStringOrNull() }
          val scopes = obj["scopes"].asArrayOrNull().orEmpty().mapNotNull { it.asStringOrNull() }
          PairedDeviceItem(
            deviceId = obj.string("deviceId") ?: return@mapNotNull null,
            displayName = obj.string("displayName"),
            roles = roles,
            scopes = scopes,
            remoteIp = obj.string("remoteIp"),
            approvedAtMs = obj.long("approvedAtMs") ?: obj.long("createdAtMs"),
            tokenSummary = summarizeTokenRoles(obj["tokens"]),
          )
        }
      _state.update {
        it.copy(
          pendingPairs = pending.sortedByDescending(PendingDevicePairItem::requestedAtMs),
          pairedDevices = paired.sortedBy { item -> item.displayName ?: item.deviceId },
          pairingError = null,
        )
      }
    } catch (err: Throwable) {
      _state.update { it.copy(pairingError = err.message ?: "Unable to load device pairing") }
    }
  }

  private suspend fun refreshNodesInternal() {
    val capability = _state.value.capability
    if (!capability.supports("node.list") || !capability.hasScope("operator.read")) {
      _state.update { it.copy(nodes = emptyList(), nodesError = null) }
      return
    }
    try {
      val root = requestElement("node.list", "{}")
      val nodesArray =
        when (root) {
          is JsonArray -> root
          is JsonObject -> root["nodes"].asArrayOrNull() ?: JsonArray(emptyList())
          else -> JsonArray(emptyList())
        }
      val nodes =
        nodesArray.mapNotNull { element ->
          val obj = element.asObjectOrNull() ?: return@mapNotNull null
          NodeItem(
            id = obj.string("nodeId") ?: obj.string("id") ?: return@mapNotNull null,
            displayName = obj.string("displayName") ?: obj.string("name"),
            platform = obj.string("platform"),
            status = obj.string("status"),
            lastSeenAtMs = obj.long("lastSeenAtMs") ?: obj.long("lastHeartbeatAtMs"),
          )
        }
      _state.update { it.copy(nodes = nodes, nodesError = null) }
    } catch (err: Throwable) {
      _state.update { it.copy(nodesError = err.message ?: "Unable to load nodes") }
    }
  }

  private suspend fun refreshApprovalsInternal() {
    val capability = _state.value.capability
    if (!capability.canInspectApprovals) {
      _state.update { it.copy(approvals = null, approvalsError = null) }
      return
    }
    try {
      val root = requestObject("exec.approvals.get", "{}")
      val file = root["file"].asObjectOrNull() ?: JsonObject(emptyMap())
      val agents = file["agents"].asObjectOrNull() ?: JsonObject(emptyMap())
      val allowlistCount =
        agents.values.sumOf { agent ->
          val entries = agent.asObjectOrNull()?.get("allowlist").asArrayOrNull().orEmpty()
          entries.count { it.asObjectOrNull()?.string("pattern")?.isNotBlank() == true }
        }
      _state.update {
        it.copy(
          approvals =
            ExecApprovalsSummary(
              path = root.string("path").orEmpty(),
              exists = root.boolean("exists") == true,
              hash = root.string("hash").orEmpty(),
              agentCount = agents.size,
              allowlistCount = allowlistCount,
            ),
          approvalsError = null,
        )
      }
    } catch (err: Throwable) {
      _state.update { it.copy(approvalsError = err.message ?: "Unable to load approvals") }
    }
  }

  private suspend fun refreshLogsInternal() {
    val capability = _state.value.capability
    if (!capability.canReadLogs) {
      _state.update { it.copy(logs = LogTailSnapshot(file = null, cursor = null, lines = emptyList()), logsError = null) }
      return
    }
    try {
      val params =
        buildJsonObject {
          put("limit", JsonPrimitive(12))
          put("maxBytes", JsonPrimitive(64_000))
        }
      val root = requestObject("logs.tail", params.toString())
      val lines = root["lines"].asArrayOrNull().orEmpty().mapNotNull { it.asStringOrNull() }
      _state.update {
        it.copy(
          logs =
            LogTailSnapshot(
              file = root.string("file"),
              cursor = root.long("cursor"),
              lines = lines,
            ),
          logsError = null,
        )
      }
    } catch (err: Throwable) {
      _state.update { it.copy(logsError = err.message ?: "Unable to tail logs") }
    }
  }

  private suspend fun loadChannelConfigInternal(channelId: String) {
    val normalizedChannelId = channelId.trim()
    if (normalizedChannelId.isEmpty()) {
      _state.update {
        it.copy(
          channelConfig =
            ChannelConfigSnapshot(
              errorText = "Channel ID is required.",
            ),
        )
      }
      return
    }
    val capability = _state.value.capability
    if (!capability.canReadConfig) {
      _state.update {
        it.copy(
          channelConfig =
            ChannelConfigSnapshot(
              channelId = normalizedChannelId,
              errorText = "This gateway session does not expose config.get.",
            ),
        )
      }
      return
    }

    _state.update { current ->
      current.copy(
        channelConfig =
          current.channelConfig.copy(
            channelId = normalizedChannelId,
            isLoading = true,
            errorText = null,
          ),
      )
    }

    try {
      val root = requestObject("config.get", "{}")
      val configRoot = root["config"].asObjectOrNull() ?: JsonObject(emptyMap())
      val configElement = extractChannelConfig(configRoot, normalizedChannelId)
      val prettyConfig = configElement?.let { prettyJson.encodeToString(JsonElement.serializer(), it) }
      _state.update {
        it.copy(
          channelConfig =
            ChannelConfigSnapshot(
              channelId = normalizedChannelId,
              currentConfigJson = prettyConfig,
              baseHash = root.string("hash"),
              hasExistingConfig = configElement != null,
              containsRedactedSecrets = containsRedactedSecrets(configElement),
              isLoading = false,
              lastLoadedAtMs = System.currentTimeMillis(),
              errorText = null,
            ),
        )
      }
    } catch (err: Throwable) {
      _state.update { current ->
        current.copy(
          channelConfig =
            current.channelConfig.copy(
              channelId = normalizedChannelId,
              isLoading = false,
              errorText = err.message ?: "Unable to load channel config",
            ),
        )
      }
    }
  }

  private suspend fun saveChannelPatchInternal(channelId: String, patchJson: String) {
    val normalizedChannelId = channelId.trim()
    if (normalizedChannelId.isEmpty()) {
      postNotice("Channel ID is required.")
      return
    }
    val capability = _state.value.capability
    if (!capability.canPatchConfig) {
      postNotice("This gateway session does not expose config.patch.")
      return
    }
    val patchElement =
      normalizeChannelPatch(patchJson) ?: run {
        postNotice("Channel patch must be a valid JSON object.")
        return
      }
    val baseHash =
      try {
        requestObject("config.get", "{}").string("hash")
      } catch (err: Throwable) {
        postNotice(err.message ?: "Unable to refresh config hash")
        return
      }

    val params =
      buildJsonObject {
        put("raw", JsonPrimitive(buildChannelConfigPatchRaw(normalizedChannelId, patchElement)))
        baseHash?.let { put("baseHash", JsonPrimitive(it)) }
      }
    val response =
      executeAction(
        method = "config.patch",
        paramsJson = params.toString(),
        timeoutMs = 25_000L,
        successNotice = "Channel patch applied. The gateway may reconnect while it hot-reloads.",
      )
    if (response != null) {
      refreshChannelsInternal(probe = true)
      loadChannelConfigInternal(normalizedChannelId)
    }
  }

  private suspend fun removeChannelConfigInternal(channelId: String) {
    val normalizedChannelId = channelId.trim()
    if (normalizedChannelId.isEmpty()) {
      postNotice("Channel ID is required.")
      return
    }
    val capability = _state.value.capability
    if (!capability.canPatchConfig) {
      postNotice("This gateway session does not expose config.patch.")
      return
    }
    val baseHash =
      try {
        requestObject("config.get", "{}").string("hash")
      } catch (err: Throwable) {
        postNotice(err.message ?: "Unable to refresh config hash")
        return
      }

    val params =
      buildJsonObject {
        put("raw", JsonPrimitive(buildChannelConfigPatchRaw(normalizedChannelId, null)))
        baseHash?.let { put("baseHash", JsonPrimitive(it)) }
      }
    val response =
      executeAction(
        method = "config.patch",
        paramsJson = params.toString(),
        timeoutMs = 25_000L,
        successNotice = "Channel config removed. The gateway may reconnect while it hot-reloads.",
      )
    if (response != null) {
      refreshChannelsInternal(probe = true)
      loadChannelConfigInternal(normalizedChannelId)
    }
  }

  private suspend fun executeAction(
    method: String,
    paramsJson: String,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    successNotice: String,
  ): String? {
    val normalizedParams = normalizeParams(paramsJson) ?: return null
    return try {
      val response = request(method, normalizedParams, timeoutMs)
      appendHistory(
        method = method,
        paramsJson = normalizedParams,
        responseJson = response,
        errorText = null,
      )
      postNotice(successNotice)
      response
    } catch (err: Throwable) {
      appendHistory(
        method = method,
        paramsJson = normalizedParams,
        responseJson = null,
        errorText = err.message ?: "Unknown RPC error",
      )
      postNotice(err.message ?: "RPC failed")
      null
    }
  }

  private suspend fun refreshAfterMutation(method: String) {
    when {
      method.startsWith("sessions.") -> refreshSessionsInternal()
      method.startsWith("agents.") -> {
        refreshAgentsInternal()
        refreshSessionsInternal()
      }
      method.startsWith("channels.") -> refreshChannelsInternal(probe = true)
      method.startsWith("device.pair.") -> refreshPairingInternal()
      method.startsWith("exec.approvals.") -> refreshApprovalsInternal()
    }
  }

  private suspend fun requestElement(
    method: String,
    paramsJson: String?,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  ): JsonElement {
    val raw = request(method, paramsJson, timeoutMs)
    if (raw.isBlank()) {
      return JsonObject(emptyMap())
    }
    return json.parseToJsonElement(raw)
  }

  private suspend fun requestObject(
    method: String,
    paramsJson: String?,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  ): JsonObject {
    return requestElement(method, paramsJson, timeoutMs).asObjectOrNull() ?: JsonObject(emptyMap())
  }

  private fun appendHistory(
    method: String,
    paramsJson: String,
    responseJson: String?,
    errorText: String?,
  ) {
    val entry =
      RpcHistoryEntry(
        id = UUID.randomUUID().toString(),
        method = method,
        paramsJson = paramsJson,
        responseJson = responseJson,
        errorText = errorText,
        succeeded = errorText == null,
        createdAtMs = System.currentTimeMillis(),
      )
    _state.update { current ->
      current.copy(history = listOf(entry) + current.history.take(HISTORY_LIMIT - 1))
    }
  }

  private fun postNotice(message: String) {
    _state.update { it.copy(latestNotice = message) }
  }

  private fun loadCustomTemplates(): List<ActionTemplate> {
    val raw = prefs.getString(PREF_CUSTOM_SNIPPETS, null) ?: return emptyList()
    return try {
      json.decodeFromString(ListSerializer(ActionTemplate.serializer()), raw)
        .map { it.copy(builtIn = false) }
    } catch (_: Throwable) {
      emptyList()
    }
  }

  private fun persistCustomTemplates(templates: List<ActionTemplate>) {
    prefs.edit {
      putString(
        PREF_CUSTOM_SNIPPETS,
        json.encodeToString(ListSerializer(ActionTemplate.serializer()), templates),
      )
    }
  }

  private fun defaultTemplates(): List<ActionTemplate> {
    return listOf(
      ActionTemplate(
        id = "builtin-health",
        title = "Health",
        description = "Quick reachability check.",
        method = "health",
        paramsJson = "{}",
        category = "Core",
        builtIn = true,
      ),
      ActionTemplate(
        id = "builtin-status",
        title = "Status",
        description = "Gateway status snapshot.",
        method = "status",
        paramsJson = "{}",
        category = "Core",
        builtIn = true,
      ),
      ActionTemplate(
        id = "builtin-sessions-list",
        title = "Sessions List",
        description = "List recent sessions with previews.",
        method = "sessions.list",
        paramsJson = """{"includeGlobal":true,"includeUnknown":false,"includeDerivedTitles":true,"includeLastMessage":true,"limit":50}""",
        category = "Sessions",
        builtIn = true,
      ),
      ActionTemplate(
        id = "builtin-sessions-reset",
        title = "Reset Main Session",
        description = "Reset the main session in place.",
        method = "sessions.reset",
        paramsJson = """{"key":"main","reason":"reset"}""",
        category = "Sessions",
        builtIn = true,
      ),
      ActionTemplate(
        id = "builtin-agents-list",
        title = "Agents List",
        description = "Inspect configured agents.",
        method = "agents.list",
        paramsJson = "{}",
        category = "Agents",
        builtIn = true,
      ),
      ActionTemplate(
        id = "builtin-channels-status",
        title = "Channels Probe",
        description = "Probe live channel health.",
        method = "channels.status",
        paramsJson = """{"probe":true,"timeoutMs":4000}""",
        category = "Channels",
        builtIn = true,
      ),
      ActionTemplate(
        id = "builtin-device-pairs",
        title = "Device Pairing",
        description = "List pending and paired devices.",
        method = "device.pair.list",
        paramsJson = "{}",
        category = "Devices",
        builtIn = true,
      ),
      ActionTemplate(
        id = "builtin-approvals",
        title = "Approvals Snapshot",
        description = "Read current exec approvals file.",
        method = "exec.approvals.get",
        paramsJson = "{}",
        category = "Approvals",
        builtIn = true,
      ),
      ActionTemplate(
        id = "builtin-logs",
        title = "Logs Tail",
        description = "Grab the latest gateway logs.",
        method = "logs.tail",
        paramsJson = """{"limit":12,"maxBytes":64000}""",
        category = "Logs",
        builtIn = true,
      ),
      ActionTemplate(
        id = "builtin-update",
        title = "Update Run",
        description = "Run gateway update + restart.",
        method = "update.run",
        paramsJson = "{}",
        category = "Update",
        builtIn = true,
      ),
    )
  }

  private fun normalizeParams(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
      return null
    }
    return try {
      json.parseToJsonElement(trimmed).toString()
    } catch (_: Throwable) {
      postNotice("Parameters must be valid JSON.")
      null
    }
  }

  private fun parseSessionItem(element: JsonElement): OperatorSessionItem? {
    val obj = element.asObjectOrNull() ?: return null
    val key = obj.string("key") ?: return null
    val displayName = obj.string("displayName")
    val title = obj.string("title") ?: obj.string("derivedTitle") ?: displayName ?: key
    val preview =
      when (val lastMessage = obj["lastMessage"]) {
        is JsonObject -> lastMessage.string("preview") ?: lastMessage.string("text")
        is JsonPrimitive -> lastMessage.content
        else -> null
      }
    return OperatorSessionItem(
      key = key,
      title = title,
      displayName = displayName,
      agentId = obj.string("agentId"),
      label = obj.string("label"),
      model = obj.string("model"),
      updatedAtMs = obj.long("updatedAt"),
      lastMessagePreview = preview,
    )
  }

  private fun summarizeTokenRoles(tokensElement: JsonElement?): String? {
    val tokens = tokensElement.asArrayOrNull().orEmpty()
    if (tokens.isEmpty()) return null
    val parts =
      tokens.mapNotNull { element ->
        val obj = element.asObjectOrNull() ?: return@mapNotNull null
        val role = obj.string("role") ?: return@mapNotNull null
        if (obj.long("revokedAtMs") != null) {
          "$role (revoked)"
        } else {
          role
        }
      }
    return if (parts.isEmpty()) null else parts.joinToString(", ")
  }
}

internal fun extractChannelConfig(configRoot: JsonObject, channelId: String): JsonElement? {
  val normalizedChannelId = channelId.trim()
  if (normalizedChannelId.isEmpty()) return null
  val channels = configRoot["channels"].asObjectOrNull() ?: return null
  return channels[normalizedChannelId]
}

internal fun buildChannelConfigPatchRaw(channelId: String, patchElement: JsonElement?): String {
  val normalizedChannelId = channelId.trim()
  require(normalizedChannelId.isNotEmpty()) { "channelId must not be blank" }
  return buildJsonObject {
    put(
      "channels",
      buildJsonObject {
        put(normalizedChannelId, patchElement ?: JsonNull)
      },
    )
  }.toString()
}

internal fun containsRedactedSecrets(configElement: JsonElement?): Boolean {
  return configElement?.toString()?.contains("__OPENCLAW_REDACTED__") == true
}

private fun normalizeChannelPatch(raw: String): JsonObject? {
  val trimmed = raw.trim()
  if (trimmed.isEmpty()) return null
  return try {
    Json.parseToJsonElement(trimmed).asObjectOrNull()
  } catch (_: Throwable) {
    null
  }
}

private fun GatewayHelloSnapshot?.toCapability(): GatewayCapabilitySnapshot {
  return GatewayCapabilitySnapshot(
    serverVersion = this?.serverVersion,
    authRole = this?.authRole,
    grantedScopes = this?.grantedScopes ?: emptySet(),
    supportedMethods = this?.supportedMethods ?: emptySet(),
    supportedEvents = this?.supportedEvents ?: emptySet(),
  )
}

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonElement?.asStringOrNull(): String? =
  when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content.trim().takeIf(String::isNotEmpty)
    else -> null
  }

private fun JsonElement?.asBooleanOrNull(): Boolean? =
  when (this) {
    is JsonPrimitive -> booleanOrNull
    else -> null
  }

private fun JsonElement?.asLongOrNull(): Long? =
  when (this) {
    is JsonPrimitive -> content.toLongOrNull()
    else -> null
  }

private fun JsonObject.string(key: String): String? = this[key].asStringOrNull()

private fun JsonObject.boolean(key: String): Boolean? = this[key].asBooleanOrNull()

private fun JsonObject.long(key: String): Long? = this[key].asLongOrNull()

private fun JsonObject.int(key: String): Int? = long(key)?.toInt()
