package ai.openclaw.app.chat

import ai.openclaw.app.diagnostics.ConversationMode
import ai.openclaw.app.diagnostics.ConversationTurnDiagnostics
import ai.openclaw.app.diagnostics.ConversationTurnStatus
import ai.openclaw.app.trace.TraceEventType
import ai.openclaw.app.trace.TraceSeverity
import ai.openclaw.app.trace.TraceSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ChatController(
  private val scope: CoroutineScope,
  private val json: Json,
  private val request: suspend (method: String, paramsJson: String?, timeoutMs: Long) -> String,
  private val sendNodeEvent: suspend (event: String, payloadJson: String?) -> Boolean,
  private val supportsChatSubscribe: () -> Boolean,
  private val supportsAgentWait: () -> Boolean,
  private val traceSink: (
    source: TraceSource,
    eventType: TraceEventType,
    severity: TraceSeverity,
    description: String,
    payloadText: String?,
    sessionId: String?,
    agentName: String?,
    deviceId: String?,
    timestampMs: Long,
  ) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
) {
  private val _sessionKey = MutableStateFlow("main")
  val sessionKey: StateFlow<String> = _sessionKey.asStateFlow()

  private val _sessionId = MutableStateFlow<String?>(null)
  val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

  private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

  private val _errorText = MutableStateFlow<String?>(null)
  val errorText: StateFlow<String?> = _errorText.asStateFlow()

  private val _healthOk = MutableStateFlow(false)
  val healthOk: StateFlow<Boolean> = _healthOk.asStateFlow()

  private val _thinkingLevel = MutableStateFlow("off")
  val thinkingLevel: StateFlow<String> = _thinkingLevel.asStateFlow()

  private val _pendingRunCount = MutableStateFlow(0)
  val pendingRunCount: StateFlow<Int> = _pendingRunCount.asStateFlow()

  private val _streamingAssistantText = MutableStateFlow<String?>(null)
  val streamingAssistantText: StateFlow<String?> = _streamingAssistantText.asStateFlow()

  private val pendingToolCallsById = ConcurrentHashMap<String, ChatPendingToolCall>()
  private val _pendingToolCalls = MutableStateFlow<List<ChatPendingToolCall>>(emptyList())
  val pendingToolCalls: StateFlow<List<ChatPendingToolCall>> = _pendingToolCalls.asStateFlow()

  private val _sessions = MutableStateFlow<List<ChatSessionEntry>>(emptyList())
  val sessions: StateFlow<List<ChatSessionEntry>> = _sessions.asStateFlow()

  private val _turnDiagnostics = MutableStateFlow<ConversationTurnDiagnostics?>(null)
  val turnDiagnostics: StateFlow<ConversationTurnDiagnostics?> = _turnDiagnostics.asStateFlow()

  private val pendingRuns = mutableSetOf<String>()
  private val pendingRunSnapshots = ConcurrentHashMap<String, PendingRunSnapshot>()
  private val pendingDiagnosticsByRunId = ConcurrentHashMap<String, ConversationTurnDiagnostics>()
  private val lastDiagnosticsBySessionKey = ConcurrentHashMap<String, ConversationTurnDiagnostics>()
  private val pendingAssistantPreviewByRunId = ConcurrentHashMap<String, String>()
  private val pendingRunTimeoutJobs = ConcurrentHashMap<String, Job>()
  private val pendingHistoryRecoveryJobs = ConcurrentHashMap<String, Job>()
  private val pendingSilenceProbeJobs = ConcurrentHashMap<String, Job>()
  private val pendingAgentWaitJobs = ConcurrentHashMap<String, Job>()
  private val completedRunReplyFingerprints = LinkedHashMap<String, String>()
  private val slashExecutor = SlashCommandExecutor(json = json, request = request)
  private val pendingRunTimeoutMs = 60_000L
  private val liveEventSilenceProbeMs = 4_000L
  private val historyRecoveryIntervalMs = 1_500L
  private val historyRecoveryAttemptsWithoutSubscribe = 40
  private val historyRecoveryAttemptsAfterSilence = 18
  private val historyRecoveryAttemptsAfterTimeout = 8
  private val maxCachedCompletedRuns = 96

  private var lastHealthPollAtMs: Long? = null
  private var chatSubscribedSessionKey: String? = null

  private data class PendingRunSnapshot(
    val sessionKey: String,
    val idempotencyKey: String,
    val startedAtMs: Long,
    val baselineMessageCount: Int,
    val baselineAssistantFingerprint: String?,
    val optimisticUserMessage: ChatMessage,
  )

  private data class ChatSendAck(
    val runId: String?,
    val status: String?,
  )

  private data class AgentWaitResult(
    val status: String?,
    val errorMessage: String?,
  )

  fun onDisconnected(message: String) {
    _healthOk.value = false
    // Not an error; keep connection status in the UI pill.
    _errorText.value = null
    trace(
      source = TraceSource.Gateway,
      eventType = TraceEventType.ConnectionClosed,
      severity = TraceSeverity.Warning,
      description = message,
      sessionId = _sessionKey.value,
    )
    pausePendingRecoveries()
    pendingToolCallsById.clear()
    synchronized(completedRunReplyFingerprints) {
      completedRunReplyFingerprints.clear()
    }
    publishPendingToolCalls()
    _sessionId.value = null
    chatSubscribedSessionKey = null
    refreshPendingUiState()
  }

  fun load(sessionKey: String) {
    val key = sessionKey.trim().ifEmpty { "main" }
    _sessionKey.value = key
    refreshPendingUiState()
    scope.launch { bootstrap(forceHealth = true) }
  }

  fun applyMainSessionKey(mainSessionKey: String) {
    val trimmed = mainSessionKey.trim()
    if (trimmed.isEmpty()) return
    if (_sessionKey.value == trimmed) return
    if (_sessionKey.value != "main") return
    _sessionKey.value = trimmed
    refreshPendingUiState()
    scope.launch { bootstrap(forceHealth = true) }
  }

  fun refresh() {
    scope.launch { bootstrap(forceHealth = true) }
  }

  fun refreshSessions(limit: Int? = null) {
    scope.launch { fetchSessions(limit = limit) }
  }

  fun ensureSubscribed() {
    scope.launch { subscribeChatIfNeeded(_sessionKey.value) }
  }

  fun setThinkingLevel(thinkingLevel: String) {
    val normalized = normalizeThinking(thinkingLevel) ?: "off"
    if (normalized == _thinkingLevel.value) return
    _thinkingLevel.value = normalized
  }

  fun switchSession(sessionKey: String) {
    val key = sessionKey.trim()
    if (key.isEmpty()) return
    if (key == _sessionKey.value) return
    _sessionKey.value = key
    refreshPendingUiState()
    scope.launch { bootstrap(forceHealth = true) }
  }

  fun createSession(sessionKey: String) {
    val key = sessionKey.trim()
    if (key.isEmpty()) return
    _sessions.value =
      listOf(ChatSessionEntry(key = key, updatedAtMs = System.currentTimeMillis(), displayName = null)) +
        _sessions.value.filterNot { it.key == key }
    _sessionKey.value = key
    _messages.value = emptyList()
    _sessionId.value = null
    _errorText.value = null
    _streamingAssistantText.value = null
    clearPendingRuns()
    pendingToolCallsById.clear()
    publishPendingToolCalls()
    refreshPendingUiState()
    scope.launch { bootstrap(forceHealth = true) }
  }

  fun sendMessage(
    message: String,
    thinkingLevel: String,
    attachments: List<OutgoingAttachment>,
  ) {
    val trimmed = message.trim()
    if (trimmed.isEmpty() && attachments.isEmpty()) return
    val parsedSlash = if (attachments.isEmpty()) parseSlashCommand(trimmed) else null
    if (parsedSlash != null) {
      val name = parsedSlash.command.name
      if (!parsedSlash.command.executeLocal || name == "new" || name == "reset") {
        // Gateway/native command lane. Let it flow through chat.send.
      } else {
        executeLocalSlashCommand(parsedSlash)
        return
      }
    } else if (attachments.isEmpty() && trimmed.startsWith("/")) {
      appendSystemMessage("Unsupported slash command `$trimmed`.\nTry `/help`.")
      return
    }
    if (!_healthOk.value) {
      _errorText.value = "Gateway health not OK; cannot send"
      return
    }
    if (_pendingRunCount.value > 0) {
      _errorText.value = "Wait for the current reply or stop it first."
      return
    }

    val runId = UUID.randomUUID().toString()
    val text = if (trimmed.isEmpty() && attachments.isNotEmpty()) "See attached." else trimmed
    val sessionKey = _sessionKey.value
    val thinking = normalizeThinking(thinkingLevel) ?: "off"
    val baselineMessages = _messages.value
    val sentAtMs = System.currentTimeMillis()
    val userContent =
      buildList {
        add(ChatMessageContent(type = "text", text = text))
        for (att in attachments) {
          add(
            ChatMessageContent(
              type = att.type,
              mimeType = att.mimeType,
              fileName = att.fileName,
              base64 = att.base64,
            ),
          )
        }
      }
    val optimisticUserMessage =
      ChatMessage(
        id = UUID.randomUUID().toString(),
        role = "user",
        content = userContent,
        timestampMs = sentAtMs,
      )
    val pendingSnapshot =
      PendingRunSnapshot(
        sessionKey = sessionKey,
        idempotencyKey = runId,
        startedAtMs = sentAtMs,
        baselineMessageCount = baselineMessages.size,
        baselineAssistantFingerprint = assistantFingerprint(baselineMessages.lastOrNull { it.role == "assistant" }),
        optimisticUserMessage = optimisticUserMessage,
      )
    val diagnostics =
      ConversationTurnDiagnostics(
        mode = ConversationMode.Chat,
        sessionKey = sessionKey,
        runId = runId,
        inputPreview = ConversationTurnDiagnostics.preview(text),
        status = ConversationTurnStatus.Sending,
        startedAtMs = sentAtMs,
      )

    _messages.value = _messages.value + optimisticUserMessage
    trace(
      source = TraceSource.Client,
      eventType = TraceEventType.UserMessageSent,
      description = "User chat prompt queued",
      payloadText = text,
      sessionId = sessionKey,
      deviceId = "android",
      timestampMs = sentAtMs,
    )

    trackPendingRun(runId, pendingSnapshot)
    trackPendingDiagnostics(runId, diagnostics)
    armPendingRunTimeout(runId)

    _errorText.value = null
    _streamingAssistantText.value = null
    pendingToolCallsById.clear()
    publishPendingToolCalls()

    scope.launch {
      try {
        val params = buildChatSendParams(sessionKey, text, thinking, attachments, runId)
        val res = request("chat.send", params.toString(), 15_000)
        val ack = parseChatSendAck(res)
        val actualRunId = ack.runId ?: runId
        updatePendingDiagnostics(actualRunId) { existing ->
          (existing ?: diagnostics).copy(
            runId = actualRunId,
            status = ConversationTurnStatus.Waiting,
            sendAckAtMs = System.currentTimeMillis(),
          )
        }
        if (actualRunId != runId) {
          remapPendingRun(runId, actualRunId, pendingSnapshot)
          armPendingRunTimeout(actualRunId)
          startPendingRecoveryStrategy(actualRunId)
        } else {
          startPendingRecoveryStrategy(runId)
        }
        trace(
          source = TraceSource.Client,
          eventType = TraceEventType.GatewayEvent,
          description = "Chat send acknowledged",
          payloadText = res,
          sessionId = sessionKey,
          deviceId = "android",
        )
        if (ack.status == "ok") {
          if (!tryCompleteRunFromHistory(actualRunId)) {
            armPendingHistoryRecovery(
              runId = actualRunId,
              initialDelayMs = 250L,
              intervalMs = 750L,
              maxAttempts = 8,
              failureMessage = "Reply delayed; refresh chat to reload history.",
            )
          }
        }
      } catch (err: Throwable) {
        if (shouldKeepPendingAfterSendFailure(err)) {
          updatePendingDiagnostics(runId) { existing ->
            (existing ?: diagnostics).copy(
              status = ConversationTurnStatus.Waiting,
              errorMessage = "Acknowledgement delayed",
            )
          }
          _errorText.value = "Send acknowledgement delayed; waiting for gateway reply."
          trace(
            source = TraceSource.Client,
            eventType = TraceEventType.Error,
            severity = TraceSeverity.Warning,
            description = "Chat send acknowledgement delayed",
            payloadText = err.message,
            sessionId = sessionKey,
            deviceId = "android",
          )
          armPendingSendRecovery(
            runId = runId,
            sessionKey = sessionKey,
            message = text,
            thinking = thinking,
            attachments = attachments,
          )
        } else {
          finalizePendingDiagnostics(
            runId = runId,
            status = ConversationTurnStatus.Error,
            completionSource = "chat-error",
            errorMessage = err.message,
          )
          clearPendingRun(runId)
          _errorText.value = err.message
          trace(
            source = TraceSource.Client,
            eventType = TraceEventType.Error,
            severity = TraceSeverity.Error,
            description = err.message ?: "Chat send failed",
            payloadText = text,
            sessionId = sessionKey,
            deviceId = "android",
          )
        }
      }
    }
  }

  private fun executeLocalSlashCommand(command: ParsedSlashCommand) {
    val nonBlockingCommands = setOf("help", "status", "usage", "agents", "stop")
    if (_pendingRunCount.value > 0 && command.command.name !in nonBlockingCommands) {
      appendSystemMessage("Wait for the current reply or stop it first.")
      return
    }
    when (command.command.name) {
      "stop" -> {
        abort(forceSessionAbort = true, insertSystemMessage = true)
        return
      }
      "clear" -> {
        scope.launch {
          try {
            request(
              "sessions.reset",
              buildJsonObject {
                put("key", JsonPrimitive(_sessionKey.value))
              }.toString(),
              15_000,
            )
            clearPendingRuns()
            pendingToolCallsById.clear()
            publishPendingToolCalls()
            _messages.value = emptyList()
            _streamingAssistantText.value = null
            _errorText.value = null
            _sessionId.value = null
            refreshPendingUiState()
            fetchSessions(limit = 50)
            pollHealthIfNeeded(force = true)
            appendSystemMessage("Chat history cleared.")
          } catch (err: Throwable) {
            appendSystemMessage("Failed to clear chat history: ${err.message ?: err::class.simpleName}")
          }
        }
        return
      }
      "focus", "export" -> {
        appendSystemMessage(
          if (command.command.name == "focus") {
            "Focus mode is not available in Android yet."
          } else {
            "Export is not available in Android yet."
          },
        )
        return
      }
    }
    scope.launch {
      val result = slashExecutor.execute(_sessionKey.value, command)
      if (command.command.name == "think" && command.args.isNotBlank()) {
        normalizeThinking(command.args)?.let { _thinkingLevel.value = it }
      }
      appendSystemMessage(result.content)
      if (result.refreshChat) {
        fetchSessions(limit = 50)
        pollHealthIfNeeded(force = true)
      }
    }
  }

  fun abort(forceSessionAbort: Boolean = false, insertSystemMessage: Boolean = false) {
    val sessionKey = _sessionKey.value
    val runIds = pendingRunSnapshots.entries.filter { it.value.sessionKey == sessionKey }.map { it.key }
    if (runIds.isEmpty() && !forceSessionAbort) return
    scope.launch {
      var abortedAny = false
      var hadSuccessfulRequest = false
      for (runId in runIds) {
        try {
          val params =
            buildJsonObject {
              put("sessionKey", JsonPrimitive(sessionKey))
              put("runId", JsonPrimitive(runId))
            }
          val response = request("chat.abort", params.toString(), 15_000)
          hadSuccessfulRequest = true
          if (parseAbortResponse(response) != false) {
            abortedAny = true
            finalizeAbortedRun(runId)
          }
        } catch (_: Throwable) {
          // best-effort
        }
      }
      if (runIds.isEmpty() || forceSessionAbort) {
        try {
          val response =
            request(
              "chat.abort",
              buildJsonObject {
                put("sessionKey", JsonPrimitive(sessionKey))
              }.toString(),
              15_000,
            )
          hadSuccessfulRequest = true
          if (parseAbortResponse(response) != false) {
            abortedAny = true
          }
        } catch (err: Throwable) {
          if (insertSystemMessage) {
            appendSystemMessage("Failed to abort current run: ${err.message ?: err::class.simpleName}")
          }
        }
      }
      if (abortedAny) {
        _streamingAssistantText.value = null
        _errorText.value = null
        pendingToolCallsById.clear()
        publishPendingToolCalls()
        trace(
          source = TraceSource.Client,
          eventType = TraceEventType.GatewayEvent,
          description = "Chat run aborted",
          payloadText = null,
          sessionId = sessionKey,
        )
        if (insertSystemMessage) {
          appendSystemMessage("⚙️ Agent was aborted.")
        }
      } else if (insertSystemMessage && hadSuccessfulRequest) {
        appendSystemMessage("No active run to abort.")
      }
    }
  }

  fun handleGatewayEvent(event: String, payloadJson: String?) {
    when (event) {
      "tick" -> {
        scope.launch { pollHealthIfNeeded(force = false) }
      }
      "health" -> {
        // If we receive a health snapshot, the gateway is reachable.
        _healthOk.value = true
        resumePendingRecoveries()
        trace(
          source = TraceSource.Gateway,
          eventType = TraceEventType.GatewayEvent,
          description = "Gateway health is OK",
          sessionId = _sessionKey.value,
        )
      }
      "seqGap" -> {
        _errorText.value = "Event stream interrupted; try refreshing."
        resumePendingRecoveries()
        trace(
          source = TraceSource.Gateway,
          eventType = TraceEventType.Error,
          severity = TraceSeverity.Warning,
          description = "Event stream interrupted",
          payloadText = payloadJson,
          sessionId = _sessionKey.value,
        )
      }
      "chat" -> {
        if (payloadJson.isNullOrBlank()) return
        handleChatEvent(payloadJson)
      }
      "agent" -> {
        if (payloadJson.isNullOrBlank()) return
        handleAgentEvent(payloadJson)
      }
    }
  }

  private suspend fun bootstrap(forceHealth: Boolean) {
    _errorText.value = null
    _healthOk.value = false
    pendingToolCallsById.clear()
    publishPendingToolCalls()
    _sessionId.value = null

    val key = _sessionKey.value
    try {
      subscribeChatIfNeeded(key)

      val historyJson = request("chat.history", """{"sessionKey":"$key"}""", 15_000)
      val history = parseHistory(historyJson, sessionKey = key)
      _messages.value = mergeHistoryWithPending(history.messages, sessionKey = key)
      _sessionId.value = history.sessionId
      history.thinkingLevel?.trim()?.takeIf { it.isNotEmpty() }?.let { _thinkingLevel.value = it }

      pollHealthIfNeeded(force = forceHealth)
      fetchSessions(limit = 50)
      refreshPendingUiState()
      resumePendingRecoveries()
    } catch (err: Throwable) {
      _errorText.value = err.message
    }
  }

  private suspend fun fetchSessions(limit: Int?) {
    try {
      val params =
        buildJsonObject {
          put("includeGlobal", JsonPrimitive(true))
          put("includeUnknown", JsonPrimitive(false))
          if (limit != null && limit > 0) put("limit", JsonPrimitive(limit))
        }
      val res = request("sessions.list", params.toString(), 15_000)
      _sessions.value = parseSessions(res)
    } catch (_: Throwable) {
      // best-effort
    }
  }

  private suspend fun pollHealthIfNeeded(force: Boolean) {
    val now = System.currentTimeMillis()
    val last = lastHealthPollAtMs
    if (!force && last != null && now - last < 10_000) return
    lastHealthPollAtMs = now
    try {
      request("health", null, 15_000)
      _healthOk.value = true
    } catch (_: Throwable) {
      _healthOk.value = false
    }
  }

  private fun handleChatEvent(payloadJson: String) {
    val payload = json.parseToJsonElement(payloadJson).asObjectOrNull() ?: return
    val sessionKey = payload["sessionKey"].asStringOrNull()?.trim()
    if (!sessionKey.isNullOrEmpty() && sessionKey != _sessionKey.value) return

    val runId = payload["runId"].asStringOrNull()
    val isPending =
      if (runId != null) synchronized(pendingRuns) { pendingRuns.contains(runId) } else true

    val state = payload["state"].asStringOrNull()
    when (state) {
      "delta" -> {
        // Only show streaming text for runs we initiated
        if (!isPending) return
        val text = parseAssistantDeltaText(payload)
        if (!text.isNullOrEmpty()) {
          runId?.let {
            pendingAssistantPreviewByRunId[it] = text
            cancelPendingSilenceProbe(it)
            markPendingRunReplying(it)
          }
          _streamingAssistantText.value = text
          trace(
            source = TraceSource.Agent,
            eventType = TraceEventType.AgentMessageReceived,
            description = "Assistant reply delta received",
            payloadText = payloadJson,
            sessionId = sessionKey ?: _sessionKey.value,
            timestampMs = payload["ts"].asLongOrNull() ?: System.currentTimeMillis(),
          )
        }
      }
      "final", "aborted", "error" -> {
        runId?.let {
          updatePendingDiagnostics(it) { existing ->
            (existing ?: return@updatePendingDiagnostics null).copy(
              terminalEventAtMs = System.currentTimeMillis(),
            )
          }
        }
        val finalMessage =
          if (state == "final") {
            parseChatEventMessage(payload["message"]) ?: runId?.let(::previewMessageForRun)
          } else {
            null
          }
        if (state == "error") {
          _errorText.value = payload["errorMessage"].asStringOrNull() ?: "Chat failed"
          trace(
            source = TraceSource.Agent,
            eventType = TraceEventType.Error,
            severity = TraceSeverity.Error,
            description = _errorText.value ?: "Chat failed",
            payloadText = payloadJson,
            sessionId = sessionKey ?: _sessionKey.value,
            timestampMs = payload["ts"].asLongOrNull() ?: System.currentTimeMillis(),
          )
        }
        pendingToolCallsById.clear()
        publishPendingToolCalls()
        when (state) {
          "final" -> {
            if (runId != null && finalMessage != null && isDuplicateCompletedRun(runId, finalMessage)) {
              _streamingAssistantText.value = null
              finalizePendingDiagnostics(
                runId = runId,
                status = ConversationTurnStatus.Complete,
                completionSource = "chat-event",
              )
              clearPendingRun(runId)
              return
            }
            if (finalMessage != null) {
              trace(
                source = TraceSource.Agent,
                eventType = TraceEventType.AgentResponseSent,
                description = "Assistant response finalized",
                payloadText = payloadJson,
                sessionId = sessionKey ?: _sessionKey.value,
                timestampMs = payload["ts"].asLongOrNull() ?: System.currentTimeMillis(),
              )
              completeRunWithMessage(runId, finalMessage, completionSource = "chat-event")
              return
            }
            if (runId != null) {
              _streamingAssistantText.value = null
              if (!supportsAgentWait()) {
                armPendingHistoryRecovery(
                  runId = runId,
                  initialDelayMs = 500L,
                  intervalMs = 1_000L,
                  maxAttempts = historyRecoveryAttemptsAfterSilence,
                  failureMessage = "Reply delayed; refresh chat to reload history.",
                )
              }
              return
            }
            _streamingAssistantText.value = null
          }
          "aborted" -> {
            _streamingAssistantText.value = null
            trace(
              source = TraceSource.Agent,
              eventType = TraceEventType.Error,
              severity = TraceSeverity.Warning,
              description = "Response aborted",
              payloadText = payloadJson,
              sessionId = sessionKey ?: _sessionKey.value,
              timestampMs = payload["ts"].asLongOrNull() ?: System.currentTimeMillis(),
            )
            if (runId != null) {
              finalizePendingDiagnostics(
                runId = runId,
                status = ConversationTurnStatus.Error,
                completionSource = "chat-error",
                errorMessage = "Response aborted",
              )
              clearPendingRun(runId)
            } else {
              clearPendingRuns()
            }
          }
          "error" -> {
            _streamingAssistantText.value = null
            if (runId != null) {
              finalizePendingDiagnostics(
                runId = runId,
                status = ConversationTurnStatus.Error,
                completionSource = "chat-error",
                errorMessage = _errorText.value,
              )
              clearPendingRun(runId)
            } else {
              clearPendingRuns()
            }
          }
        }
      }
    }
  }

  private fun handleAgentEvent(payloadJson: String) {
    val payload = json.parseToJsonElement(payloadJson).asObjectOrNull() ?: return
    val sessionKey = payload["sessionKey"].asStringOrNull()?.trim()
    if (!sessionKey.isNullOrEmpty() && sessionKey != _sessionKey.value) return

    val stream = payload["stream"].asStringOrNull()
    val data = payload["data"].asObjectOrNull()

    when (stream) {
      "assistant" -> {
        val runId = payload["runId"].asStringOrNull()
        if (data?.get("type").asStringOrNull()?.equals("thinking", ignoreCase = true) == true) {
          trace(
            source = TraceSource.Agent,
            eventType = TraceEventType.AgentThinking,
            description = "Assistant is thinking",
            payloadText = payloadJson,
            sessionId = sessionKey ?: _sessionKey.value,
            timestampMs = payload["ts"].asLongOrNull() ?: System.currentTimeMillis(),
          )
          return
        }
        val text = data?.get("text")?.asStringOrNull()
        if (!text.isNullOrEmpty()) {
          runId?.let {
            pendingAssistantPreviewByRunId[it] = text
            cancelPendingSilenceProbe(it)
            markPendingRunReplying(it)
          }
          _streamingAssistantText.value = text
          trace(
            source = TraceSource.Agent,
            eventType = TraceEventType.AgentMessageReceived,
            description = "Assistant agent stream received",
            payloadText = payloadJson,
            sessionId = sessionKey ?: _sessionKey.value,
            timestampMs = payload["ts"].asLongOrNull() ?: System.currentTimeMillis(),
          )
        }
      }
      "tool" -> {
        val phase = data?.get("phase")?.asStringOrNull()
        val name = data?.get("name")?.asStringOrNull()
        val toolCallId = data?.get("toolCallId")?.asStringOrNull()
        if (phase.isNullOrEmpty() || name.isNullOrEmpty() || toolCallId.isNullOrEmpty()) return

        val ts = payload["ts"].asLongOrNull() ?: System.currentTimeMillis()
        if (phase == "start") {
          val args = data?.get("args").asObjectOrNull()
          pendingToolCallsById[toolCallId] =
            ChatPendingToolCall(
              toolCallId = toolCallId,
              name = name,
              args = args,
              startedAtMs = ts,
              isError = null,
            )
          publishPendingToolCalls()
          trace(
            source = TraceSource.Agent,
            eventType = TraceEventType.ToolCallStarted,
            description = "Tool call started: $name",
            payloadText = payloadJson,
            sessionId = sessionKey ?: _sessionKey.value,
            timestampMs = ts,
          )
        } else if (phase == "result") {
          pendingToolCallsById.remove(toolCallId)
          publishPendingToolCalls()
          trace(
            source = TraceSource.Agent,
            eventType = TraceEventType.ToolCallFinished,
            description = "Tool call finished: $name",
            payloadText = payloadJson,
            sessionId = sessionKey ?: _sessionKey.value,
            timestampMs = ts,
          )
        }
      }
      "error" -> {
        _errorText.value = "Event stream interrupted; try refreshing."
        pendingToolCallsById.clear()
        publishPendingToolCalls()
        _streamingAssistantText.value = null
        resumePendingRecoveries()
        trace(
          source = TraceSource.Agent,
          eventType = TraceEventType.Error,
          severity = TraceSeverity.Warning,
          description = "Agent event stream interrupted",
          payloadText = payloadJson,
          sessionId = sessionKey ?: _sessionKey.value,
          timestampMs = payload["ts"].asLongOrNull() ?: System.currentTimeMillis(),
        )
      }
    }
  }

  private fun parseAssistantDeltaText(payload: JsonObject): String? {
    val message = payload["message"].asObjectOrNull() ?: return null
    if (message["role"].asStringOrNull() != "assistant") return null
    val content = message["content"].asArrayOrNull() ?: return null
    for (item in content) {
      val obj = item.asObjectOrNull() ?: continue
      if (obj["type"].asStringOrNull() != "text") continue
      val text = obj["text"].asStringOrNull()
      if (!text.isNullOrEmpty()) {
        return text
      }
    }
    return null
  }

  private fun parseChatEventMessage(messageEl: JsonElement?): ChatMessage? {
    val message = messageEl.asObjectOrNull() ?: return null
    val role = message["role"].asStringOrNull() ?: return null
    val content = message["content"].asArrayOrNull()?.mapNotNull(::parseMessageContent) ?: emptyList()
    if (content.isEmpty()) return null
    val timestampMs = message["timestamp"].asLongOrNull() ?: System.currentTimeMillis()
    return ChatMessage(
      id = UUID.randomUUID().toString(),
      role = role,
      content = content,
      timestampMs = timestampMs,
    )
  }

  private fun trace(
    source: TraceSource,
    eventType: TraceEventType,
    severity: TraceSeverity = TraceSeverity.Info,
    description: String,
    payloadText: String? = null,
    sessionId: String? = null,
    agentName: String? = null,
    deviceId: String? = null,
    timestampMs: Long = System.currentTimeMillis(),
  ) {
    traceSink(source, eventType, severity, description, payloadText, sessionId, agentName, deviceId, timestampMs)
  }

  private fun publishPendingToolCalls() {
    _pendingToolCalls.value =
      pendingToolCallsById.values.sortedBy { it.startedAtMs }
  }

  private fun trackPendingRun(runId: String, snapshot: PendingRunSnapshot) {
    pendingRunSnapshots[runId] = snapshot
    synchronized(pendingRuns) {
      pendingRuns.add(runId)
    }
    refreshPendingUiState()
  }

  private fun trackPendingDiagnostics(runId: String, diagnostics: ConversationTurnDiagnostics) {
    pendingDiagnosticsByRunId[runId] = diagnostics
    lastDiagnosticsBySessionKey[diagnostics.sessionKey] = diagnostics
    publishTurnDiagnostics()
  }

  private fun updatePendingDiagnostics(
    runId: String,
    transform: (ConversationTurnDiagnostics?) -> ConversationTurnDiagnostics?,
  ) {
    val updated = transform(pendingDiagnosticsByRunId[runId])
    if (updated == null) {
      pendingDiagnosticsByRunId.remove(runId)
      publishTurnDiagnostics()
      return
    }
    pendingDiagnosticsByRunId[runId] = updated
    lastDiagnosticsBySessionKey[updated.sessionKey] = updated
    publishTurnDiagnostics()
  }

  private fun markPendingRunReplying(runId: String) {
    updatePendingDiagnostics(runId) { existing ->
      val current = existing ?: return@updatePendingDiagnostics null
      val firstReplyAt = current.firstReplyAtMs ?: System.currentTimeMillis()
      current.copy(
        status = ConversationTurnStatus.Streaming,
        firstReplyAtMs = firstReplyAt,
      )
    }
  }

  private fun finalizePendingDiagnostics(
    runId: String,
    status: ConversationTurnStatus,
    completionSource: String,
    errorMessage: String? = null,
  ) {
    val existing = pendingDiagnosticsByRunId.remove(runId) ?: return
    val now = System.currentTimeMillis()
    val resolved =
      existing.copy(
        status = status,
        completionSource = completionSource,
        errorMessage = errorMessage,
        terminalEventAtMs = existing.terminalEventAtMs ?: now,
        resolvedAtMs = now,
      )
    lastDiagnosticsBySessionKey[resolved.sessionKey] = resolved
    publishTurnDiagnostics()
  }

  private fun publishTurnDiagnostics() {
    val currentSession = _sessionKey.value
    val diagnostics =
      pendingDiagnosticsByRunId.values
        .filter { it.sessionKey == currentSession }
        .maxByOrNull { it.startedAtMs }
        ?: lastDiagnosticsBySessionKey[currentSession]
    _turnDiagnostics.value = diagnostics
  }

  private fun armPendingRunTimeout(runId: String) {
    pendingRunTimeoutJobs[runId]?.cancel()
    pendingRunTimeoutJobs[runId] =
      scope.launch {
        delay(pendingRunTimeoutMs)
        val stillPending =
          synchronized(pendingRuns) {
            pendingRuns.contains(runId)
          }
        if (!stillPending) return@launch
        val preview = previewMessageForRun(runId)
        if (preview != null) {
          completeRunWithMessage(runId, preview, completionSource = "timeout-preview")
          return@launch
        }
        if (supportsAgentWait()) {
          armPendingAgentWait(runId = runId, initialDelayMs = 0L, timeoutErrorMessage = "Reply timed out; try again or refresh.")
        } else {
          armPendingHistoryRecovery(
            runId = runId,
            initialDelayMs = 0L,
            intervalMs = 1_000L,
            maxAttempts = historyRecoveryAttemptsAfterTimeout,
            failureMessage = "Reply timed out; try again or refresh.",
          )
        }
      }
  }

  private fun startPendingRecoveryStrategy(runId: String) {
    if (supportsAgentWait()) {
      armPendingAgentWait(runId = runId, initialDelayMs = 0L, timeoutErrorMessage = "Reply timed out; try again or refresh.")
    } else if (supportsChatSubscribe()) {
      armPendingSilenceProbe(runId)
    } else {
      armPendingHistoryRecovery(
        runId = runId,
        initialDelayMs = historyRecoveryIntervalMs,
        intervalMs = historyRecoveryIntervalMs,
        maxAttempts = historyRecoveryAttemptsWithoutSubscribe,
        failureMessage = "Reply timed out; try again or refresh.",
      )
    }
  }

  private fun armPendingSilenceProbe(runId: String) {
    pendingSilenceProbeJobs[runId]?.cancel()
    pendingSilenceProbeJobs[runId] =
      scope.launch {
        delay(liveEventSilenceProbeMs)
        val stillPending =
          synchronized(pendingRuns) {
            pendingRuns.contains(runId)
          }
        if (!stillPending) return@launch
        if (pendingAssistantPreviewByRunId[runId].isNullOrBlank()) {
          armPendingHistoryRecovery(
            runId = runId,
            initialDelayMs = 0L,
            intervalMs = historyRecoveryIntervalMs,
            maxAttempts = historyRecoveryAttemptsAfterSilence,
            failureMessage = null,
          )
        }
      }
  }

  private fun cancelPendingSilenceProbe(runId: String) {
    pendingSilenceProbeJobs.remove(runId)?.cancel()
  }

  private fun armPendingAgentWait(
    runId: String,
    initialDelayMs: Long,
    timeoutErrorMessage: String,
  ) {
    pendingAgentWaitJobs[runId]?.cancel()
    pendingAgentWaitJobs[runId] =
      scope.launch {
        if (initialDelayMs > 0L) {
          delay(initialDelayMs)
        }
        while (isRunPending(runId)) {
          val result =
            try {
              waitForRunCompletion(runId)
            } catch (err: Throwable) {
              if (!isRunPending(runId)) return@launch
              val message = err.message?.trim().orEmpty().lowercase()
              if (message.contains("not connected")) {
                delay(1_500L)
                continue
              }
              if (message.contains("method") && message.contains("not")) {
                armPendingHistoryRecovery(
                  runId = runId,
                  initialDelayMs = 0L,
                  intervalMs = historyRecoveryIntervalMs,
                  maxAttempts = historyRecoveryAttemptsWithoutSubscribe,
                  failureMessage = timeoutErrorMessage,
                )
                return@launch
              }
              delay(1_250L)
              continue
            }

          when (result.status) {
            "ok" -> {
              if (tryCompleteRunFromHistory(runId, completionSource = "agent-wait")) {
                return@launch
              }
              val preview = previewMessageForRun(runId)
              if (preview != null) {
                completeRunWithMessage(runId, preview, completionSource = "agent-wait")
                return@launch
              }
              armPendingHistoryRecovery(
                runId = runId,
                initialDelayMs = 250L,
                intervalMs = 750L,
                maxAttempts = 10,
                failureMessage = "Reply delayed; refresh chat to reload history.",
              )
              return@launch
            }

            "error" -> {
              if (tryCompleteRunFromHistory(runId, completionSource = "agent-wait")) {
                return@launch
              }
              val preview = previewMessageForRun(runId)
              if (preview != null) {
                completeRunWithMessage(runId, preview, completionSource = "agent-wait")
              } else {
                finalizePendingDiagnostics(
                  runId = runId,
                  status = ConversationTurnStatus.Error,
                  completionSource = "chat-error",
                  errorMessage = result.errorMessage ?: "Chat failed",
                )
                clearPendingRun(runId)
                _streamingAssistantText.value = null
                _errorText.value = result.errorMessage ?: "Chat failed"
              }
              return@launch
            }

            "timeout" -> {
              delay(250L)
            }

            else -> {
              delay(750L)
            }
          }
        }
      }
  }

  private fun armPendingHistoryRecovery(
    runId: String,
    initialDelayMs: Long,
    intervalMs: Long,
    maxAttempts: Int,
    failureMessage: String?,
  ) {
    if (maxAttempts <= 0) return
    pendingHistoryRecoveryJobs[runId]?.cancel()
    pendingHistoryRecoveryJobs[runId] =
      scope.launch {
        if (initialDelayMs > 0L) {
          delay(initialDelayMs)
        }
        repeat(maxAttempts) { attempt ->
          val stillPending =
            synchronized(pendingRuns) {
              pendingRuns.contains(runId)
            }
          if (!stillPending) return@launch
          try {
            if (tryCompleteRunFromHistory(runId)) {
              return@launch
            }
          } catch (_: Throwable) {
            // best-effort fallback while live event delivery is incomplete
          }
          if (attempt < maxAttempts - 1) {
            delay(intervalMs)
          }
        }
        val unresolved =
          synchronized(pendingRuns) {
            pendingRuns.contains(runId)
          }
        if (!unresolved) return@launch
        val preview = previewMessageForRun(runId)
        if (preview != null) {
          completeRunWithMessage(runId, preview, completionSource = "stream-preview")
          return@launch
        }
        if (!failureMessage.isNullOrBlank()) {
          finalizePendingDiagnostics(
            runId = runId,
            status = ConversationTurnStatus.Error,
            completionSource = "history-recovery",
            errorMessage = failureMessage,
          )
          clearPendingRun(runId)
          _streamingAssistantText.value = null
          _errorText.value = failureMessage
        }
      }
  }

  private fun clearPendingRun(runId: String) {
    pendingRunTimeoutJobs.remove(runId)?.cancel()
    pendingHistoryRecoveryJobs.remove(runId)?.cancel()
    pendingSilenceProbeJobs.remove(runId)?.cancel()
    pendingAgentWaitJobs.remove(runId)?.cancel()
    pendingRunSnapshots.remove(runId)
    pendingDiagnosticsByRunId.remove(runId)
    pendingAssistantPreviewByRunId.remove(runId)
    synchronized(pendingRuns) {
      pendingRuns.remove(runId)
    }
    refreshPendingUiState()
  }

  private fun remapPendingRun(oldRunId: String, newRunId: String, snapshot: PendingRunSnapshot) {
    pendingRunTimeoutJobs.remove(oldRunId)?.cancel()
    pendingHistoryRecoveryJobs.remove(oldRunId)
    pendingSilenceProbeJobs.remove(oldRunId)?.cancel()
    pendingAgentWaitJobs.remove(oldRunId)?.cancel()
    pendingRunSnapshots.remove(oldRunId)
    val diagnostics = pendingDiagnosticsByRunId.remove(oldRunId)
    pendingAssistantPreviewByRunId.remove(oldRunId)
    synchronized(pendingRuns) {
      pendingRuns.remove(oldRunId)
    }
    pendingRunSnapshots[newRunId] = snapshot
    diagnostics?.let { pendingDiagnosticsByRunId[newRunId] = it.copy(runId = newRunId) }
    synchronized(pendingRuns) {
      pendingRuns.add(newRunId)
    }
    refreshPendingUiState()
  }

  private fun clearPendingRuns() {
    for ((_, job) in pendingRunTimeoutJobs) {
      job.cancel()
    }
    pendingRunTimeoutJobs.clear()
    for ((_, job) in pendingHistoryRecoveryJobs) {
      job.cancel()
    }
    pendingHistoryRecoveryJobs.clear()
    for ((_, job) in pendingSilenceProbeJobs) {
      job.cancel()
    }
    pendingSilenceProbeJobs.clear()
    for ((_, job) in pendingAgentWaitJobs) {
      job.cancel()
    }
    pendingAgentWaitJobs.clear()
    pendingRunSnapshots.clear()
    pendingDiagnosticsByRunId.clear()
    pendingAssistantPreviewByRunId.clear()
    synchronized(pendingRuns) {
      pendingRuns.clear()
    }
    refreshPendingUiState()
  }

  private fun pausePendingRecoveries() {
    for ((_, job) in pendingRunTimeoutJobs) {
      job.cancel()
    }
    pendingRunTimeoutJobs.clear()
    for ((_, job) in pendingHistoryRecoveryJobs) {
      job.cancel()
    }
    pendingHistoryRecoveryJobs.clear()
    for ((_, job) in pendingSilenceProbeJobs) {
      job.cancel()
    }
    pendingSilenceProbeJobs.clear()
    for ((_, job) in pendingAgentWaitJobs) {
      job.cancel()
    }
    pendingAgentWaitJobs.clear()
  }

  private fun resumePendingRecoveries() {
    val currentSession = _sessionKey.value
    for ((runId, snapshot) in pendingRunSnapshots) {
      if (snapshot.sessionKey != currentSession) continue
      if (!pendingRunTimeoutJobs.containsKey(runId)) {
        armPendingRunTimeout(runId)
      }
      when {
        supportsAgentWait() -> {
          if (!pendingAgentWaitJobs.containsKey(runId)) {
            armPendingAgentWait(runId = runId, initialDelayMs = 0L, timeoutErrorMessage = "Reply timed out; try again or refresh.")
          }
        }

        supportsChatSubscribe() -> {
          if (!pendingSilenceProbeJobs.containsKey(runId) && !pendingHistoryRecoveryJobs.containsKey(runId)) {
            armPendingSilenceProbe(runId)
          }
        }

        !pendingHistoryRecoveryJobs.containsKey(runId) -> {
          armPendingHistoryRecovery(
            runId = runId,
            initialDelayMs = historyRecoveryIntervalMs,
            intervalMs = historyRecoveryIntervalMs,
            maxAttempts = historyRecoveryAttemptsWithoutSubscribe,
            failureMessage = "Reply timed out; try again or refresh.",
          )
        }
      }
    }
    refreshPendingUiState()
  }

  private suspend fun subscribeChatIfNeeded(sessionKey: String) {
    if (!supportsChatSubscribe()) return
    val key = sessionKey.trim()
    if (key.isEmpty()) return
    if (chatSubscribedSessionKey == key) return
    val sent = sendNodeEvent("chat.subscribe", """{"sessionKey":"$key"}""")
    if (sent) {
      chatSubscribedSessionKey = key
    }
  }

  private suspend fun tryCompleteRunFromHistory(
    runId: String,
    completionSource: String = "history-recovery",
  ): Boolean {
    val snapshot = pendingRunSnapshots[runId] ?: return false
    val historyJson = request("chat.history", """{"sessionKey":"${snapshot.sessionKey}"}""", 15_000)
    val history = parseHistory(historyJson, sessionKey = snapshot.sessionKey)
    val resolvedAssistant = findResolvedAssistantReply(history, snapshot) ?: run {
      return false
    }
    rememberCompletedRun(runId, resolvedAssistant)
    if (snapshot.sessionKey == _sessionKey.value) {
      _sessionId.value = history.sessionId
      history.thinkingLevel?.trim()?.takeIf { it.isNotEmpty() }?.let { _thinkingLevel.value = it }
      _messages.value = mergeHistoryWithPending(history.messages, sessionKey = snapshot.sessionKey)
    }
    finalizePendingDiagnostics(
      runId = runId,
      status = ConversationTurnStatus.Complete,
      completionSource = completionSource,
    )
    clearPendingRun(runId)
    if (snapshot.sessionKey == _sessionKey.value) {
      _streamingAssistantText.value = null
    }
    _errorText.value = null
    return true
  }

  private fun parseHistory(historyJson: String, sessionKey: String): ChatHistory {
    val root = json.parseToJsonElement(historyJson).asObjectOrNull() ?: return ChatHistory(sessionKey, null, null, emptyList())
    val sid = root["sessionId"].asStringOrNull()
    val thinkingLevel = root["thinkingLevel"].asStringOrNull()
    val array = root["messages"].asArrayOrNull() ?: JsonArray(emptyList())

    val messages =
      array.mapNotNull { item ->
        val obj = item.asObjectOrNull() ?: return@mapNotNull null
        val role = obj["role"].asStringOrNull() ?: return@mapNotNull null
        val content = obj["content"].asArrayOrNull()?.mapNotNull(::parseMessageContent) ?: emptyList()
        val ts = obj["timestamp"].asLongOrNull()
        ChatMessage(
          id = UUID.randomUUID().toString(),
          role = role,
          content = content,
          timestampMs = ts,
        )
      }

    return ChatHistory(sessionKey = sessionKey, sessionId = sid, thinkingLevel = thinkingLevel, messages = messages)
  }

  private fun parseMessageContent(el: JsonElement): ChatMessageContent? {
    val obj = el.asObjectOrNull() ?: return null
    val type = obj["type"].asStringOrNull() ?: "text"
    return if (type == "text") {
      ChatMessageContent(type = "text", text = obj["text"].asStringOrNull())
    } else {
      ChatMessageContent(
        type = type,
        mimeType = obj["mimeType"].asStringOrNull(),
        fileName = obj["fileName"].asStringOrNull(),
        base64 = obj["content"].asStringOrNull(),
      )
    }
  }

  private fun parseSessions(jsonString: String): List<ChatSessionEntry> {
    val root = json.parseToJsonElement(jsonString).asObjectOrNull() ?: return emptyList()
    val sessions = root["sessions"].asArrayOrNull() ?: return emptyList()
    return sessions.mapNotNull { item ->
      val obj = item.asObjectOrNull() ?: return@mapNotNull null
      val key = obj["key"].asStringOrNull()?.trim().orEmpty()
      if (key.isEmpty()) return@mapNotNull null
      val updatedAt = obj["updatedAt"].asLongOrNull()
      val displayName = obj["displayName"].asStringOrNull()?.trim()
      ChatSessionEntry(key = key, updatedAtMs = updatedAt, displayName = displayName)
    }
  }

  private fun buildChatSendParams(
    sessionKey: String,
    message: String,
    thinking: String,
    attachments: List<OutgoingAttachment>,
    idempotencyKey: String,
  ): JsonObject {
    return buildJsonObject {
      put("sessionKey", JsonPrimitive(sessionKey))
      put("message", JsonPrimitive(message))
      put("thinking", JsonPrimitive(thinking))
      put("timeoutMs", JsonPrimitive(30_000))
      put("idempotencyKey", JsonPrimitive(idempotencyKey))
      if (attachments.isNotEmpty()) {
        put(
          "attachments",
          JsonArray(
            attachments.map { att ->
              buildJsonObject {
                put("type", JsonPrimitive(att.type))
                put("mimeType", JsonPrimitive(att.mimeType))
                put("fileName", JsonPrimitive(att.fileName))
                put("content", JsonPrimitive(att.base64))
              }
            },
          ),
        )
      }
    }
  }

  private fun parseChatSendAck(resJson: String): ChatSendAck {
    return try {
      val obj = json.parseToJsonElement(resJson).asObjectOrNull()
      ChatSendAck(
        runId = obj?.get("runId").asStringOrNull(),
        status = obj?.get("status").asStringOrNull()?.trim()?.lowercase(),
      )
    } catch (_: Throwable) {
      ChatSendAck(runId = null, status = null)
    }
  }

  private fun parseAbortResponse(resJson: String): Boolean? {
    return try {
      json.parseToJsonElement(resJson).asObjectOrNull()?.get("aborted").asBooleanOrNull()
    } catch (_: Throwable) {
      null
    }
  }

  private fun appendSystemMessage(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    _messages.value =
      _messages.value +
        ChatMessage(
          id = UUID.randomUUID().toString(),
          role = "system",
          content = listOf(ChatMessageContent(type = "text", text = trimmed)),
          timestampMs = System.currentTimeMillis(),
        )
  }

  private fun finalizeAbortedRun(runId: String) {
    finalizePendingDiagnostics(
      runId = runId,
      status = ConversationTurnStatus.Error,
      completionSource = "chat-abort",
      errorMessage = "Agent was aborted.",
    )
    clearPendingRun(runId)
    pendingAssistantPreviewByRunId.remove(runId)
  }

  private suspend fun waitForRunCompletion(runId: String): AgentWaitResult {
    val params =
      buildJsonObject {
        put("runId", JsonPrimitive(runId))
        put("timeoutMs", JsonPrimitive(30_000))
      }
    val response = request("agent.wait", params.toString(), 35_000)
    return try {
      val obj = json.parseToJsonElement(response).asObjectOrNull()
      val errorMessage =
        obj?.get("error")
          .asObjectOrNull()
          ?.get("message")
          .asStringOrNull()
      AgentWaitResult(
        status = obj?.get("status").asStringOrNull()?.trim()?.lowercase(),
        errorMessage = errorMessage,
      )
    } catch (_: Throwable) {
      AgentWaitResult(status = null, errorMessage = null)
    }
  }

  private fun armPendingSendRecovery(
    runId: String,
    sessionKey: String,
    message: String,
    thinking: String,
    attachments: List<OutgoingAttachment>,
  ) {
    pendingHistoryRecoveryJobs[runId]?.cancel()
    pendingHistoryRecoveryJobs[runId] =
      scope.launch {
        while (isRunPending(runId)) {
          if (!_healthOk.value) {
            delay(1_250L)
            continue
          }
          try {
            val params = buildChatSendParams(sessionKey, message, thinking, attachments, runId)
            val response = request("chat.send", params.toString(), 15_000)
            val ack = parseChatSendAck(response)
            val actualRunId = ack.runId ?: runId
            val snapshot = pendingRunSnapshots[runId] ?: return@launch
            updatePendingDiagnostics(runId) { existing ->
              (existing ?: return@updatePendingDiagnostics null).copy(
                runId = actualRunId,
                status = ConversationTurnStatus.Waiting,
                sendAckAtMs = System.currentTimeMillis(),
              )
            }
            if (actualRunId != runId) {
              remapPendingRun(runId, actualRunId, snapshot)
              armPendingRunTimeout(actualRunId)
              startPendingRecoveryStrategy(actualRunId)
            } else {
              startPendingRecoveryStrategy(runId)
            }
            if (ack.status == "ok") {
              if (!tryCompleteRunFromHistory(actualRunId)) {
                armPendingHistoryRecovery(
                  runId = actualRunId,
                  initialDelayMs = 250L,
                  intervalMs = 750L,
                  maxAttempts = 8,
                  failureMessage = "Reply delayed; refresh chat to reload history.",
                )
              }
            }
            _errorText.value = null
            return@launch
          } catch (_: Throwable) {
            delay(1_500L)
          }
        }
      }
  }

  private fun isRunPending(runId: String): Boolean {
    return synchronized(pendingRuns) { pendingRuns.contains(runId) }
  }

  private fun refreshPendingUiState() {
    _pendingRunCount.value = pendingRunSnapshots.values.count { it.sessionKey == _sessionKey.value }
    _streamingAssistantText.value = currentStreamingPreviewForSession(_sessionKey.value)
    publishTurnDiagnostics()
  }

  private fun currentStreamingPreviewForSession(sessionKey: String): String? {
    val runId =
      pendingRunSnapshots.entries
        .filter { it.value.sessionKey == sessionKey }
        .maxByOrNull { it.value.startedAtMs }
        ?.key
    return runId?.let { pendingAssistantPreviewByRunId[it] }?.trim()?.takeIf { it.isNotEmpty() }
  }

  private fun mergeHistoryWithPending(messages: List<ChatMessage>, sessionKey: String): List<ChatMessage> {
    val pendingForSession =
      pendingRunSnapshots.values
        .filter { it.sessionKey == sessionKey }
        .sortedBy { it.startedAtMs }
    if (pendingForSession.isEmpty()) return messages

    val merged = messages.toMutableList()
    val existingSignatures = merged.mapTo(mutableSetOf(), ::messageContentSignature)
    for (snapshot in pendingForSession) {
      val signature = messageContentSignature(snapshot.optimisticUserMessage)
      if (signature !in existingSignatures) {
        merged += snapshot.optimisticUserMessage
        existingSignatures += signature
      }
    }
    return merged
  }

  private fun parseRunId(resJson: String): String? {
    return try {
      json.parseToJsonElement(resJson).asObjectOrNull()?.get("runId").asStringOrNull()
    } catch (_: Throwable) {
      null
    }
  }

  private fun normalizeThinking(raw: String): String? {
    return when (raw.trim().lowercase()) {
      "off" -> "off"
      "minimal" -> "minimal"
      "low" -> "low"
      "medium" -> "medium"
      "high" -> "high"
      "adaptive" -> "adaptive"
      "xhigh" -> "xhigh"
      else -> null
    }
  }

  private fun findResolvedAssistantReply(history: ChatHistory, snapshot: PendingRunSnapshot): ChatMessage? {
    for (index in history.messages.indices.reversed()) {
      val message = history.messages[index]
      if (message.role != "assistant") continue
      val fingerprintChanged = assistantFingerprint(message) != snapshot.baselineAssistantFingerprint
      val appendedAfterBaseline = index >= snapshot.baselineMessageCount
      val isNewerByTimestamp = normalizeTimestampMs(message.timestampMs) > snapshot.startedAtMs
      if (fingerprintChanged || appendedAfterBaseline || isNewerByTimestamp) {
        return message
      }
    }
    return null
  }

  private fun appendMessageIfNew(message: ChatMessage) {
    val last = _messages.value.lastOrNull()
    if (last != null && messageFingerprint(last) == messageFingerprint(message)) {
      return
    }
    _messages.value = _messages.value + message
  }

  private fun completeRunWithMessage(
    runId: String?,
    message: ChatMessage,
    completionSource: String = "chat-event",
  ) {
    val shouldAppend =
      if (runId == null) {
        true
      } else {
        pendingRunSnapshots[runId]?.sessionKey == _sessionKey.value
      }
    if (runId != null) {
      rememberCompletedRun(runId, message)
      finalizePendingDiagnostics(
        runId = runId,
        status = ConversationTurnStatus.Complete,
        completionSource = completionSource,
      )
      clearPendingRun(runId)
    } else {
      clearPendingRuns()
    }
    if (shouldAppend) {
      _streamingAssistantText.value = null
    }
    _errorText.value = null
    if (shouldAppend) {
      appendMessageIfNew(message)
    }
  }

  private fun previewMessageForRun(runId: String): ChatMessage? {
    val text = pendingAssistantPreviewByRunId[runId]?.trim().orEmpty()
    if (text.isEmpty()) return null
    return ChatMessage(
      id = UUID.randomUUID().toString(),
      role = "assistant",
      content = listOf(ChatMessageContent(type = "text", text = text)),
      timestampMs = System.currentTimeMillis(),
    )
  }

  private fun isDuplicateCompletedRun(runId: String, message: ChatMessage): Boolean {
    val fingerprint = completedReplyFingerprint(message)
    synchronized(completedRunReplyFingerprints) {
      return completedRunReplyFingerprints[runId] == fingerprint
    }
  }

  private fun rememberCompletedRun(runId: String, message: ChatMessage) {
    val fingerprint = completedReplyFingerprint(message)
    synchronized(completedRunReplyFingerprints) {
      completedRunReplyFingerprints[runId] = fingerprint
      while (completedRunReplyFingerprints.size > maxCachedCompletedRuns) {
        val eldest = completedRunReplyFingerprints.entries.firstOrNull()?.key ?: break
        completedRunReplyFingerprints.remove(eldest)
      }
    }
  }

  private fun completedReplyFingerprint(message: ChatMessage): String {
    val parts =
      message.content.joinToString("|") { content ->
        buildString {
          append(content.type)
          append(':')
          append(content.text?.trim().orEmpty())
          append(':')
          append(content.fileName?.trim().orEmpty())
        }
      }
    return "${message.role}|$parts"
  }

  private fun shouldKeepPendingAfterSendFailure(err: Throwable): Boolean {
    val message = err.message?.trim()?.lowercase().orEmpty()
    if (message.isEmpty()) return true
    if (message.contains("unauthorized") || message.contains("forbidden") || message.contains("auth")) return false
    if (message.contains("invalid") || message.contains("validation") || message.contains("parse")) return false
    if (message.contains("not connected")) return false
    return true
  }

  private fun assistantFingerprint(message: ChatMessage?): String? {
    if (message == null || message.role != "assistant") return null
    return messageFingerprint(message)
  }

  private fun messageContentSignature(message: ChatMessage): String {
    val parts =
      message.content.joinToString("|") { content ->
        buildString {
          append(content.type)
          append(':')
          append(content.text?.trim().orEmpty())
          append(':')
          append(content.fileName?.trim().orEmpty())
          append(':')
          append(content.mimeType?.trim().orEmpty())
        }
      }
    return "${message.role}|$parts"
  }

  private fun messageFingerprint(message: ChatMessage): String {
    val text =
      message.content
        .filter { it.type == "text" }
        .joinToString("\n") { it.text?.trim().orEmpty() }
        .trim()
    return "${message.role}|${normalizeTimestampMs(message.timestampMs)}|$text"
  }

  private fun normalizeTimestampMs(timestampMs: Long?): Long {
    val raw = timestampMs ?: return 0L
    return if (raw in 1L until 100_000_000_000L) raw * 1000L else raw
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
