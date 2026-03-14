package ai.openclaw.app.trace

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TraceStore(
  scope: CoroutineScope,
  private val maxEvents: Int = 1_500,
) {
  private val exportJson = Json { prettyPrint = true }

  private val _events = MutableStateFlow<List<TraceEvent>>(emptyList())
  val events: StateFlow<List<TraceEvent>> = _events.asStateFlow()

  private val _filters = MutableStateFlow(TraceFilterState())
  val filters: StateFlow<TraceFilterState> = _filters.asStateFlow()

  private val _gatewayLogCollectionMode = MutableStateFlow(GatewayLogCollectionMode.AutoWhileOpen)
  val gatewayLogCollectionMode: StateFlow<GatewayLogCollectionMode> = _gatewayLogCollectionMode.asStateFlow()

  val filteredEvents: StateFlow<List<TraceEvent>> =
    combine(_events, _filters) { events, filters ->
      events.filter(filters::matches)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  val agentOptions: StateFlow<List<String>> =
    _events
      .mapDistinctValues(scope) { event -> event.agentName?.trim()?.takeIf { it.isNotEmpty() } }

  val sessionOptions: StateFlow<List<String>> =
    _events
      .mapDistinctValues(scope) { event -> event.sessionId?.trim()?.takeIf { it.isNotEmpty() } }

  val deviceOptions: StateFlow<List<String>> =
    _events
      .mapDistinctValues(scope) { event -> event.deviceId?.trim()?.takeIf { it.isNotEmpty() } }

  fun append(
    source: TraceSource,
    eventType: TraceEventType,
    severity: TraceSeverity = TraceSeverity.Info,
    agentName: String? = null,
    sessionId: String? = null,
    deviceId: String? = null,
    description: String,
    payloadText: String? = null,
    timestampMs: Long = System.currentTimeMillis(),
  ): TraceEvent {
    val event =
      TraceEvent(
        id = UUID.randomUUID().toString(),
        timestampMs = timestampMs,
        source = source,
        eventType = eventType,
        severity = severity,
        agentName = agentName?.trim()?.takeIf { it.isNotEmpty() },
        sessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() },
        deviceId = deviceId?.trim()?.takeIf { it.isNotEmpty() },
        description = description.trim(),
        payloadText = payloadText?.trim()?.takeIf { it.isNotEmpty() },
      )
    _events.value =
      buildList(capacity = minOf(_events.value.size + 1, maxEvents)) {
        add(event)
        addAll(_events.value.take(maxEvents - 1))
      }
    return event
  }

  fun clear() {
    _events.value = emptyList()
  }

  fun setFilters(filters: TraceFilterState) {
    _filters.value = filters
  }

  fun updateFilters(transform: (TraceFilterState) -> TraceFilterState) {
    _filters.value = transform(_filters.value)
  }

  fun setGatewayLogCollectionMode(mode: GatewayLogCollectionMode) {
    _gatewayLogCollectionMode.value = mode
  }

  fun exportVisibleEventsAsJson(): String {
    return exportJson.encodeToString(filteredEvents.value)
  }
}

private fun <T : Any> StateFlow<List<TraceEvent>>.mapDistinctValues(
  scope: CoroutineScope,
  selector: (TraceEvent) -> T?,
): StateFlow<List<String>> {
  return this.map { events ->
    events
      .mapNotNull(selector)
      .map { it.toString() }
      .map(String::trim)
      .filter { it.isNotEmpty() && !it.equals("all", ignoreCase = true) }
      .distinctBy { it.lowercase() }
      .sortedBy { it.lowercase() }
  }.stateIn(scope, SharingStarted.Eagerly, emptyList())
}
