package ai.openclaw.app.voice

object SpeechTextNormalizer {
  private const val codeHeavyFallback = "I've prepared the detailed response on screen."

  private val fencedCodePattern = Regex("```[\\s\\S]*?```")
  private val markdownLinkPattern = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
  private val inlineCodePattern = Regex("`([^`]+)`")
  private val headingPattern = Regex("(?m)^\\s*#{1,6}\\s*")
  private val blockquotePattern = Regex("(?m)^\\s*>+\\s*")
  private val bulletPattern = Regex("(?m)^\\s*[-*+]\\s+")
  private val numberedPattern = Regex("(?m)^\\s*\\d+[.)]\\s+")
  private val dividerPattern = Regex("(?m)^\\s*[-=]{3,}\\s*$")
  private val rawUrlPattern = Regex("https?://\\S+")
  private val markdownMarkerPattern = Regex("(\\*\\*|__|~~|\\*)")
  private val tableLinePattern = Regex("(?m)^\\s*\\|.*\\|\\s*$")
  private val decorativeGlyphPattern = Regex("[\\p{So}\\u200D\\uFE0F]")
  private val decorativeSymbolPattern = Regex("[•▪◦◆◇★☆※]+")
  private val repeatedPunctuationPattern = Regex("([!?.,;:])\\1+")
  private val codeCuePattern =
    Regex(
      "[`{}\\[\\];<>]|::|=>|->|==|!=|\\b(fun|class|const|val|var|return|import|package|object|interface|override)\\b",
      RegexOption.IGNORE_CASE,
    )

  fun forSpeech(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    if (looksCodeHeavy(trimmed)) return codeHeavyFallback

    var normalized = trimmed
    normalized = normalized.replace(fencedCodePattern, " ")
    normalized = normalized.replace(markdownLinkPattern, "$1")
    normalized = normalized.replace(rawUrlPattern, " ")
    normalized = normalized.replace(tableLinePattern, " ")
    normalized = normalized.replace(headingPattern, "")
    normalized = normalized.replace(blockquotePattern, "")
    normalized = normalized.replace(bulletPattern, "")
    normalized = normalized.replace(numberedPattern, "")
    normalized = normalized.replace(dividerPattern, " ")
    normalized = normalized.replace(inlineCodePattern, "$1")
    normalized = normalized.replace(markdownMarkerPattern, "")
    normalized = normalized.replace(decorativeGlyphPattern, " ")
    normalized = normalized.replace(decorativeSymbolPattern, " ")
    normalized = normalized.replace("_", " ")
    normalized = normalized.replace(repeatedPunctuationPattern, "$1")
    normalized = normalized.replace(Regex("\\s+([,.!?;:])"), "$1")
    normalized = normalized.replace(Regex("([.!?])(?=[A-Za-z])"), "$1 ")
    normalized = normalized.replace(Regex("\\s+"), " ").trim()

    return normalized.ifEmpty { codeHeavyFallback }
  }

  private fun looksCodeHeavy(text: String): Boolean {
    val inspected =
      text
        .replace(markdownLinkPattern, "$1")
        .replace(rawUrlPattern, " ")
        .replace(headingPattern, "")
        .replace(blockquotePattern, "")
        .replace(bulletPattern, "")
        .replace(numberedPattern, "")
        .replace(markdownMarkerPattern, "")
    if (inspected.contains("```")) return true
    if (tableLinePattern.containsMatchIn(inspected)) return true

    val lines = inspected.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val codeLikeLines =
      lines.count { line ->
        codeCuePattern.containsMatchIn(line) || line.count { it == '|' } >= 2
      }
    if (codeLikeLines >= 2 && codeLikeLines * 2 >= lines.size.coerceAtLeast(1)) {
      return true
    }
    return false
  }
}
