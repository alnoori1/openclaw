package ai.openclaw.app.trace

import kotlinx.serialization.Serializable

@Serializable
enum class TraceSource {
  Agent,
  Gateway,
  Client,
}

@Serializable
enum class TraceSeverity {
  Info,
  Warning,
  Error,
}

@Serializable
enum class TraceEventFamily(
  val label: String,
) {
  All("All"),
  Messages("Messages"),
  ToolCalls("Tool calls"),
  Errors("Errors"),
  SystemEvents("System events"),
}

@Serializable
enum class TraceEventType(
  val label: String,
  val family: TraceEventFamily,
) {
  UserMessageSent("User message sent", TraceEventFamily.Messages),
  AgentMessageReceived("Assistant message", TraceEventFamily.Messages),
  AgentResponseSent("Assistant response", TraceEventFamily.Messages),
  AgentThinking("Assistant thinking", TraceEventFamily.SystemEvents),
  ToolCallStarted("Tool started", TraceEventFamily.ToolCalls),
  ToolCallFinished("Tool finished", TraceEventFamily.ToolCalls),
  ToolCallErrored("Tool failed", TraceEventFamily.Errors),
  VoiceCaptureStarted("Voice capture started", TraceEventFamily.SystemEvents),
  VoiceCaptureReady("Voice capture ready", TraceEventFamily.SystemEvents),
  VoiceCaptureCancelled("Voice capture cancelled", TraceEventFamily.SystemEvents),
  VoiceTurnQueued("Voice queued", TraceEventFamily.Messages),
  VoiceTurnSent("Voice sent", TraceEventFamily.Messages),
  VoiceReplyStarted("Voice reply started", TraceEventFamily.Messages),
  VoicePlaybackStarted("Playback started", TraceEventFamily.SystemEvents),
  VoicePlaybackStopped("Playback stopped", TraceEventFamily.SystemEvents),
  ConnectionOpened("Connection opened", TraceEventFamily.SystemEvents),
  ConnectionClosed("Connection closed", TraceEventFamily.SystemEvents),
  GatewayLog("Gateway log", TraceEventFamily.SystemEvents),
  GatewayEvent("Gateway event", TraceEventFamily.SystemEvents),
  Error("Error", TraceEventFamily.Errors),
}

@Serializable
enum class TraceSourceMode(
  val label: String,
) {
  Merged("Merged"),
  AgentOnly("Agent"),
  GatewayOnly("Gateway"),
  ClientOnly("Client"),
}

@Serializable
enum class GatewayLogCollectionMode(
  val label: String,
) {
  AutoWhileOpen("Auto"),
  Manual("Manual"),
}

@Serializable
data class TraceEvent(
  val id: String,
  val timestampMs: Long,
  val source: TraceSource,
  val eventType: TraceEventType,
  val severity: TraceSeverity,
  val agentName: String? = null,
  val sessionId: String? = null,
  val deviceId: String? = null,
  val description: String,
  val payloadText: String? = null,
) {
  val normalizedAgentName: String = agentName?.trim()?.lowercase().orEmpty()
  val normalizedSessionId: String = sessionId?.trim()?.lowercase().orEmpty()
  val normalizedDeviceId: String = deviceId?.trim()?.lowercase().orEmpty()
}

@Serializable
data class TraceFilterState(
  val eventFamily: TraceEventFamily = TraceEventFamily.All,
  val agentName: String? = null,
  val sessionId: String? = null,
  val deviceId: String? = null,
  val severity: TraceSeverity? = null,
  val sourceMode: TraceSourceMode = TraceSourceMode.Merged,
) {
  fun matches(event: TraceEvent): Boolean {
    if (eventFamily != TraceEventFamily.All && event.eventType.family != eventFamily) return false
    if (severity != null && event.severity != severity) return false
    if (!agentName.isNullOrBlank() && event.normalizedAgentName != agentName.trim().lowercase()) return false
    if (!sessionId.isNullOrBlank() && event.normalizedSessionId != sessionId.trim().lowercase()) return false
    if (!deviceId.isNullOrBlank() && event.normalizedDeviceId != deviceId.trim().lowercase()) return false
    return when (sourceMode) {
      TraceSourceMode.Merged -> true
      TraceSourceMode.AgentOnly -> event.source == TraceSource.Agent
      TraceSourceMode.GatewayOnly -> event.source == TraceSource.Gateway
      TraceSourceMode.ClientOnly -> event.source == TraceSource.Client
    }
  }
}
