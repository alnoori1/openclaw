package ai.openclaw.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.openclaw.app.companion.OperatorAgentItem
import ai.openclaw.app.companion.OperatorCompanionController
import ai.openclaw.app.companion.OperatorCompanionState
import ai.openclaw.app.companion.OperatorSessionItem
import ai.openclaw.app.chat.OutgoingAttachment
import ai.openclaw.app.diagnostics.ConversationTurnDiagnostics
import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.node.CameraCaptureManager
import ai.openclaw.app.node.CanvasController
import ai.openclaw.app.node.SmsManager
import ai.openclaw.app.trace.GatewayLogCollectionMode
import ai.openclaw.app.trace.TraceEvent
import ai.openclaw.app.trace.TraceFilterState
import ai.openclaw.app.voice.AssistantVoiceOption
import ai.openclaw.app.voice.VoiceConversationEntry
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(app: Application) : AndroidViewModel(app) {
  private val runtime: NodeRuntime = (app as NodeApp).runtime
  private val operatorCompanion =
    OperatorCompanionController(
      context = app,
      scope = viewModelScope,
      isConnected = runtime.isConnected,
      helloSnapshot = runtime.operatorHelloSnapshot,
      request = { method, paramsJson, timeoutMs ->
        runtime.operatorRequest(method = method, paramsJson = paramsJson, timeoutMs = timeoutMs)
      },
    )

  val canvas: CanvasController = runtime.canvas
  val canvasCurrentUrl: StateFlow<String?> = runtime.canvas.currentUrl
  val canvasA2uiHydrated: StateFlow<Boolean> = runtime.canvasA2uiHydrated
  val canvasRehydratePending: StateFlow<Boolean> = runtime.canvasRehydratePending
  val canvasRehydrateErrorText: StateFlow<String?> = runtime.canvasRehydrateErrorText
  val camera: CameraCaptureManager = runtime.camera
  val sms: SmsManager = runtime.sms

  val gateways: StateFlow<List<GatewayEndpoint>> = runtime.gateways
  val discoveryStatusText: StateFlow<String> = runtime.discoveryStatusText

  val isConnected: StateFlow<Boolean> = runtime.isConnected
  val isNodeConnected: StateFlow<Boolean> = runtime.nodeConnected
  val statusText: StateFlow<String> = runtime.statusText
  val serverName: StateFlow<String?> = runtime.serverName
  val remoteAddress: StateFlow<String?> = runtime.remoteAddress
  val pendingGatewayTrust: StateFlow<NodeRuntime.GatewayTrustPrompt?> = runtime.pendingGatewayTrust
  val isForeground: StateFlow<Boolean> = runtime.isForeground
  val seamColorArgb: StateFlow<Long> = runtime.seamColorArgb
  val mainSessionKey: StateFlow<String> = runtime.mainSessionKey

  val cameraHud: StateFlow<CameraHudState?> = runtime.cameraHud
  val cameraFlashToken: StateFlow<Long> = runtime.cameraFlashToken

  val instanceId: StateFlow<String> = runtime.instanceId
  val displayName: StateFlow<String> = runtime.displayName
  val appThemeMode: StateFlow<AppThemeMode> = runtime.appThemeMode
  val assistantAvatarUri: StateFlow<String> = runtime.assistantAvatarUri
  val assistantVoiceSelection: StateFlow<String> = runtime.assistantVoiceSelection
  val assistantVoiceOptions: StateFlow<List<AssistantVoiceOption>> = runtime.assistantVoiceOptions
  val cameraEnabled: StateFlow<Boolean> = runtime.cameraEnabled
  val locationMode: StateFlow<LocationMode> = runtime.locationMode
  val locationPreciseEnabled: StateFlow<Boolean> = runtime.locationPreciseEnabled
  val preventSleep: StateFlow<Boolean> = runtime.preventSleep
  val micEnabled: StateFlow<Boolean> = runtime.micEnabled
  val micCooldown: StateFlow<Boolean> = runtime.micCooldown
  val micStatusText: StateFlow<String> = runtime.micStatusText
  val micLiveTranscript: StateFlow<String?> = runtime.micLiveTranscript
  val micIsListening: StateFlow<Boolean> = runtime.micIsListening
  val micQueuedMessages: StateFlow<List<String>> = runtime.micQueuedMessages
  val micConversation: StateFlow<List<VoiceConversationEntry>> = runtime.micConversation
  val micInputLevel: StateFlow<Float> = runtime.micInputLevel
  val micIsSending: StateFlow<Boolean> = runtime.micIsSending
  val micAssistantPlaybackActive: StateFlow<Boolean> = runtime.micAssistantPlaybackActive
  val voiceTurnDiagnostics: StateFlow<ConversationTurnDiagnostics?> = runtime.voiceTurnDiagnostics
  val speakerEnabled: StateFlow<Boolean> = runtime.speakerEnabled
  val voiceInputMode: StateFlow<VoiceInputMode> = runtime.voiceInputMode
  val voiceThinkingLevel: StateFlow<String> = runtime.voiceThinkingLevel
  val manualEnabled: StateFlow<Boolean> = runtime.manualEnabled
  val manualHost: StateFlow<String> = runtime.manualHost
  val manualPort: StateFlow<Int> = runtime.manualPort
  val manualTls: StateFlow<Boolean> = runtime.manualTls
  val gatewayToken: StateFlow<String> = runtime.gatewayToken
  val onboardingCompleted: StateFlow<Boolean> = runtime.onboardingCompleted
  val canvasDebugStatusEnabled: StateFlow<Boolean> = runtime.canvasDebugStatusEnabled

  val chatSessionKey: StateFlow<String> = runtime.chatSessionKey
  val chatSessionId: StateFlow<String?> = runtime.chatSessionId
  val chatMessages = runtime.chatMessages
  val chatError: StateFlow<String?> = runtime.chatError
  val chatHealthOk: StateFlow<Boolean> = runtime.chatHealthOk
  val chatThinkingLevel: StateFlow<String> = runtime.chatThinkingLevel
  val chatStreamingAssistantText: StateFlow<String?> = runtime.chatStreamingAssistantText
  val chatPendingToolCalls = runtime.chatPendingToolCalls
  val chatSessions = runtime.chatSessions
  val pendingRunCount: StateFlow<Int> = runtime.pendingRunCount
  val chatTurnDiagnostics: StateFlow<ConversationTurnDiagnostics?> = runtime.chatTurnDiagnostics
  val companionState: StateFlow<OperatorCompanionState> = operatorCompanion.state
  val traceEvents: StateFlow<List<TraceEvent>> = runtime.traceEvents
  val filteredTraceEvents: StateFlow<List<TraceEvent>> = runtime.filteredTraceEvents
  val traceFilters: StateFlow<TraceFilterState> = runtime.traceFilters
  val traceAgentOptions: StateFlow<List<String>> = runtime.traceAgentOptions
  val traceSessionOptions: StateFlow<List<String>> = runtime.traceSessionOptions
  val traceDeviceOptions: StateFlow<List<String>> = runtime.traceDeviceOptions
  val gatewayLogCollectionMode: StateFlow<GatewayLogCollectionMode> = runtime.gatewayLogCollectionMode

  fun setForeground(value: Boolean) {
    runtime.setForeground(value)
  }

  fun setDisplayName(value: String) {
    runtime.setDisplayName(value)
  }

  fun setAppThemeMode(mode: AppThemeMode) {
    runtime.setAppThemeMode(mode)
  }

  fun setAssistantAvatarUri(value: String?) {
    runtime.setAssistantAvatarUri(value)
  }

  fun setAssistantVoiceSelection(value: String?) {
    runtime.setAssistantVoiceSelection(value)
  }

  fun setCameraEnabled(value: Boolean) {
    runtime.setCameraEnabled(value)
  }

  fun setLocationMode(mode: LocationMode) {
    runtime.setLocationMode(mode)
  }

  fun setLocationPreciseEnabled(value: Boolean) {
    runtime.setLocationPreciseEnabled(value)
  }

  fun setPreventSleep(value: Boolean) {
    runtime.setPreventSleep(value)
  }

  fun setManualEnabled(value: Boolean) {
    runtime.setManualEnabled(value)
  }

  fun setManualHost(value: String) {
    runtime.setManualHost(value)
  }

  fun setManualPort(value: Int) {
    runtime.setManualPort(value)
  }

  fun setManualTls(value: Boolean) {
    runtime.setManualTls(value)
  }

  fun setGatewayToken(value: String) {
    runtime.setGatewayToken(value)
  }

  fun setGatewayPassword(value: String) {
    runtime.setGatewayPassword(value)
  }

  fun setOnboardingCompleted(value: Boolean) {
    runtime.setOnboardingCompleted(value)
  }

  fun setCanvasDebugStatusEnabled(value: Boolean) {
    runtime.setCanvasDebugStatusEnabled(value)
  }

  fun setVoiceScreenActive(active: Boolean) {
    runtime.setVoiceScreenActive(active)
  }

  fun setDebugScreenActive(active: Boolean) {
    runtime.setDebugScreenActive(active)
  }

  fun setMicEnabled(enabled: Boolean) {
    runtime.setMicEnabled(enabled)
  }

  fun startPushToTalkCapture() {
    runtime.startPushToTalkCapture()
  }

  fun finishPushToTalkCaptureAndSend() {
    runtime.finishPushToTalkCaptureAndSend()
  }

  fun cancelPushToTalkCapture() {
    runtime.cancelPushToTalkCapture()
  }

  fun setSpeakerEnabled(enabled: Boolean) {
    runtime.setSpeakerEnabled(enabled)
  }

  fun setVoiceInputMode(mode: VoiceInputMode) {
    runtime.setVoiceInputMode(mode)
  }

  fun setVoiceThinkingLevel(level: String) {
    runtime.setVoiceThinkingLevel(level)
  }

  fun stopVoiceInteraction() {
    runtime.stopVoiceInteraction()
  }

  fun setTraceFilters(filters: TraceFilterState) {
    runtime.setTraceFilters(filters)
  }

  fun clearTraceEvents() {
    runtime.clearTraceEvents()
  }

  fun refreshGatewayTraceLogs() {
    runtime.refreshGatewayTraceLogs()
  }

  fun exportVisibleTraceEvents() {
    runtime.exportVisibleTraceEvents()
  }

  fun setGatewayLogCollectionMode(mode: GatewayLogCollectionMode) {
    runtime.setGatewayLogCollectionMode(mode)
  }

  fun refreshAssistantVoiceOptions() {
    runtime.refreshAssistantVoiceOptions()
  }

  fun refreshGatewayConnection() {
    runtime.refreshGatewayConnection()
  }

  fun refreshCompanion() {
    operatorCompanion.refreshAll()
  }

  fun refreshCompanionSessions() {
    operatorCompanion.refreshSessions()
  }

  fun refreshCompanionAgents() {
    operatorCompanion.refreshAgents()
  }

  fun refreshCompanionChannels(probe: Boolean = false) {
    operatorCompanion.refreshChannels(probe = probe)
  }

  fun refreshCompanionPairing() {
    operatorCompanion.refreshPairing()
  }

  fun refreshCompanionNodes() {
    operatorCompanion.refreshNodes()
  }

  fun refreshCompanionApprovals() {
    operatorCompanion.refreshApprovals()
  }

  fun refreshCompanionLogs() {
    operatorCompanion.refreshLogs()
  }

  fun loadChannelConfig(channelId: String) {
    operatorCompanion.loadChannelConfig(channelId)
  }

  fun saveChannelPatch(channelId: String, patchJson: String) {
    operatorCompanion.saveChannelPatch(channelId = channelId, patchJson = patchJson)
  }

  fun removeChannelConfig(channelId: String) {
    operatorCompanion.removeChannelConfig(channelId)
  }

  fun resetSession(key: String) {
    operatorCompanion.resetSession(key)
  }

  fun compactSession(key: String) {
    operatorCompanion.compactSession(key)
  }

  fun archiveSession(key: String) {
    operatorCompanion.archiveSession(key)
  }

  fun createAgent(name: String, workspace: String) {
    operatorCompanion.createAgent(name = name, workspace = workspace)
  }

  fun duplicateAgent(agent: OperatorAgentItem) {
    operatorCompanion.duplicateAgent(agent)
  }

  fun deleteAgent(agentId: String) {
    operatorCompanion.deleteAgent(agentId)
  }

  fun logoutChannel(channelId: String, accountId: String?) {
    operatorCompanion.logoutChannel(channelId = channelId, accountId = accountId)
  }

  fun approvePendingPair(requestId: String) {
    operatorCompanion.approvePendingPair(requestId)
  }

  fun rejectPendingPair(requestId: String) {
    operatorCompanion.rejectPendingPair(requestId)
  }

  fun removePairedDevice(deviceId: String) {
    operatorCompanion.removePairedDevice(deviceId)
  }

  fun runGatewayUpdate() {
    operatorCompanion.runGatewayUpdate()
  }

  fun executeRpc(method: String, paramsJson: String) {
    operatorCompanion.executeRpc(method = method, paramsJson = paramsJson)
  }

  fun saveRpcSnippet(
    title: String,
    description: String,
    method: String,
    paramsJson: String,
  ) {
    operatorCompanion.saveCustomSnippet(
      title = title,
      description = description,
      method = method,
      paramsJson = paramsJson,
    )
  }

  fun deleteRpcSnippet(snippetId: String) {
    operatorCompanion.deleteCustomSnippet(snippetId)
  }

  fun clearCompanionNotice() {
    operatorCompanion.clearNotice()
  }

  fun connect(endpoint: GatewayEndpoint) {
    runtime.connect(endpoint)
  }

  fun connectManual() {
    runtime.connectManual()
  }

  fun disconnect() {
    runtime.disconnect()
  }

  fun acceptGatewayTrustPrompt(fingerprintOverride: String? = null) {
    runtime.acceptGatewayTrustPrompt(fingerprintOverride = fingerprintOverride)
  }

  fun declineGatewayTrustPrompt() {
    runtime.declineGatewayTrustPrompt()
  }

  fun handleCanvasA2UIActionFromWebView(payloadJson: String) {
    runtime.handleCanvasA2UIActionFromWebView(payloadJson)
  }

  fun requestCanvasRehydrate(source: String = "screen_tab") {
    runtime.requestCanvasRehydrate(source = source, force = true)
  }

  fun loadChat(sessionKey: String) {
    runtime.loadChat(sessionKey)
  }

  fun refreshChat() {
    runtime.refreshChat()
  }

  fun refreshChatSessions(limit: Int? = null) {
    runtime.refreshChatSessions(limit = limit)
  }

  fun setChatThinkingLevel(level: String) {
    runtime.setChatThinkingLevel(level)
  }

  fun switchChatSession(sessionKey: String) {
    runtime.switchChatSession(sessionKey)
  }

  fun createChatSession(): String {
    return runtime.createChatSession()
  }

  fun openSessionInChat(session: OperatorSessionItem) {
    switchChatSession(session.key)
  }

  fun abortChat() {
    runtime.abortChat()
  }

  fun sendChat(message: String, thinking: String, attachments: List<OutgoingAttachment>) {
    runtime.sendChat(message = message, thinking = thinking, attachments = attachments)
  }
}
