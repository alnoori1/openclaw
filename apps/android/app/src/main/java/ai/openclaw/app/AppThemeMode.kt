package ai.openclaw.app

enum class AppThemeMode(val rawValue: String) {
  System("system"),
  Light("light"),
  Dark("dark"),
  ;

  companion object {
    fun fromRawValue(raw: String?): AppThemeMode {
      return entries.firstOrNull { it.rawValue.equals(raw?.trim(), ignoreCase = true) } ?: System
    }
  }
}
