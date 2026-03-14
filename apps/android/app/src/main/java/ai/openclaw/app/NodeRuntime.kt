package ai.openclaw.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ai.openclaw.app.chat.ChatController
import ai.openclaw.app.chat.supportsAgentWait
import ai.openclaw.app.chat.supportsLiveChatEvents
import ai.openclaw.app.chat.ChatMessage
import ai.openclaw.app.chat.ChatPendingToolCall
import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.chat.OutgoingAttachment
import ai.openclaw.app.diagnostics.ConversationTurnDiagnostics
import ai.openclaw.app.gateway.DeviceAuthStore
import ai.openclaw.app.gateway.DeviceIdentityStore
import ai.openclaw.app.gateway.GatewayDiscovery
import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.gateway.GatewayHelloSnapshot
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.gateway.GatewayTlsParams
import ai.openclaw.app.gateway.isGatewayTlsSystemTrusted
import ai.openclaw.app.gateway.isSystemTlsTrustCandidateHost
import ai.openclaw.app.gateway.parseGatewayFingerprint
import ai.openclaw.app.gateway.probeGatewayTlsFingerprint
import ai.openclaw.app.node.*
import ai.openclaw.app.protocol.OpenClawCanvasA2UIAction
import ai.openclaw.app.trace.GatewayLogCollectionMode
import ai.openclaw.app.trace.TraceEvent
import ai.openclaw.app.trace.TraceEventType
import ai.openclaw.app.trace.TraceFilterState
import ai.openclaw.app.trace.TraceSeverity
import ai.openclaw.app.trace.TraceSource
import ai.openclaw.app.trace.TraceStore
import ai.openclaw.app.voice.AssistantVoiceOption
import ai.openclaw.app.voice.MicCaptureManager
import ai.openclaw.app.voice.TalkModeManager
import ai.openclaw.app.voice.VoiceConversationEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class NodeRuntime(context: Context) {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val prefs = SecurePrefs(appContext)
  private val deviceAuthStore = DeviceAuthStore(prefs)
  val canvas = CanvasController()
  val camera = CameraCaptureManager(appContext)
  val location = LocationCaptureManager(appContext)
  val sms = SmsManager(appContext)
  private val json = Json { ignoreUnknownKeys = true }
  private val traceStore = TraceStore(scope = scope)

  private val externalAudioCaptureActive = MutableStateFlow(false)

  private val discovery = GatewayDiscovery(appContext, scope = scope)
  val gateways: StateFlow<List<GatewayEndpoint>> = discovery.gateways
  val discoveryStatusText: StateFlow<String> = discovery.statusText

  private val identityStore = DeviceIdentityStore(appContext)
  private var connectedEndpoint: GatewayEndpoint? = null

  private val cameraHandler: CameraHandler = CameraHandler(
    appContext = appContext,
    camera = camera,
    externalAudioCaptureActive = externalAudioCaptureActive,
    showCameraHud = ::showCameraHud,
    triggerCameraFlash = ::triggerCameraFlash,
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val debugHandler: DebugHandler = DebugHandler(
    appContext = appContext,
    identityStore = identityStore,
  )

  private val locationHandler: LocationHandler = LocationHandler(
    appContext = appContext,
    location = location,
    json = json,
    isForeground = { _isForeground.value },
    locationPreciseEnabled = { locationPreciseEnabled.value },
  )

  private val deviceHandler: DeviceHandler = DeviceHandler(
    appContext = appContext,
  )

  private val notificationsHandler: NotificationsHandler = NotificationsHandler(
    appContext = appContext,
  )

  private val systemHandler: SystemHandler = SystemHandler(
    appContext = appContext,
  )

  private val photosHandler: PhotosHandler = PhotosHandler(
    appContext = appContext,
  )

  private val contactsHandler: ContactsHandler = ContactsHandler(
    appContext = appContext,
  )

  private val calendarHandler: CalendarHandler = CalendarHandler(
    appContext = appContext,
  )

  private val motionHandler: MotionHandler = MotionHandler(
    appContext = appContext,
  )

  private val smsHandlerImpl: SmsHandler = SmsHandler(
    sms = sms,
  )

  private val a2uiHandler: A2UIHandler = A2UIHandler(
    canvas = canvas,
    json = json,
    getNodeCanvasHostUrl = { nodeSession.currentCanvasHostUrl() },
    getOperatorCanvasHostUrl = { operatorSession.currentCanvasHostUrl() },
  )

  private val connectionManager: ConnectionManager = ConnectionManager(
    prefs = prefs,
    cameraEnabled = { cameraEnabled.value },
    locationMode = { locationMode.value },
    voiceWakeMode = { VoiceWakeMode.Off },
    motionActivityAvailable = { motionHandler.isActivityAvailable() },
    motionPedometerAvailable = { motionHandler.isPedometerAvailable() },
    smsAvailable = { sms.canSendSms() },
    hasRecordAudioPermission = { hasRecordAudioPermission() },
    manualTls = { manualTls.value },
  )

  private val invokeDispatcher: InvokeDispatcher = InvokeDispatcher(
    canvas = canvas,
    cameraHandler = cameraHandler,
    locationHandler = locationHandler,
    deviceHandler = deviceHandler,
    notificationsHandler = notificationsHandler,
    systemHandler = systemHandler,
    photosHandler = photosHandler,
    contactsHandler = contactsHandler,
    calendarHandler = calendarHandler,
    motionHandler = motionHandler,
    smsHandler = smsHandlerImpl,
    a2uiHandler = a2uiHandler,
    debugHandler = debugHandler,
    isForeground = { _isForeground.value },
    cameraEnabled = { cameraEnabled.value },
    locationEnabled = { locationMode.value != LocationMode.Off },
    smsAvailable = { sms.canSendSms() },
    debugBuild = { BuildConfig.DEBUG },
    refreshNodeCanvasCapability = { nodeSession.refreshNodeCanvasCapability() },
    onCanvasA2uiPush = {
      _canvasA2uiHydrated.value = true
      _canvasRehydratePending.value = false
      _canvasRehydrateErrorText.value = null
    },
    onCanvasA2uiReset = { _canvasA2uiHydrated.value = false },
    motionActivityAvailable = { motionHandler.isActivityAvailable() },
    motionPedometerAvailable = { motionHandler.isPedometerAvailable() },
  )

  data class GatewayTrustPrompt(
    val endpoint: GatewayEndpoint,
    val fingerprintSha256: String?,
    val bodyText: String,
    val allowFingerprintOverride: Boolean,
  )

  private val _isConnected = MutableStateFlow(false)
  val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
  private val _nodeConnected = MutableStateFlow(false)
  val nodeConnected: StateFlow<Boolean> = _nodeConnected.asStateFlow()

  private val _statusText = MutableStateFlow("Offline")
  val statusText: StateFlow<String> = _statusText.asStateFlow()

  private val _pendingGatewayTrust = MutableStateFlow<GatewayTrustPrompt?>(null)
  val pendingGatewayTrust: StateFlow<GatewayTrustPrompt?> = _pendingGatewayTrust.asStateFlow()

  private val _mainSessionKey = MutableStateFlow("main")
  val mainSessionKey: StateFlow<String> = _mainSessionKey.asStateFlow()

  private val cameraHudSeq = AtomicLong(0)
  private val _cameraHud = MutableStateFlow<CameraHudState?>(null)
  val cameraHud: StateFlow<CameraHudState?> = _cameraHud.asStateFlow()

  private val _cameraFlashToken = MutableStateFlow(0L)
  val cameraFlashToken: StateFlow<Long> = _cameraFlashToken.asStateFlow()

  private val _canvasA2uiHydrated = MutableStateFlow(false)
  val canvasA2uiHydrated: StateFlow<Boolean> = _canvasA2uiHydrated.asStateFlow()
  private val _canvasRehydratePending = MutableStateFlow(false)
  val canvasRehydratePending: StateFlow<Boolean> = _canvasRehydratePending.asStateFlow()
  private val _canvasRehydrateErrorText = MutableStateFlow<String?>(null)
  val canvasRehydrateErrorText: StateFlow<String?> = _canvasRehydrateErrorText.asStateFlow()

  private val _serverName = MutableStateFlow<String?>(null)
  val serverName: StateFlow<String?> = _serverName.asStateFlow()

  private val _remoteAddress = MutableStateFlow<String?>(null)
  val remoteAddress: StateFlow<String?> = _remoteAddress.asStateFlow()

  private val _seamColorArgb = MutableStateFlow(DEFAULT_SEAM_COLOR_ARGB)
  val seamColorArgb: StateFlow<Long> = _seamColorArgb.asStateFlow()

  private val _isForeground = MutableStateFlow(true)
  val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

  private var lastAutoA2uiUrl: String? = null
  private var didAutoRequestCanvasRehydrate = false
  private val canvasRehydrateSeq = AtomicLong(0)
  private var operatorConnected = false
  private var operatorStatusText: String = "Offline"
  private var nodeStatusText: String = "Offline"
  private var debugScreenActive = false
  private var gatewayTraceLogPollJob: Job? = null
  private var gatewayTraceLogCursor: Long? = null
  private var gatewayTraceLogLastRefreshAtMs: Long = 0L
  private var gatewayTraceLogLastErrorAtMs: Long = 0L
  private val seenGatewayLogEntries = LinkedHashSet<String>()

  private val operatorSession =
    GatewaySession(
      scope = scope,
      identityStore = identityStore,
      deviceAuthStore = deviceAuthStore,
      onConnected = { name, remote, mainSessionKey ->
        operatorConnected = true
        operatorStatusText = "Connected"
        _serverName.value = name
        _remoteAddress.value = remote
        _seamColorArgb.value = DEFAULT_SEAM_COLOR_ARGB
        applyMainSessionKey(mainSessionKey)
        updateStatus()
        micCapture.onGatewayConnectionChanged(true)
        traceStore.append(
          source = TraceSource.Gateway,
          eventType = TraceEventType.ConnectionOpened,
          severity = TraceSeverity.Info,
          agentName = null,
          sessionId = resolveConversationSessionKey(),
          deviceId = remote,
          description = "Gateway connection opened",
          payloadText = name?.takeIf { it.isNotBlank() },
        )
        updateGatewayTraceLogPolling()
        scope.launch {
          refreshBrandingFromGateway()
          if (voiceReplySpeakerLazy.isInitialized()) {
            voiceReplySpeaker.refreshConfig()
          }
          refreshAssistantVoiceOptions()
        }
      },
      onDisconnected = { message ->
        operatorConnected = false
        operatorStatusText = message
        _serverName.value = null
        _remoteAddress.value = null
        _seamColorArgb.value = DEFAULT_SEAM_COLOR_ARGB
        if (!isCanonicalMainSessionKey(_mainSessionKey.value)) {
          _mainSessionKey.value = "main"
        }
        chat.applyMainSessionKey(resolveMainSessionKey())
        chat.onDisconnected(message)
        updateStatus()
        micCapture.onGatewayConnectionChanged(false)
        traceStore.append(
          source = TraceSource.Gateway,
          eventType = TraceEventType.ConnectionClosed,
          severity = TraceSeverity.Warning,
          agentName = null,
          sessionId = resolveConversationSessionKey(),
          deviceId = _remoteAddress.value,
          description = message,
          payloadText = null,
        )
        updateGatewayTraceLogPolling()
      },
      onEvent = { event, payloadJson ->
        handleGatewayEvent(event, payloadJson)
      },
    )

  private val nodeSession =
    GatewaySession(
      scope = scope,
      identityStore = identityStore,
      deviceAuthStore = deviceAuthStore,
      onConnected = { _, _, _ ->
        _nodeConnected.value = true
        nodeStatusText = "Connected"
        didAutoRequestCanvasRehydrate = false
        _canvasA2uiHydrated.value = false
        _canvasRehydratePending.value = false
        _canvasRehydrateErrorText.value = null
        updateStatus()
        maybeNavigateToA2uiOnConnect()
      },
      onDisconnected = { message ->
        _nodeConnected.value = false
        nodeStatusText = message
        didAutoRequestCanvasRehydrate = false
        _canvasA2uiHydrated.value = false
        _canvasRehydratePending.value = false
        _canvasRehydrateErrorText.value = null
        updateStatus()
        showLocalCanvasOnDisconnect()
      },
      onEvent = { _, _ -> },
      onInvoke = { req ->
        invokeDispatcher.handleInvoke(req.command, req.paramsJson)
      },
      onTlsFingerprint = { stableId, fingerprint ->
        prefs.saveGatewayTlsFingerprint(stableId, fingerprint)
      },
    )

  init {
    DeviceNotificationListenerService.setNodeEventSink { event, payloadJson ->
      scope.launch {
        nodeSession.sendNodeEvent(event = event, payloadJson = payloadJson)
      }
    }
  }

  private val chat: ChatController =
    ChatController(
      scope = scope,
      json = json,
      request = { method, paramsJson, timeoutMs ->
        operatorSession.request(method = method, paramsJson = paramsJson, timeoutMs = timeoutMs)
      },
      sendNodeEvent = { event, payloadJson ->
        operatorSession.sendNodeEvent(event = event, payloadJson = payloadJson)
      },
      supportsChatSubscribe = { operatorSession.helloSnapshot.value.supportsLiveChatEvents() },
      supportsAgentWait = { operatorSession.helloSnapshot.value.supportsAgentWait() },
      traceSink = { source, eventType, severity, description, payloadText, sessionId, agentName, deviceId, timestampMs ->
        traceStore.append(
          source = source,
          eventType = eventType,
          severity = severity,
          agentName = agentName,
          sessionId = sessionId,
          deviceId = deviceId,
          description = description,
          payloadText = payloadText,
          timestampMs = timestampMs,
        )
      },
    )
  private val voiceReplySpeakerLazy: Lazy<TalkModeManager> = lazy {
    // Dedicated assistant playback engine for voice replies.
    TalkModeManager(
      context = appContext,
      scope = scope,
      session = operatorSession,
      supportsChatSubscribe = false,
      isConnected = { operatorConnected },
      preferFastVoiceResponses = true,
    ).also { speaker ->
      speaker.setPlaybackEnabled(prefs.speakerEnabled.value)
    }
  }
  private val voiceReplySpeaker: TalkModeManager
    get() = voiceReplySpeakerLazy.value

  private val micCapture: MicCaptureManager by lazy {
    MicCaptureManager(
      context = appContext,
      scope = scope,
      sendToGateway = { message, idempotencyKey, onRunIdKnown ->
        // Notify MicCaptureManager of the idempotency key *before* the network
        // call so pendingRunId is set before any chat events can arrive.
        onRunIdKnown(idempotencyKey)
        val params =
          buildJsonObject {
            put("sessionKey", JsonPrimitive(resolveConversationSessionKey()))
            put("message", JsonPrimitive(message))
            put("thinking", JsonPrimitive(resolveVoiceThinkingLevel()))
            put("timeoutMs", JsonPrimitive(30_000))
            put("idempotencyKey", JsonPrimitive(idempotencyKey))
          }
        val response = operatorSession.request("chat.send", params.toString())
        parseChatSendRunId(response) ?: idempotencyKey
      },
      ensureReplySubscription = {
        ensureVoiceReplySubscription()
      },
      loadLatestAssistantReply = { sinceSeconds ->
        loadLatestVoiceAssistantReply(sinceSeconds)
      },
      speakAssistantReply = { text ->
        voiceReplySpeaker.speakAssistantReply(text)
      },
      interimAssistantText = {
        "I'm looking into this..."
      },
      isPushToTalkMode = { prefs.voiceInputMode.value == VoiceInputMode.PushToTalk },
      currentSessionKey = { resolveConversationSessionKey() },
      traceSink = { source, eventType, severity, description, payloadText, sessionId, agentName, deviceId, timestampMs ->
        traceStore.append(
          source = source,
          eventType = eventType,
          severity = severity,
          agentName = agentName,
          sessionId = sessionId,
          deviceId = deviceId,
          description = description,
          payloadText = payloadText,
          timestampMs = timestampMs,
        )
      },
    )
  }

  val micStatusText: StateFlow<String>
    get() = micCapture.statusText

  val micLiveTranscript: StateFlow<String?>
    get() = micCapture.liveTranscript

  val micIsListening: StateFlow<Boolean>
    get() = micCapture.isListening

  val micEnabled: StateFlow<Boolean>
    get() = micCapture.micEnabled

  val micCooldown: StateFlow<Boolean>
    get() = micCapture.micCooldown

  val micQueuedMessages: StateFlow<List<String>>
    get() = micCapture.queuedMessages

  val micConversation: StateFlow<List<VoiceConversationEntry>>
    get() = micCapture.conversation

  val micInputLevel: StateFlow<Float>
    get() = micCapture.inputLevel

  val micIsSending: StateFlow<Boolean>
    get() = micCapture.isSending

  val micAssistantPlaybackActive: StateFlow<Boolean>
    get() = micCapture.assistantPlaybackActive

  val voiceTurnDiagnostics: StateFlow<ConversationTurnDiagnostics?>
    get() = micCapture.turnDiagnostics

  val traceEvents: StateFlow<List<TraceEvent>>
    get() = traceStore.events

  val filteredTraceEvents: StateFlow<List<TraceEvent>>
    get() = traceStore.filteredEvents

  val traceFilters: StateFlow<TraceFilterState>
    get() = traceStore.filters

  val traceAgentOptions: StateFlow<List<String>>
    get() = traceStore.agentOptions

  val traceSessionOptions: StateFlow<List<String>>
    get() = traceStore.sessionOptions

  val traceDeviceOptions: StateFlow<List<String>>
    get() = traceStore.deviceOptions

  val gatewayLogCollectionMode: StateFlow<GatewayLogCollectionMode>
    get() = traceStore.gatewayLogCollectionMode

  val assistantVoiceOptions: StateFlow<List<AssistantVoiceOption>>
    get() = voiceReplySpeaker.voiceOptions

  private val talkMode: TalkModeManager by lazy {
    TalkModeManager(
      context = appContext,
      scope = scope,
      session = operatorSession,
      supportsChatSubscribe = true,
      isConnected = { operatorConnected },
    )
  }

  private fun applyMainSessionKey(candidate: String?) {
    val trimmed = normalizeMainKey(candidate) ?: return
    if (_mainSessionKey.value == trimmed) return
    _mainSessionKey.value = trimmed
    talkMode.setMainSessionKey(trimmed)
    chat.applyMainSessionKey(trimmed)
  }

  private fun updateStatus() {
    _isConnected.value = operatorConnected
    val operator = operatorStatusText.trim()
    val node = nodeStatusText.trim()
    _statusText.value =
      when {
        operatorConnected && _nodeConnected.value -> "Connected"
        operatorConnected && !_nodeConnected.value -> "Connected (node offline)"
        !operatorConnected && _nodeConnected.value ->
          if (operator.isNotEmpty() && operator != "Offline") {
            "Connected (operator: $operator)"
          } else {
            "Connected (operator offline)"
          }
        operator.isNotBlank() && operator != "Offline" -> operator
        else -> node
      }
  }

  private fun resolveMainSessionKey(): String {
    val trimmed = _mainSessionKey.value.trim()
    return if (trimmed.isEmpty()) "main" else trimmed
  }

  private fun resolveConversationSessionKey(): String {
    val active = chat.sessionKey.value.trim()
    return if (active.isEmpty()) resolveMainSessionKey() else active
  }

  private fun maybeNavigateToA2uiOnConnect() {
    val a2uiUrl = a2uiHandler.resolveA2uiHostUrl() ?: return
    val current = canvas.currentUrl()?.trim().orEmpty()
    if (current.isEmpty() || current == lastAutoA2uiUrl) {
      lastAutoA2uiUrl = a2uiUrl
      canvas.navigate(a2uiUrl)
    }
  }

  private fun showLocalCanvasOnDisconnect() {
    lastAutoA2uiUrl = null
    _canvasA2uiHydrated.value = false
    _canvasRehydratePending.value = false
    _canvasRehydrateErrorText.value = null
    canvas.navigate("")
  }

  fun requestCanvasRehydrate(source: String = "manual", force: Boolean = true) {
    scope.launch {
      if (!_nodeConnected.value) {
        _canvasRehydratePending.value = false
        _canvasRehydrateErrorText.value = "Node offline. Reconnect and retry."
        return@launch
      }
      if (!force && didAutoRequestCanvasRehydrate) return@launch
      didAutoRequestCanvasRehydrate = true
      val requestId = canvasRehydrateSeq.incrementAndGet()
      _canvasRehydratePending.value = true
      _canvasRehydrateErrorText.value = null

      val sessionKey = resolveMainSessionKey()
      val prompt =
        "Restore canvas now for session=$sessionKey source=$source. " +
          "If existing A2UI state exists, replay it immediately. " +
          "If not, create and render a compact mobile-friendly dashboard in Canvas."
      val sent =
        nodeSession.sendNodeEvent(
          event = "agent.request",
          payloadJson =
            buildJsonObject {
              put("message", JsonPrimitive(prompt))
              put("sessionKey", JsonPrimitive(sessionKey))
              put("thinking", JsonPrimitive("low"))
              put("deliver", JsonPrimitive(false))
            }.toString(),
        )
      if (!sent) {
        if (!force) {
          didAutoRequestCanvasRehydrate = false
        }
        if (canvasRehydrateSeq.get() == requestId) {
          _canvasRehydratePending.value = false
          _canvasRehydrateErrorText.value = "Failed to request restore. Tap to retry."
        }
        Log.w("OpenClawCanvas", "canvas rehydrate request failed ($source): transport unavailable")
        return@launch
      }
      scope.launch {
        delay(20_000)
        if (canvasRehydrateSeq.get() != requestId) return@launch
        if (!_canvasRehydratePending.value) return@launch
        if (_canvasA2uiHydrated.value) return@launch
        _canvasRehydratePending.value = false
        _canvasRehydrateErrorText.value = "No canvas update yet. Tap to retry."
      }
    }
  }

  val instanceId: StateFlow<String> = prefs.instanceId
  val displayName: StateFlow<String> = prefs.displayName
  val appThemeMode: StateFlow<AppThemeMode> = prefs.appThemeMode
  val assistantAvatarUri: StateFlow<String> = prefs.assistantAvatarUri
  val assistantVoiceSelection: StateFlow<String> = prefs.assistantVoiceSelection
  val cameraEnabled: StateFlow<Boolean> = prefs.cameraEnabled
  val locationMode: StateFlow<LocationMode> = prefs.locationMode
  val locationPreciseEnabled: StateFlow<Boolean> = prefs.locationPreciseEnabled
  val preventSleep: StateFlow<Boolean> = prefs.preventSleep
  val manualEnabled: StateFlow<Boolean> = prefs.manualEnabled
  val manualHost: StateFlow<String> = prefs.manualHost
  val manualPort: StateFlow<Int> = prefs.manualPort
  val manualTls: StateFlow<Boolean> = prefs.manualTls
  val gatewayToken: StateFlow<String> = prefs.gatewayToken
  val onboardingCompleted: StateFlow<Boolean> = prefs.onboardingCompleted
  fun setGatewayToken(value: String) = prefs.setGatewayToken(value)
  fun setGatewayPassword(value: String) = prefs.setGatewayPassword(value)
  fun setOnboardingCompleted(value: Boolean) = prefs.setOnboardingCompleted(value)
  val lastDiscoveredStableId: StateFlow<String> = prefs.lastDiscoveredStableId
  val canvasDebugStatusEnabled: StateFlow<Boolean> = prefs.canvasDebugStatusEnabled

  private var didAutoConnect = false

  val chatSessionKey: StateFlow<String> = chat.sessionKey
  val chatSessionId: StateFlow<String?> = chat.sessionId
  val chatMessages: StateFlow<List<ChatMessage>> = chat.messages
  val chatError: StateFlow<String?> = chat.errorText
  val chatHealthOk: StateFlow<Boolean> = chat.healthOk
  val chatThinkingLevel: StateFlow<String> = chat.thinkingLevel
  val chatStreamingAssistantText: StateFlow<String?> = chat.streamingAssistantText
  val chatPendingToolCalls: StateFlow<List<ChatPendingToolCall>> = chat.pendingToolCalls
  val chatSessions: StateFlow<List<ChatSessionEntry>> = chat.sessions
  val pendingRunCount: StateFlow<Int> = chat.pendingRunCount
  val chatTurnDiagnostics: StateFlow<ConversationTurnDiagnostics?> = chat.turnDiagnostics
  val operatorHelloSnapshot: StateFlow<GatewayHelloSnapshot?> = operatorSession.helloSnapshot

  init {
    if (prefs.voiceWakeMode.value != VoiceWakeMode.Off) {
      prefs.setVoiceWakeMode(VoiceWakeMode.Off)
    }

    scope.launch {
      prefs.loadGatewayToken()
    }

    scope.launch {
      operatorSession.helloSnapshot.collect { snapshot ->
        if (snapshot.supportsLiveChatEvents() && operatorConnected) {
          chat.ensureSubscribed()
        }
      }
    }

    scope.launch {
      voiceReplySpeaker.isSpeaking.collect { speaking ->
        micCapture.setAssistantPlaybackActive(speaking)
      }
    }

    applyAssistantVoiceSelection(prefs.assistantVoiceSelection.value)

    scope.launch {
      prefs.assistantVoiceSelection.collect { selection ->
        applyAssistantVoiceSelection(selection)
      }
    }

    scope.launch {
      prefs.talkEnabled.collect { enabled ->
        // MicCaptureManager handles STT + send to gateway.
        micCapture.setMicEnabled(enabled)
        talkMode.ttsOnAllResponses = false
        externalAudioCaptureActive.value = enabled
      }
    }

    scope.launch(Dispatchers.Default) {
      gateways.collect { list ->
        if (list.isNotEmpty()) {
          // Security: don't let an unauthenticated discovery feed continuously steer autoconnect.
          // UX parity with iOS: only set once when unset.
          if (lastDiscoveredStableId.value.trim().isEmpty()) {
            prefs.setLastDiscoveredStableId(list.first().stableId)
          }
        }

        if (didAutoConnect) return@collect
        if (_isConnected.value) return@collect

        if (manualEnabled.value) {
          val host = manualHost.value.trim()
          val port = manualPort.value
          if (host.isNotEmpty() && port in 1..65535) {
            // Security: autoconnect only to previously trusted gateways (stored TLS pin).
            if (!manualTls.value) return@collect
            val stableId = GatewayEndpoint.manual(host = host, port = port).stableId
            val storedFingerprint = prefs.loadGatewayTlsFingerprint(stableId)?.trim().orEmpty()
            if (storedFingerprint.isEmpty()) return@collect

            didAutoConnect = true
            connect(GatewayEndpoint.manual(host = host, port = port))
          }
          return@collect
        }

        val targetStableId = lastDiscoveredStableId.value.trim()
        if (targetStableId.isEmpty()) return@collect
        val target = list.firstOrNull { it.stableId == targetStableId } ?: return@collect

        // Security: autoconnect only to previously trusted gateways (stored TLS pin).
        val storedFingerprint = prefs.loadGatewayTlsFingerprint(target.stableId)?.trim().orEmpty()
        if (storedFingerprint.isEmpty()) return@collect

        didAutoConnect = true
        connect(target)
      }
    }

    scope.launch {
      combine(
        canvasDebugStatusEnabled,
        statusText,
        serverName,
        remoteAddress,
      ) { debugEnabled, status, server, remote ->
        Quad(debugEnabled, status, server, remote)
      }.distinctUntilChanged()
        .collect { (debugEnabled, status, server, remote) ->
          canvas.setDebugStatusEnabled(debugEnabled)
          if (!debugEnabled) return@collect
          canvas.setDebugStatus(status, server ?: remote)
        }
    }
  }

  fun setForeground(value: Boolean) {
    _isForeground.value = value
    if (!value) {
      stopActiveVoiceSession()
    }
  }

  fun setDisplayName(value: String) {
    prefs.setDisplayName(value)
  }

  fun setCameraEnabled(value: Boolean) {
    prefs.setCameraEnabled(value)
  }

  fun setLocationMode(mode: LocationMode) {
    prefs.setLocationMode(mode)
  }

  fun setLocationPreciseEnabled(value: Boolean) {
    prefs.setLocationPreciseEnabled(value)
  }

  fun setPreventSleep(value: Boolean) {
    prefs.setPreventSleep(value)
  }

  fun setManualEnabled(value: Boolean) {
    prefs.setManualEnabled(value)
  }

  fun setManualHost(value: String) {
    prefs.setManualHost(value)
  }

  fun setManualPort(value: Int) {
    prefs.setManualPort(value)
  }

  fun setManualTls(value: Boolean) {
    prefs.setManualTls(value)
  }

  fun setCanvasDebugStatusEnabled(value: Boolean) {
    prefs.setCanvasDebugStatusEnabled(value)
  }

  fun setVoiceScreenActive(active: Boolean) {
    if (!active) {
      stopActiveVoiceSession()
    }
    // Don't re-enable on active=true; mic toggle drives that
  }

  fun setDebugScreenActive(active: Boolean) {
    debugScreenActive = active
    updateGatewayTraceLogPolling()
  }

  fun setMicEnabled(value: Boolean) {
    prefs.setTalkEnabled(value)
    if (value) {
      // Tapping mic on interrupts any active TTS (barge-in)
      voiceReplySpeaker.stopTts()
      talkMode.stopTts()
    }
    talkMode.ttsOnAllResponses = false
    micCapture.setMicEnabled(value)
    externalAudioCaptureActive.value = value
  }

  fun startPushToTalkCapture() {
    voiceReplySpeaker.stopTts()
    talkMode.stopTts()
    talkMode.ttsOnAllResponses = false
    externalAudioCaptureActive.value = true
    micCapture.startPushToTalkCapture()
  }

  fun finishPushToTalkCaptureAndSend() {
    micCapture.finishPushToTalkCaptureAndSend()
    externalAudioCaptureActive.value = false
  }

  fun cancelPushToTalkCapture() {
    micCapture.cancelPushToTalkCapture()
    externalAudioCaptureActive.value = false
  }

  val speakerEnabled: StateFlow<Boolean>
    get() = prefs.speakerEnabled

  val voiceInputMode: StateFlow<VoiceInputMode>
    get() = prefs.voiceInputMode

  val voiceThinkingLevel: StateFlow<String>
    get() = prefs.voiceThinkingLevel

  fun setSpeakerEnabled(value: Boolean) {
    prefs.setSpeakerEnabled(value)
    if (voiceReplySpeakerLazy.isInitialized()) {
      voiceReplySpeaker.setPlaybackEnabled(value)
    }
    // Keep TalkMode in sync so speaker mute works when ttsOnAllResponses is active.
    talkMode.setPlaybackEnabled(value)
  }

  fun setAppThemeMode(mode: AppThemeMode) {
    prefs.setAppThemeMode(mode)
  }

  fun setAssistantAvatarUri(value: String?) {
    prefs.setAssistantAvatarUri(value)
  }

  fun setAssistantVoiceSelection(value: String?) {
    prefs.setAssistantVoiceSelection(value)
  }

  fun setVoiceInputMode(mode: VoiceInputMode) {
    prefs.setVoiceInputMode(mode)
  }

  fun setVoiceThinkingLevel(level: String) {
    prefs.setVoiceThinkingLevel(level)
  }

  fun refreshAssistantVoiceOptions() {
    scope.launch {
      voiceReplySpeaker.refreshConfig()
      talkMode.refreshConfig()
      applyAssistantVoiceSelection(prefs.assistantVoiceSelection.value)
    }
  }

  fun stopVoiceInteraction() {
    chat.abort(forceSessionAbort = true, insertSystemMessage = false)
    stopActiveVoiceSession()
  }

  private fun stopActiveVoiceSession() {
    talkMode.ttsOnAllResponses = false
    voiceReplySpeaker.stopTts()
    talkMode.stopTts()
    micCapture.cancelPushToTalkCapture()
    micCapture.setMicEnabled(false)
    prefs.setTalkEnabled(false)
    externalAudioCaptureActive.value = false
  }

  private fun updateGatewayTraceLogPolling() {
    gatewayTraceLogPollJob?.cancel()
    gatewayTraceLogPollJob = null
    if (!debugScreenActive) return
    if (traceStore.gatewayLogCollectionMode.value != GatewayLogCollectionMode.AutoWhileOpen) return
    gatewayTraceLogPollJob =
      scope.launch {
        fetchGatewayTraceLogs(forceResetCursor = false)
        while (debugScreenActive && traceStore.gatewayLogCollectionMode.value == GatewayLogCollectionMode.AutoWhileOpen) {
          delay(2_500L)
          fetchGatewayTraceLogs(forceResetCursor = false)
        }
      }
  }

  private suspend fun fetchGatewayTraceLogs(forceResetCursor: Boolean) {
    if (!operatorConnected) return
    val sinceLastRefreshMs = SystemClock.elapsedRealtime() - gatewayTraceLogLastRefreshAtMs
    if (!forceResetCursor && sinceLastRefreshMs < 750L) return
    gatewayTraceLogLastRefreshAtMs = SystemClock.elapsedRealtime()
    if (forceResetCursor) {
      gatewayTraceLogCursor = null
      seenGatewayLogEntries.clear()
    }

    val requestCursor = gatewayTraceLogCursor
    val response =
      try {
        operatorRequest("logs.tail", buildLogsTailParams(requestCursor).toString(), timeoutMs = 15_000)
      } catch (err: Throwable) {
        if (requestCursor == null) {
          maybeTraceGatewayLogFailure(err)
          return
        }
        gatewayTraceLogCursor = null
        seenGatewayLogEntries.clear()
        try {
          operatorRequest("logs.tail", buildLogsTailParams(cursor = null).toString(), timeoutMs = 15_000)
        } catch (fallbackErr: Throwable) {
          maybeTraceGatewayLogFailure(fallbackErr)
          return
        }
      }

    val root = json.parseToJsonElement(response).asObjectOrNull() ?: return
    gatewayTraceLogCursor = root["cursor"].asJsonLongOrNull() ?: gatewayTraceLogCursor
    val lines = (root["lines"] as? JsonArray).orEmpty().mapNotNull { it.asStringOrNull() }
    for (line in lines) {
      val trimmed = line.trim()
      if (trimmed.isEmpty()) continue
      if (!seenGatewayLogEntries.add(trimmed)) continue
      while (seenGatewayLogEntries.size > 800) {
        val first = seenGatewayLogEntries.firstOrNull() ?: break
        seenGatewayLogEntries.remove(first)
      }
      val parsed = parseGatewayLogLine(trimmed)
      traceStore.append(
        source = TraceSource.Gateway,
        eventType = TraceEventType.GatewayLog,
        severity = parsed.severity,
        agentName = parsed.agentName,
        sessionId = parsed.sessionId,
        deviceId = parsed.deviceId,
        description = parsed.description,
        payloadText = trimmed,
        timestampMs = parsed.timestampMs,
      )
    }
  }

  private fun buildLogsTailParams(cursor: Long?) =
    buildJsonObject {
      put("limit", JsonPrimitive(if (cursor == null) 80 else 40))
      put("maxBytes", JsonPrimitive(96_000))
      cursor?.let { put("cursor", JsonPrimitive(it)) }
    }

  private fun maybeTraceGatewayLogFailure(err: Throwable) {
    val now = SystemClock.elapsedRealtime()
    if (now - gatewayTraceLogLastErrorAtMs < 10_000L) return
    gatewayTraceLogLastErrorAtMs = now
    traceStore.append(
      source = TraceSource.Gateway,
      eventType = TraceEventType.Error,
      severity = TraceSeverity.Warning,
      agentName = null,
      sessionId = resolveConversationSessionKey(),
      deviceId = _remoteAddress.value,
      description = "Gateway log polling failed",
      payloadText = err.message ?: err::class.simpleName,
    )
  }

  private data class ParsedGatewayLogLine(
    val timestampMs: Long,
    val severity: TraceSeverity,
    val description: String,
    val sessionId: String?,
    val deviceId: String?,
    val agentName: String?,
  )

  private fun parseGatewayLogLine(line: String): ParsedGatewayLogLine {
    val severity =
      when {
        line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true) -> TraceSeverity.Error
        line.contains("warn", ignoreCase = true) || line.contains("retry", ignoreCase = true) -> TraceSeverity.Warning
        else -> TraceSeverity.Info
      }
    val sessionId = Regex("""session[:=]\s*([#\w.-]+)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)
    val deviceId = Regex("""device[:=]\s*([#\w.-]+)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)
    val agentName = Regex("""agent[:=]\s*([#\w .-]+)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)?.trim()
    val timestampMs =
      Regex("""^(\d{2}):(\d{2}):(\d{2})""").find(line)?.let { match ->
        val now = Date()
        val parsed =
          runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
              .parse(
                "${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)} ${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]}",
              )
          }.getOrNull()
        parsed?.time
      } ?: System.currentTimeMillis()
    val description =
      line
        .replace(Regex("""^\d{2}:\d{2}:\d{2}\s*"""), "")
        .trim()
        .ifEmpty { "Gateway log entry" }
    return ParsedGatewayLogLine(
      timestampMs = timestampMs,
      severity = severity,
      description = description,
      sessionId = sessionId,
      deviceId = deviceId,
      agentName = agentName,
    )
  }

  fun setTraceFilters(filters: TraceFilterState) {
    traceStore.setFilters(filters)
  }

  fun clearTraceEvents() {
    traceStore.clear()
    gatewayTraceLogCursor = null
    seenGatewayLogEntries.clear()
  }

  fun setGatewayLogCollectionMode(mode: GatewayLogCollectionMode) {
    traceStore.setGatewayLogCollectionMode(mode)
    updateGatewayTraceLogPolling()
  }

  fun refreshGatewayTraceLogs() {
    scope.launch { fetchGatewayTraceLogs(forceResetCursor = false) }
  }

  fun exportVisibleTraceEvents() {
    scope.launch {
      try {
        val tracesDir = File(appContext.cacheDir, "trace-exports").apply { mkdirs() }
        val fileName = "claw-trace-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
        val file = File(tracesDir, fileName)
        file.writeText(traceStore.exportVisibleEventsAsJson())
        val uri = FileProvider.getUriForFile(appContext, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent =
          Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Claw Companion Trace Export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        appContext.startActivity(Intent.createChooser(intent, "Export traces").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      } catch (err: Throwable) {
        traceStore.append(
          source = TraceSource.Client,
          eventType = TraceEventType.Error,
          severity = TraceSeverity.Error,
          agentName = null,
          sessionId = resolveConversationSessionKey(),
          deviceId = "android",
          description = "Trace export failed",
          payloadText = err.message,
        )
      }
    }
  }

  fun refreshGatewayConnection() {
    val endpoint =
      connectedEndpoint ?: run {
        _statusText.value = "Failed: no cached gateway endpoint"
        return
      }
    operatorStatusText = "Connecting…"
    updateStatus()
    val tls = connectionManager.resolveTlsParams(endpoint)
    startGatewayConnection(endpoint, tls)
    operatorSession.reconnect()
    nodeSession.reconnect()
  }

  fun connect(endpoint: GatewayEndpoint) {
    val tls = connectionManager.resolveTlsParams(endpoint)
    if (tls?.required == true && tls.expectedFingerprint.isNullOrBlank()) {
      val isSystemTrustCandidate = isSystemTlsTrustCandidateHost(endpoint.host)
      // First-time TLS: capture fingerprint, ask user to verify out-of-band, then store and connect.
      _statusText.value = "Verify gateway TLS fingerprint…"
      scope.launch {
        val systemTrusted =
          if (isSystemTrustCandidate) {
            isGatewayTlsSystemTrusted(endpoint.host, endpoint.port)
          } else {
            false
          }
        if (systemTrusted) {
          _pendingGatewayTrust.value = null
          startGatewayConnection(endpoint, tls)
          return@launch
        }

        val liveFingerprint = probeGatewayTlsFingerprint(endpoint.host, endpoint.port)
        val discoveryHint = parseGatewayFingerprint(endpoint.tlsFingerprintSha256)
        if (
          liveFingerprint.isNullOrBlank() &&
            discoveryHint.isNullOrBlank() &&
            isSystemTrustCandidate
        ) {
          _statusText.value = "Failed: could not verify secure gateway endpoint"
          return@launch
        }

        val prompt =
          when {
            !liveFingerprint.isNullOrBlank() ->
              GatewayTrustPrompt(
                endpoint = endpoint,
                fingerprintSha256 = liveFingerprint,
                bodyText = "First-time TLS connection.\n\nVerify this SHA-256 fingerprint before trusting:",
                allowFingerprintOverride = false,
              )
            !discoveryHint.isNullOrBlank() ->
              GatewayTrustPrompt(
                endpoint = endpoint,
                fingerprintSha256 = discoveryHint,
                bodyText =
                  "Live TLS probe failed.\n\nDiscovery reported this SHA-256 fingerprint. Verify it on the gateway host before trusting. You can also paste a corrected fingerprint below:",
                allowFingerprintOverride = true,
              )
            else ->
              GatewayTrustPrompt(
                endpoint = endpoint,
                fingerprintSha256 = null,
                bodyText =
                  "Live TLS probe failed.\n\nPaste the gateway SHA-256 fingerprint from the gateway host to continue.",
                allowFingerprintOverride = true,
              )
          }

        _statusText.value =
          when {
            !liveFingerprint.isNullOrBlank() -> "Verify gateway TLS fingerprint..."
            !discoveryHint.isNullOrBlank() -> "Verify gateway TLS fingerprint (hint available)"
            else -> "Action needed: enter gateway TLS fingerprint"
          }
        _pendingGatewayTrust.value = prompt
      }
      return
    }

    startGatewayConnection(endpoint, tls)
    return

    connectedEndpoint = endpoint
    operatorStatusText = "Connecting…"
    nodeStatusText = "Connecting…"
    updateStatus()
    val token = prefs.loadGatewayToken()
    val password = prefs.loadGatewayPassword()
    operatorSession.connect(endpoint, token, password, connectionManager.buildOperatorConnectOptions(), tls)
    nodeSession.connect(endpoint, token, password, connectionManager.buildNodeConnectOptions(), tls)
  }

  private fun startGatewayConnection(endpoint: GatewayEndpoint, tls: GatewayTlsParams?) {
    connectedEndpoint = endpoint
    operatorStatusText = "Connecting..."
    nodeStatusText = "Connecting..."
    updateStatus()
    val token = prefs.loadGatewayToken()
    val password = prefs.loadGatewayPassword()
    operatorSession.connect(endpoint, token, password, connectionManager.buildOperatorConnectOptions(), tls)
    nodeSession.connect(endpoint, token, password, connectionManager.buildNodeConnectOptions(), tls)
  }

  fun acceptGatewayTrustPrompt(fingerprintOverride: String? = null) {
    val prompt = _pendingGatewayTrust.value ?: return
    val fingerprint =
      parseGatewayFingerprint(
        if (prompt.allowFingerprintOverride) {
          fingerprintOverride ?: prompt.fingerprintSha256
        } else {
          prompt.fingerprintSha256
        },
      ) ?: run {
        _statusText.value = "Failed: enter a valid SHA-256 TLS fingerprint"
        return
      }
    _pendingGatewayTrust.value = null
    prefs.saveGatewayTlsFingerprint(prompt.endpoint.stableId, fingerprint)
    connect(prompt.endpoint)
  }

  fun declineGatewayTrustPrompt() {
    _pendingGatewayTrust.value = null
    _statusText.value = "Offline"
  }

  private fun hasRecordAudioPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
      )
  }

  fun connectManual() {
    val host = manualHost.value.trim()
    val port = manualPort.value
    if (host.isEmpty() || port <= 0 || port > 65535) {
      _statusText.value = "Failed: invalid manual host/port"
      return
    }
    connect(GatewayEndpoint.manual(host = host, port = port))
  }

  fun disconnect() {
    connectedEndpoint = null
    _pendingGatewayTrust.value = null
    operatorSession.disconnect()
    nodeSession.disconnect()
  }

  suspend fun operatorRequest(method: String, paramsJson: String?, timeoutMs: Long = 15_000): String {
    return operatorSession.request(method = method, paramsJson = paramsJson, timeoutMs = timeoutMs)
  }

  fun handleCanvasA2UIActionFromWebView(payloadJson: String) {
    scope.launch {
      val trimmed = payloadJson.trim()
      if (trimmed.isEmpty()) return@launch

      val root =
        try {
          json.parseToJsonElement(trimmed).asObjectOrNull() ?: return@launch
        } catch (_: Throwable) {
          return@launch
        }

      val userActionObj = (root["userAction"] as? JsonObject) ?: root
      val actionId = (userActionObj["id"] as? JsonPrimitive)?.content?.trim().orEmpty().ifEmpty {
        java.util.UUID.randomUUID().toString()
      }
      val name = OpenClawCanvasA2UIAction.extractActionName(userActionObj) ?: return@launch

      val surfaceId =
        (userActionObj["surfaceId"] as? JsonPrimitive)?.content?.trim().orEmpty().ifEmpty { "main" }
      val sourceComponentId =
        (userActionObj["sourceComponentId"] as? JsonPrimitive)?.content?.trim().orEmpty().ifEmpty { "-" }
      val contextJson = (userActionObj["context"] as? JsonObject)?.toString()

      val sessionKey = resolveMainSessionKey()
      val message =
        OpenClawCanvasA2UIAction.formatAgentMessage(
          actionName = name,
          sessionKey = sessionKey,
          surfaceId = surfaceId,
          sourceComponentId = sourceComponentId,
          host = SecurePrefs.defaultClientDisplayName,
          instanceId = instanceId.value.lowercase(),
          contextJson = contextJson,
        )

      val connected = _nodeConnected.value
      var error: String? = null
      if (connected) {
        val sent =
          nodeSession.sendNodeEvent(
            event = "agent.request",
            payloadJson =
              buildJsonObject {
                put("message", JsonPrimitive(message))
                put("sessionKey", JsonPrimitive(sessionKey))
                put("thinking", JsonPrimitive("low"))
                put("deliver", JsonPrimitive(false))
                put("key", JsonPrimitive(actionId))
              }.toString(),
          )
        if (!sent) {
          error = "send failed"
        }
      } else {
        error = "gateway not connected"
      }

      try {
        canvas.eval(
          OpenClawCanvasA2UIAction.jsDispatchA2UIActionStatus(
            actionId = actionId,
            ok = connected && error == null,
            error = error,
          ),
        )
      } catch (_: Throwable) {
        // ignore
      }
    }
  }

  fun loadChat(sessionKey: String) {
    val key = sessionKey.trim().ifEmpty { resolveMainSessionKey() }
    talkMode.setMainSessionKey(key)
    chat.load(key)
  }

  fun refreshChat() {
    chat.refresh()
  }

  fun refreshChatSessions(limit: Int? = null) {
    chat.refreshSessions(limit = limit)
  }

  fun setChatThinkingLevel(level: String) {
    chat.setThinkingLevel(level)
  }

  private fun resolveVoiceThinkingLevel(): String {
    return when (prefs.voiceThinkingLevel.value.trim().lowercase()) {
      "low", "medium", "high" -> prefs.voiceThinkingLevel.value.trim().lowercase()
      else -> "off"
    }
  }

  fun switchChatSession(sessionKey: String) {
    val key = sessionKey.trim().ifEmpty { resolveMainSessionKey() }
    talkMode.setMainSessionKey(key)
    chat.switchSession(key)
  }

  fun createChatSession(): String {
    val key = "mobile-${UUID.randomUUID().toString().substring(0, 8)}"
    talkMode.setMainSessionKey(key)
    chat.createSession(key)
    return key
  }

  fun abortChat() {
    chat.abort(forceSessionAbort = true, insertSystemMessage = true)
  }

  fun sendChat(message: String, thinking: String, attachments: List<OutgoingAttachment>) {
    chat.sendMessage(message = message, thinkingLevel = thinking, attachments = attachments)
  }

  private fun handleGatewayEvent(event: String, payloadJson: String?) {
    if (event != "tick" && event != "chat" && event != "agent" && event != "health") {
      traceStore.append(
        source = TraceSource.Gateway,
        eventType = if (event == "seqGap") TraceEventType.Error else TraceEventType.GatewayEvent,
        severity = if (event == "seqGap") TraceSeverity.Warning else TraceSeverity.Info,
        agentName = null,
        sessionId = resolveConversationSessionKey(),
        deviceId = _remoteAddress.value,
        description = "Gateway event: $event",
        payloadText = payloadJson,
      )
    }
    micCapture.handleGatewayEvent(event, payloadJson)
    talkMode.handleGatewayEvent(event, payloadJson)
    chat.handleGatewayEvent(event, payloadJson)
  }

  private fun applyAssistantVoiceSelection(selection: String?) {
    val resolved = selection?.trim().orEmpty()
    voiceReplySpeaker.setPreferredVoice(resolved)
    talkMode.setPreferredVoice(resolved)
  }

  private fun parseChatSendRunId(response: String): String? {
    return try {
      val root = json.parseToJsonElement(response).asObjectOrNull() ?: return null
      root["runId"].asStringOrNull()
    } catch (_: Throwable) {
      null
    }
  }

  private suspend fun ensureVoiceReplySubscription() {
    if (!operatorSession.helloSnapshot.value.supportsLiveChatEvents()) return
    val sessionKey = resolveConversationSessionKey()
    operatorSession.sendNodeEvent("chat.subscribe", """{"sessionKey":"$sessionKey"}""")
  }

  private suspend fun loadLatestVoiceAssistantReply(sinceSeconds: Double): String? {
    val sessionKey = resolveConversationSessionKey()
    val res = operatorSession.request("chat.history", """{"sessionKey":"$sessionKey"}""")
    val root = json.parseToJsonElement(res).asObjectOrNull() ?: return null
    val messages = root["messages"] as? JsonArray ?: return null
    for (item in messages.reversed()) {
      val message = item.asObjectOrNull() ?: continue
      if (message["role"].asStringOrNull() != "assistant") continue
      val timestamp = messageTimestampSeconds(message["timestamp"])
      if (timestamp != null && !isVoiceMessageTimestampAfter(timestamp, sinceSeconds)) continue
      val content = message["content"] as? JsonArray ?: continue
      val text =
        content.mapNotNull { entry ->
          entry.asObjectOrNull()?.get("text")?.asStringOrNull()?.trim()?.takeIf(String::isNotEmpty)
        }
      if (text.isNotEmpty()) {
        return text.joinToString("\n")
      }
    }
    return null
  }

  private fun messageTimestampSeconds(element: kotlinx.serialization.json.JsonElement?): Double? {
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.content.toDoubleOrNull()
  }

  private fun isVoiceMessageTimestampAfter(timestamp: Double, sinceSeconds: Double): Boolean {
    val sinceMs = sinceSeconds * 1000
    return if (timestamp > 10_000_000_000) {
      timestamp >= sinceMs - 500
    } else {
      timestamp >= sinceSeconds - 0.5
    }
  }

  private suspend fun refreshBrandingFromGateway() {
    if (!_isConnected.value) return
    try {
      val res = operatorSession.request("config.get", "{}")
      val root = json.parseToJsonElement(res).asObjectOrNull()
      val config = root?.get("config").asObjectOrNull()
      val ui = config?.get("ui").asObjectOrNull()
      val raw = ui?.get("seamColor").asStringOrNull()?.trim()
      val sessionCfg = config?.get("session").asObjectOrNull()
      val mainKey = normalizeMainKey(sessionCfg?.get("mainKey").asStringOrNull())
      applyMainSessionKey(mainKey)

      val parsed = parseHexColorArgb(raw)
      _seamColorArgb.value = parsed ?: DEFAULT_SEAM_COLOR_ARGB
    } catch (_: Throwable) {
      // ignore
    }
  }

  private fun triggerCameraFlash() {
    // Token is used as a pulse trigger; value doesn't matter as long as it changes.
    _cameraFlashToken.value = SystemClock.elapsedRealtimeNanos()
  }

  private fun showCameraHud(message: String, kind: CameraHudKind, autoHideMs: Long? = null) {
    val token = cameraHudSeq.incrementAndGet()
    _cameraHud.value = CameraHudState(token = token, kind = kind, message = message)

    if (autoHideMs != null && autoHideMs > 0) {
      scope.launch {
        delay(autoHideMs)
        if (_cameraHud.value?.token == token) _cameraHud.value = null
      }
    }
  }

}

private fun kotlinx.serialization.json.JsonElement?.asJsonLongOrNull(): Long? {
  val primitive = this as? JsonPrimitive ?: return null
  return primitive.content.toLongOrNull()
}
