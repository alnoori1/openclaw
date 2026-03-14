package ai.openclaw.app.chat

data class SlashCommandDef(
  val name: String,
  val description: String,
  val args: String? = null,
  val executeLocal: Boolean = false,
)

data class ParsedSlashCommand(
  val command: SlashCommandDef,
  val args: String,
)

val slashCommands: List<SlashCommandDef> =
  listOf(
    SlashCommandDef(name = "new", description = "Start a new session", executeLocal = true),
    SlashCommandDef(name = "reset", description = "Reset current session", executeLocal = true),
    SlashCommandDef(name = "compact", description = "Compact session context", executeLocal = true),
    SlashCommandDef(name = "stop", description = "Stop current run", executeLocal = true),
    SlashCommandDef(name = "clear", description = "Clear chat history", executeLocal = true),
    SlashCommandDef(name = "focus", description = "Toggle focus mode", executeLocal = true),
    SlashCommandDef(name = "model", description = "Show or set model", args = "<name>", executeLocal = true),
    SlashCommandDef(name = "think", description = "Show or set thinking level", args = "<level>", executeLocal = true),
    SlashCommandDef(name = "verbose", description = "Show or set verbose mode", args = "<on|off|full>", executeLocal = true),
    SlashCommandDef(name = "fast", description = "Show or set fast mode", args = "<status|on|off>", executeLocal = true),
    SlashCommandDef(name = "help", description = "Show available commands", executeLocal = true),
    SlashCommandDef(name = "status", description = "Show gateway status", executeLocal = true),
    SlashCommandDef(name = "export", description = "Export session", executeLocal = true),
    SlashCommandDef(name = "usage", description = "Show token usage", executeLocal = true),
    SlashCommandDef(name = "agents", description = "List agents", executeLocal = true),
    SlashCommandDef(name = "kill", description = "Abort sub-agents", args = "<id|all>", executeLocal = true),
    SlashCommandDef(name = "skill", description = "Run a skill", args = "<name>", executeLocal = false),
    SlashCommandDef(name = "steer", description = "Steer a sub-agent", args = "<id> <msg>", executeLocal = false),
  )

private val slashCommandIndex: Map<String, SlashCommandDef> = slashCommands.associateBy { it.name }

fun parseSlashCommand(text: String): ParsedSlashCommand? {
  val trimmed = text.trim()
  if (!trimmed.startsWith("/")) return null

  val body = trimmed.drop(1)
  if (body.isEmpty()) return null

  val firstSeparator = body.indexOfFirst { it.isWhitespace() || it == ':' }
  val name =
    if (firstSeparator == -1) {
      body
    } else {
      body.substring(0, firstSeparator)
    }.trim()
  if (name.isEmpty()) return null

  val command = slashCommandIndex[name.lowercase()] ?: return null
  var remainder =
    if (firstSeparator == -1) {
      ""
    } else {
      body.substring(firstSeparator).trimStart()
    }
  if (remainder.startsWith(":")) {
    remainder = remainder.drop(1).trimStart()
  }
  return ParsedSlashCommand(command = command, args = remainder.trim())
}
