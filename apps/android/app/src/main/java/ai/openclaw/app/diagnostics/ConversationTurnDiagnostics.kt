package ai.openclaw.app.diagnostics

import java.util.Locale

data class ConversationTurnDiagnostics(
  val mode: ConversationMode,
  val sessionKey: String,
  val runId: String?,
  val inputPreview: String,
  val status: ConversationTurnStatus,
  val completionSource: String? = null,
  val errorMessage: String? = null,
  val startedAtMs: Long,
  val sendAckAtMs: Long? = null,
  val firstReplyAtMs: Long? = null,
  val terminalEventAtMs: Long? = null,
  val resolvedAtMs: Long? = null,
  val playbackStartedAtMs: Long? = null,
) {
  fun summaryLine(): String? {
    val parts = buildList {
      durationLabel("Ack", startedAtMs, sendAckAtMs)?.let(::add)
      durationLabel("First", startedAtMs, firstReplyAtMs ?: resolvedAtMs)?.let(::add)
      durationLabel("Reply", startedAtMs, resolvedAtMs)?.let(::add)
      durationLabel("Voice", resolvedAtMs ?: firstReplyAtMs ?: startedAtMs, playbackStartedAtMs)?.let(::add)
      completionSource?.takeIf { it.isNotBlank() }?.let { add(sourceLabel(it)) }
    }
    if (parts.isEmpty()) return null
    return parts.joinToString(" • ")
  }

  fun statusLine(): String {
    val prefix =
      when (status) {
        ConversationTurnStatus.Sending -> "Sending"
        ConversationTurnStatus.Waiting -> "Waiting"
        ConversationTurnStatus.Streaming -> "Replying"
        ConversationTurnStatus.Complete -> "Done"
        ConversationTurnStatus.Error -> "Failed"
      }
    val summary = summaryLine()
    return if (summary.isNullOrBlank()) {
      prefix
    } else {
      "$prefix • $summary"
    }
  }

  fun withStatus(
    status: ConversationTurnStatus,
    completionSource: String? = this.completionSource,
    errorMessage: String? = this.errorMessage,
  ): ConversationTurnDiagnostics {
    return copy(
      status = status,
      completionSource = completionSource,
      errorMessage = errorMessage,
    )
  }

  companion object {
    fun preview(text: String, maxChars: Int = 56): String {
      val squashed = text.trim().replace(Regex("\\s+"), " ")
      if (squashed.length <= maxChars) return squashed
      return squashed.take(maxChars - 1).trimEnd() + "…"
    }

    private fun durationLabel(label: String, startMs: Long?, endMs: Long?): String? {
      if (startMs == null || endMs == null || endMs < startMs) return null
      val deltaMs = endMs - startMs
      return "$label ${formatDuration(deltaMs)}"
    }

    private fun formatDuration(durationMs: Long): String {
      return if (durationMs < 1_000) {
        "${durationMs}ms"
      } else {
        String.format(Locale.US, "%.1fs", durationMs / 1000.0)
      }
    }

    private fun sourceLabel(source: String): String {
      return when (source.trim().lowercase(Locale.US)) {
        "chat-event" -> "Live event"
        "stream-preview" -> "Preview"
        "history-recovery" -> "History"
        "agent-wait" -> "Agent wait"
        "timeout-preview" -> "Timeout preview"
        "voice-poll" -> "History poll"
        "voice-event" -> "Voice event"
        "voice-error" -> "Voice error"
        "chat-error" -> "Chat error"
        else -> source.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
      }
    }
  }
}

enum class ConversationMode {
  Chat,
  Voice,
}

enum class ConversationTurnStatus {
  Sending,
  Waiting,
  Streaming,
  Complete,
  Error,
}
