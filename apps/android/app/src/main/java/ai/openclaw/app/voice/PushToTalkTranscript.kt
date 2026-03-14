package ai.openclaw.app.voice

internal fun buildVoiceTurnMessage(
  finalSegments: List<String>,
  liveTranscript: String?,
  lastFinalSegment: String?,
): String? {
  val mergedSegments = finalSegments.toMutableList()
  val partial = liveTranscript?.trim().orEmpty()
  if (partial.isNotEmpty() && partial != lastFinalSegment) {
    mergedSegments.add(partial)
  }
  if (mergedSegments.isEmpty()) return null
  val message =
    mergedSegments
      .joinToString(". ") { segment ->
        val trimmed = segment.trim()
        if (trimmed.isNotEmpty() && trimmed.last() in ".!?,;:") trimmed else trimmed
      }
      .trim()
      .let { if (it.isNotEmpty() && it.last() !in ".!?") "$it." else it }
  return message.takeIf { it.isNotEmpty() }
}
