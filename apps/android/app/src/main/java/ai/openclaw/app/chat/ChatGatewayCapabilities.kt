package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewayHelloSnapshot

internal fun GatewayHelloSnapshot?.supportsLiveChatEvents(): Boolean {
  val snapshot = this ?: return false
  if (!snapshot.supportedMethods.contains("chat.subscribe")) return false
  return snapshot.supportedEvents.isEmpty() ||
    snapshot.supportedEvents.contains("chat") ||
    snapshot.supportedEvents.contains("agent")
}

internal fun GatewayHelloSnapshot?.supportsAgentWait(): Boolean {
  val snapshot = this ?: return false
  return snapshot.supportedMethods.isEmpty() || snapshot.supportedMethods.contains("agent.wait")
}
