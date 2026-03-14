package ai.openclaw.app

enum class VoiceInputMode(val rawValue: String) {
  Live("live"),
  PushToTalk("pushToTalk"),
  ;

  companion object {
    fun fromRawValue(raw: String?): VoiceInputMode {
      return entries.firstOrNull { it.rawValue.equals(raw?.trim(), ignoreCase = true) } ?: Live
    }
  }
}
