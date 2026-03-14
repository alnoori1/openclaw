package ai.openclaw.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import ai.openclaw.app.diagnostics.ConversationMode
import ai.openclaw.app.diagnostics.ConversationTurnDiagnostics
import ai.openclaw.app.diagnostics.ConversationTurnStatus
import ai.openclaw.app.trace.TraceEventType
import ai.openclaw.app.trace.TraceSeverity
import ai.openclaw.app.trace.TraceSource
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class VoiceConversationRole {
  User,
  Assistant,
}

data class VoiceConversationEntry(
  val id: String,
  val role: VoiceConversationRole,
  val text: String,
  val isStreaming: Boolean = false,
)

private data class QueuedVoiceTurn(
  val idempotencyKey: String,
  val text: String,
  val diagnostics: ConversationTurnDiagnostics,
)

private enum class VoiceCaptureState {
  Idle,
  PushToTalkHolding,
  PushToTalkReadyToSend,
  Sending,
  AssistantPlayback,
}

class MicCaptureManager(
  private val context: Context,
  private val scope: CoroutineScope,
  /**
   * Send [message] to the gateway and return the run ID.
   * [idempotencyKey] stays stable for queued retries so transient transport
   * failures do not create duplicate runs.
   * [onRunIdKnown] is called with the idempotency key *before* the network
   * round-trip so [pendingRunId] is set before any chat events can arrive.
   */
  private val sendToGateway: suspend (message: String, idempotencyKey: String, onRunIdKnown: (String) -> Unit) -> String?,
  private val ensureReplySubscription: suspend () -> Unit = {},
  private val loadLatestAssistantReply: suspend (Double) -> String? = { null },
  private val speakAssistantReply: suspend (String) -> Unit = {},
  private val interimAssistantText: () -> String = { "I'm looking into this..." },
  private val isPushToTalkMode: () -> Boolean = { false },
  private val currentSessionKey: () -> String = { "main" },
  private val traceSink: (
    source: TraceSource,
    eventType: TraceEventType,
    severity: TraceSeverity,
    description: String,
    payloadText: String?,
    sessionId: String?,
    agentName: String?,
    deviceId: String?,
    timestampMs: Long,
  ) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
) {
  companion object {
    private const val tag = "MicCapture"
    private const val liveSpeechMinSessionMs = 30_000L
    private const val liveSpeechCompleteSilenceMs = 1_500L
    private const val liveSpeechPossibleSilenceMs = 900L
    private const val pushToTalkSpeechMinSessionMs = 120_000L
    private const val pushToTalkSpeechCompleteSilenceMs = 8_000L
    private const val pushToTalkSpeechPossibleSilenceMs = 5_000L
    private const val pushToTalkAudioSampleRateHz = 16_000
    private const val maxConversationEntries = 40
    private const val pendingRunTimeoutMs = 45_000L
    private const val interimAssistantDelayMs = 7_000L
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val json = Json { ignoreUnknownKeys = true }

  private val _micEnabled = MutableStateFlow(false)
  val micEnabled: StateFlow<Boolean> = _micEnabled

  private val _micCooldown = MutableStateFlow(false)
  val micCooldown: StateFlow<Boolean> = _micCooldown

  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening

  private val _statusText = MutableStateFlow("Mic off")
  val statusText: StateFlow<String> = _statusText

  private val _liveTranscript = MutableStateFlow<String?>(null)
  val liveTranscript: StateFlow<String?> = _liveTranscript

  private val _queuedMessages = MutableStateFlow<List<String>>(emptyList())
  val queuedMessages: StateFlow<List<String>> = _queuedMessages

  private val _conversation = MutableStateFlow<List<VoiceConversationEntry>>(emptyList())
  val conversation: StateFlow<List<VoiceConversationEntry>> = _conversation

  private val _inputLevel = MutableStateFlow(0f)
  val inputLevel: StateFlow<Float> = _inputLevel

  private val _isSending = MutableStateFlow(false)
  val isSending: StateFlow<Boolean> = _isSending

  private val _assistantPlaybackActive = MutableStateFlow(false)
  val assistantPlaybackActive: StateFlow<Boolean> = _assistantPlaybackActive

  private val _turnDiagnostics = MutableStateFlow<ConversationTurnDiagnostics?>(null)
  val turnDiagnostics: StateFlow<ConversationTurnDiagnostics?> = _turnDiagnostics

  private val messageQueue = ArrayDeque<QueuedVoiceTurn>()
  private val sessionSegments = mutableListOf<String>()
  private var lastFinalSegment: String? = null
  private var pendingRunId: String? = null
  private var pendingAssistantEntryId: String? = null
  private var pendingTurnDiagnostics: ConversationTurnDiagnostics? = null
  private var gatewayConnected = false

  private var recognizer: SpeechRecognizer? = null
  private var restartJob: Job? = null
  private var drainJob: Job? = null
  private var playbackResumeJob: Job? = null
  private var pendingRunTimeoutJob: Job? = null
  private var pendingReplyPollJob: Job? = null
  private var pendingInterimMessageJob: Job? = null
  private var pendingPushToTalkFinalizeJob: Job? = null
  private var pushToTalkAudioPumpJob: Job? = null
  private var stopRequested = false
  private var playbackSuppressed = false
  private var resumeAfterPlayback = false
  private var captureState = VoiceCaptureState.Idle
  private var pushToTalkHeld = false
  private var pushToTalkReleasePendingSend = false
  private var segmentedSessionActive = false
  private var usingAudioSourcePushToTalk = false
  private var pushToTalkAudioRecord: AudioRecord? = null
  private var pushToTalkAudioReadFd: ParcelFileDescriptor? = null
  private var pushToTalkAudioWriteStream: ParcelFileDescriptor.AutoCloseOutputStream? = null

  fun setMicEnabled(enabled: Boolean) {
    if (_micEnabled.value == enabled) {
      if (enabled && !isPushToTalkMode() && (!_isListening.value || playbackSuppressed)) {
        playbackSuppressed = false
        resumeAfterPlayback = true
        playbackResumeJob?.cancel()
        stopRequested = false
        start()
      }
      return
    }
    _micEnabled.value = enabled
    if (enabled) {
      captureState = VoiceCaptureState.Idle
      pushToTalkHeld = false
      resumeAfterPlayback = true
      if (playbackSuppressed) {
        _statusText.value = "Assistant speaking"
        sendQueuedIfIdle()
        return
      }
      start()
      sendQueuedIfIdle()
    } else {
      playbackResumeJob?.cancel()
      playbackResumeJob = null
      playbackSuppressed = false
      resumeAfterPlayback = false
      drainJob?.cancel()
      if (isPushToTalkMode()) {
        stop()
        captureState = VoiceCaptureState.Idle
        pushToTalkHeld = false
        _micCooldown.value = false
        _statusText.value = if (_isSending.value) "Mic off | sending..." else "Mic off"
      } else {
        _micCooldown.value = true
        drainJob =
          scope.launch {
            delay(2_000L)
            stop()
            val partial = _liveTranscript.value?.trim().orEmpty()
            if (partial.isNotEmpty() && sessionSegments.isEmpty()) {
              sessionSegments.add(partial)
            }
            flushSessionToQueue()
            drainJob = null
            _micCooldown.value = false
            sendQueuedIfIdle()
          }
      }
    }
  }

  fun startPushToTalkCapture() {
    if (playbackSuppressed) return
    if (pushToTalkHeld) return
    pendingPushToTalkFinalizeJob?.cancel()
    pendingPushToTalkFinalizeJob = null
    pushToTalkHeld = true
    pushToTalkReleasePendingSend = false
    segmentedSessionActive = false
    captureState = VoiceCaptureState.PushToTalkHolding
    _micEnabled.value = true
    _micCooldown.value = false
    stopRequested = false
    sessionSegments.clear()
    lastFinalSegment = null
    _liveTranscript.value = null
    emitTrace(
      source = TraceSource.Client,
      eventType = TraceEventType.VoiceCaptureStarted,
      severity = TraceSeverity.Info,
      description = "Push to Talk capture started",
      payloadText = null,
      sessionId = currentSessionKey().trim().ifEmpty { "main" },
      agentName = null,
      deviceId = "android",
      timestampMs = System.currentTimeMillis(),
    )
    start()
  }

  fun finishPushToTalkCaptureAndSend() {
    if (!pushToTalkHeld && captureState != VoiceCaptureState.PushToTalkReadyToSend) return
    pushToTalkHeld = false
    _micEnabled.value = false
    _micCooldown.value = false
    captureState = VoiceCaptureState.Sending
    pushToTalkReleasePendingSend = true
    restartJob?.cancel()
    restartJob = null
    _isListening.value = false
    _inputLevel.value = 0f
    _statusText.value = "Finishing capture..."
    pendingPushToTalkFinalizeJob?.cancel()
    pendingPushToTalkFinalizeJob =
      scope.launch {
        delay(if (usingAudioSourcePushToTalk) 1_800L else 900L)
        finalizePushToTalkRelease()
      }
    if (usingAudioSourcePushToTalk) {
      finishPushToTalkAudioSource()
    } else {
      mainHandler.post {
        try {
          recognizer?.stopListening()
        } catch (_: Throwable) {
          finalizePushToTalkRelease()
        }
      }
    }
  }

  fun cancelPushToTalkCapture() {
    if (!pushToTalkHeld && captureState == VoiceCaptureState.Idle) return
    pendingPushToTalkFinalizeJob?.cancel()
    pendingPushToTalkFinalizeJob = null
    pushToTalkHeld = false
    pushToTalkReleasePendingSend = false
    segmentedSessionActive = false
    captureState = VoiceCaptureState.Idle
    stop()
    _micEnabled.value = false
    _micCooldown.value = false
    sessionSegments.clear()
    _liveTranscript.value = null
    lastFinalSegment = null
    _statusText.value = if (_isSending.value) "Sending queued voice" else "Mic off"
    emitTrace(
      source = TraceSource.Client,
      eventType = TraceEventType.VoiceCaptureCancelled,
      severity = TraceSeverity.Info,
      description = "Push to Talk capture cancelled",
      payloadText = null,
      sessionId = currentSessionKey().trim().ifEmpty { "main" },
      agentName = null,
      deviceId = "android",
      timestampMs = System.currentTimeMillis(),
    )
  }

  fun onGatewayConnectionChanged(connected: Boolean) {
    gatewayConnected = connected
    if (connected) {
      sendQueuedIfIdle()
      return
    }
    if (messageQueue.isNotEmpty()) {
      _statusText.value = queuedWaitingStatus()
    }
  }

  private fun emitTrace(
    source: TraceSource,
    eventType: TraceEventType,
    severity: TraceSeverity = TraceSeverity.Info,
    description: String,
    payloadText: String? = null,
    sessionId: String? = null,
    agentName: String? = null,
    deviceId: String? = null,
    timestampMs: Long = System.currentTimeMillis(),
  ) {
    traceSink(source, eventType, severity, description, payloadText, sessionId, agentName, deviceId, timestampMs)
  }

  fun handleGatewayEvent(event: String, payloadJson: String?) {
    when (event) {
      "agent" -> {
        if (!payloadJson.isNullOrBlank()) {
          handleAgentEvent(payloadJson)
        }
      }

      "chat" -> {
        if (!payloadJson.isNullOrBlank()) {
          handleChatEvent(payloadJson)
        }
      }
    }
  }

  fun setAssistantPlaybackActive(active: Boolean) {
    _assistantPlaybackActive.value = active
    if (active) {
      captureState = VoiceCaptureState.AssistantPlayback
      if (!_micEnabled.value) return
      playbackResumeJob?.cancel()
      playbackResumeJob = null
      playbackSuppressed = true
      resumeAfterPlayback = true
      restartJob?.cancel()
      restartJob = null
      _isListening.value = false
      _inputLevel.value = 0f
      _liveTranscript.value = null
      _statusText.value = if (_isSending.value) "Assistant speaking | waiting for reply" else "Assistant speaking"
      emitTrace(
        source = TraceSource.Client,
        eventType = TraceEventType.VoicePlaybackStarted,
        severity = TraceSeverity.Info,
        description = "Assistant playback started",
        payloadText = currentPendingAssistantText(),
        sessionId = currentSessionKey().trim().ifEmpty { "main" },
        agentName = null,
        deviceId = "android",
        timestampMs = System.currentTimeMillis(),
      )
      mainHandler.post {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
      }
      return
    }

    if (!playbackSuppressed) return
    emitTrace(
      source = TraceSource.Client,
      eventType = TraceEventType.VoicePlaybackStopped,
      severity = TraceSeverity.Info,
      description = "Assistant playback stopped",
      payloadText = null,
      sessionId = currentSessionKey().trim().ifEmpty { "main" },
      agentName = null,
      deviceId = "android",
      timestampMs = System.currentTimeMillis(),
    )
    playbackSuppressed = false
    if (!_micEnabled.value || !resumeAfterPlayback) return
    playbackResumeJob?.cancel()
    playbackResumeJob =
      scope.launch {
        delay(700L)
        if (stopRequested || playbackSuppressed || !_micEnabled.value) return@launch
        start()
      }
  }

  private fun handleChatEvent(payloadJson: String) {
    val payload =
      try {
        json.parseToJsonElement(payloadJson).asObjectOrNull()
      } catch (_: Throwable) {
        null
      } ?: return

    val activeRunId = pendingRunId ?: return
    val eventRunId = payload["runId"].asStringOrNull() ?: return
    if (eventRunId != activeRunId) return

    when (payload["state"].asStringOrNull()) {
      "delta" -> {
        val deltaText = parseAssistantText(payload)
        if (!deltaText.isNullOrBlank()) {
          markReplyStarted()
          upsertPendingAssistant(text = deltaText.trim(), isStreaming = true)
          emitTrace(
            source = TraceSource.Agent,
            eventType = TraceEventType.AgentMessageReceived,
            severity = TraceSeverity.Info,
            description = "Assistant reply delta received",
            payloadText = payloadJson,
            sessionId = currentSessionKey().trim().ifEmpty { "main" },
            agentName = null,
            deviceId = "android",
            timestampMs = System.currentTimeMillis(),
          )
        }
      }

      "final" -> {
        markTerminalEvent()
        val finalText =
          parseAssistantText(payload)?.trim().orEmpty().ifEmpty {
            currentPendingAssistantText().orEmpty()
          }
        if (finalText.isNotEmpty()) {
          upsertPendingAssistant(text = finalText, isStreaming = false)
          emitTrace(
            source = TraceSource.Agent,
            eventType = TraceEventType.AgentResponseSent,
            severity = TraceSeverity.Info,
            description = "Assistant voice reply finalized",
            payloadText = payloadJson,
            sessionId = currentSessionKey().trim().ifEmpty { "main" },
            agentName = null,
            deviceId = "android",
            timestampMs = System.currentTimeMillis(),
          )
          playAssistantReplyAsync(finalText)
          completePendingTurn(completionSource = "voice-event")
        } else if (pendingAssistantEntryId != null) {
          updateConversationEntry(pendingAssistantEntryId!!, text = null, isStreaming = false)
          _statusText.value = "Waiting for final reply text"
        } else {
          _statusText.value = "Waiting for final reply text"
        }
      }

      "error" -> {
        markTerminalEvent()
        val errorMessage =
          payload["errorMessage"]
            .asStringOrNull()
            ?.trim()
            .orEmpty()
            .ifEmpty { "Voice request failed" }
        upsertPendingAssistant(text = errorMessage, isStreaming = false)
        emitTrace(
          source = TraceSource.Agent,
          eventType = TraceEventType.Error,
          severity = TraceSeverity.Error,
          description = errorMessage,
          payloadText = payloadJson,
          sessionId = currentSessionKey().trim().ifEmpty { "main" },
          agentName = null,
          deviceId = "android",
          timestampMs = System.currentTimeMillis(),
        )
        completePendingTurn(
          completionSource = "voice-error",
          status = ConversationTurnStatus.Error,
        )
      }

      "aborted" -> {
        markTerminalEvent()
        upsertPendingAssistant(text = "⚙️ Agent was aborted.", isStreaming = false)
        completePendingTurn(
          completionSource = "voice-error",
          status = ConversationTurnStatus.Error,
        )
      }
    }
  }

  private fun handleAgentEvent(payloadJson: String) {
    val payload =
      try {
        json.parseToJsonElement(payloadJson).asObjectOrNull()
      } catch (_: Throwable) {
        null
      } ?: return

    val activeRunId = pendingRunId ?: return
    val eventRunId = payload["runId"].asStringOrNull()
    if (!eventRunId.isNullOrBlank() && eventRunId != activeRunId) return

    when (payload["stream"].asStringOrNull()) {
      "assistant" -> {
        if (payload["data"].asObjectOrNull()?.get("type").asStringOrNull()?.equals("thinking", ignoreCase = true) == true) {
          emitTrace(
            source = TraceSource.Agent,
            eventType = TraceEventType.AgentThinking,
            severity = TraceSeverity.Info,
            description = "Assistant is thinking",
            payloadText = payloadJson,
            sessionId = currentSessionKey().trim().ifEmpty { "main" },
            agentName = null,
            deviceId = "android",
            timestampMs = System.currentTimeMillis(),
          )
          return
        }
        val assistantText =
          payload["data"]
            .asObjectOrNull()
            ?.get("text")
            .asStringOrNull()
            ?.trim()
            .orEmpty()
        if (assistantText.isNotEmpty()) {
          markReplyStarted()
          upsertPendingAssistant(text = assistantText, isStreaming = true)
          emitTrace(
            source = TraceSource.Agent,
            eventType = TraceEventType.AgentMessageReceived,
            severity = TraceSeverity.Info,
            description = "Assistant stream received",
            payloadText = payloadJson,
            sessionId = currentSessionKey().trim().ifEmpty { "main" },
            agentName = null,
            deviceId = "android",
            timestampMs = System.currentTimeMillis(),
          )
        }
      }

      "error" -> {
        markTerminalEvent()
        val errorMessage =
          payload["data"]
            .asObjectOrNull()
            ?.get("message")
            .asStringOrNull()
            ?.trim()
            .orEmpty()
            .ifEmpty { "Voice request failed" }
        upsertPendingAssistant(text = errorMessage, isStreaming = false)
        emitTrace(
          source = TraceSource.Agent,
          eventType = TraceEventType.Error,
          severity = TraceSeverity.Error,
          description = errorMessage,
          payloadText = payloadJson,
          sessionId = currentSessionKey().trim().ifEmpty { "main" },
          agentName = null,
          deviceId = "android",
          timestampMs = System.currentTimeMillis(),
        )
        completePendingTurn(
          completionSource = "voice-error",
          status = ConversationTurnStatus.Error,
        )
      }
    }
  }

  private fun start() {
    if (playbackSuppressed) {
      captureState = VoiceCaptureState.AssistantPlayback
      _statusText.value = "Assistant speaking"
      return
    }
    stopRequested = false
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      _statusText.value = "Speech recognizer unavailable"
      _micEnabled.value = false
      return
    }
    if (!hasMicPermission()) {
      _statusText.value = "Microphone permission required"
      _micEnabled.value = false
      return
    }

    mainHandler.post {
      try {
        if (recognizer == null) {
          recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
        }
        startListeningSession()
      } catch (err: Throwable) {
        _statusText.value = "Start failed: ${err.message ?: err::class.simpleName}"
        _micEnabled.value = false
      }
    }
  }

  private fun stop() {
    stopRequested = true
    playbackSuppressed = false
    _assistantPlaybackActive.value = false
    resumeAfterPlayback = false
    restartJob?.cancel()
    restartJob = null
    playbackResumeJob?.cancel()
    playbackResumeJob = null
    _isListening.value = false
    _statusText.value = if (_isSending.value) "Mic off | sending..." else "Mic off"
    _inputLevel.value = 0f
    if (!_isSending.value) {
      captureState = VoiceCaptureState.Idle
    }
    pendingPushToTalkFinalizeJob?.cancel()
    pendingPushToTalkFinalizeJob = null
    pushToTalkReleasePendingSend = false
    segmentedSessionActive = false
    closeRecognizer()
  }

  private fun closeRecognizer() {
    mainHandler.post {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
    }
  }

  private fun preparePushToTalkAudioSource(): Boolean {
    if (!pushToTalkHeld || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    resetPushToTalkAudioSource()

    val minBufferSize =
      AudioRecord.getMinBufferSize(
        pushToTalkAudioSampleRateHz,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    if (minBufferSize <= 0) return false

    val pipe =
      try {
        ParcelFileDescriptor.createPipe()
      } catch (_: Throwable) {
        return false
      }

    val recorder =
      try {
        AudioRecord(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          pushToTalkAudioSampleRateHz,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          minBufferSize * 2,
        )
      } catch (_: Throwable) {
        pipe[0].closeQuietly()
        pipe[1].closeQuietly()
        return false
      }

    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      recorder.release()
      pipe[0].closeQuietly()
      pipe[1].closeQuietly()
      return false
    }

    val readFd = pipe[0]
    val writeStream = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
    pushToTalkAudioReadFd = readFd
    pushToTalkAudioWriteStream = writeStream
    pushToTalkAudioRecord = recorder
    usingAudioSourcePushToTalk = true
    pushToTalkAudioPumpJob =
      scope.launch(Dispatchers.IO) {
        val buffer = ByteArray(minBufferSize.coerceAtLeast(4_096))
        try {
          recorder.startRecording()
          while (pushToTalkHeld && !pushToTalkReleasePendingSend) {
            val bytesRead = recorder.read(buffer, 0, buffer.size)
            if (bytesRead > 0) {
              writeStream.write(buffer, 0, bytesRead)
            } else if (bytesRead == AudioRecord.ERROR_DEAD_OBJECT) {
              break
            }
          }
        } catch (_: Throwable) {
          // Fall through to cleanup; the recognizer result path will recover with whatever text we have.
        } finally {
          try {
            recorder.stop()
          } catch (_: Throwable) {
            // Ignore recorder stop failures during teardown.
          }
          recorder.release()
          try {
            writeStream.flush()
          } catch (_: Throwable) {
            // Ignore flush failures during teardown.
          }
          try {
            writeStream.close()
          } catch (_: Throwable) {
            // Ignore close failures during teardown.
          }
        }
      }
    return true
  }

  private fun finishPushToTalkAudioSource() {
    pushToTalkAudioPumpJob?.cancel()
    pushToTalkAudioPumpJob = null
    pushToTalkAudioRecord?.let { recorder ->
      try {
        recorder.stop()
      } catch (_: Throwable) {
        // Ignore recorder stop failures during teardown.
      }
      recorder.release()
    }
    pushToTalkAudioRecord = null
    pushToTalkAudioWriteStream?.let { stream ->
      try {
        stream.flush()
      } catch (_: Throwable) {
        // Ignore flush failures during teardown.
      }
      try {
        stream.close()
      } catch (_: Throwable) {
        // Ignore close failures during teardown.
      }
    }
    pushToTalkAudioWriteStream = null
  }

  private fun resetPushToTalkAudioSource() {
    finishPushToTalkAudioSource()
    pushToTalkAudioReadFd.closeQuietly()
    pushToTalkAudioReadFd = null
    usingAudioSourcePushToTalk = false
  }

  private fun startListeningSession() {
    if (playbackSuppressed) return
    val recognizerInstance = recognizer ?: return
    val pushToTalkCaptureActive =
      pushToTalkHeld || captureState == VoiceCaptureState.PushToTalkReadyToSend
    val minSessionMs = if (pushToTalkCaptureActive) pushToTalkSpeechMinSessionMs else liveSpeechMinSessionMs
    val completeSilenceMs =
      if (pushToTalkCaptureActive) pushToTalkSpeechCompleteSilenceMs else liveSpeechCompleteSilenceMs
    val possibleSilenceMs =
      if (pushToTalkCaptureActive) pushToTalkSpeechPossibleSilenceMs else liveSpeechPossibleSilenceMs
    val rawAudioPushToTalk = if (pushToTalkCaptureActive) preparePushToTalkAudioSource() else false
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (rawAudioPushToTalk) {
          putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pushToTalkAudioReadFd)
          putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
          putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
          putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pushToTalkAudioSampleRateHz)
          putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
          segmentedSessionActive = true
        } else {
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minSessionMs)
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs)
          putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            possibleSilenceMs,
          )
        }
        if (!rawAudioPushToTalk && pushToTalkCaptureActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          putExtra(
            RecognizerIntent.EXTRA_SEGMENTED_SESSION,
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
          )
          segmentedSessionActive = true
        } else if (!rawAudioPushToTalk) {
          segmentedSessionActive = false
        }
      }
    _statusText.value =
      when {
        pushToTalkCaptureActive && !_isSending.value -> pushToTalkHoldStatus()
        _isSending.value -> "Listening | sending queued voice"
        messageQueue.isNotEmpty() -> "Listening | ${messageQueue.size} queued"
        else -> "Listening"
      }
    _isListening.value = true
    recognizerInstance.startListening(intent)
  }

  private fun scheduleRestart(delayMs: Long = 300L) {
    if (stopRequested) return
    if (!_micEnabled.value) return
    if (playbackSuppressed) return
    restartJob?.cancel()
    restartJob =
      scope.launch {
        delay(delayMs)
        mainHandler.post {
          if (stopRequested || !_micEnabled.value || playbackSuppressed) return@post
          try {
            startListeningSession()
          } catch (_: Throwable) {
            // retry through onError
          }
        }
      }
  }

  private fun flushSessionToQueue() {
    queueCurrentVoiceTurn()
  }

  private fun queueCurrentVoiceTurn() {
    val message = buildVoiceTurnMessage(sessionSegments, _liveTranscript.value, lastFinalSegment)
    sessionSegments.clear()
    _liveTranscript.value = null
    lastFinalSegment = null
    if (message.isNullOrEmpty()) {
      captureState = VoiceCaptureState.Idle
      _statusText.value = if (_isSending.value) "Sending queued voice" else "Mic off"
      return
    }

    appendConversation(
      role = VoiceConversationRole.User,
      text = message,
    )
    val turnId = UUID.randomUUID().toString()
    val diagnostics =
      ConversationTurnDiagnostics(
        mode = ConversationMode.Voice,
        sessionKey = currentSessionKey().trim().ifEmpty { "main" },
        runId = turnId,
        inputPreview = ConversationTurnDiagnostics.preview(message),
        status = ConversationTurnStatus.Sending,
        startedAtMs = System.currentTimeMillis(),
      )
    messageQueue.addLast(
      QueuedVoiceTurn(
        idempotencyKey = turnId,
        text = message,
        diagnostics = diagnostics,
      ),
    )
    pendingTurnDiagnostics = diagnostics
    _turnDiagnostics.value = pendingTurnDiagnostics
    publishQueue()
    emitTrace(
      source = TraceSource.Client,
      eventType = TraceEventType.VoiceTurnQueued,
      severity = TraceSeverity.Info,
      description = "Voice turn queued",
      payloadText = message,
      sessionId = diagnostics.sessionKey,
      agentName = null,
      deviceId = "android",
      timestampMs = diagnostics.startedAtMs,
    )
    sendQueuedIfIdle()
  }

  private fun publishQueue() {
    _queuedMessages.value = messageQueue.map { it.text }
  }

  private fun sendQueuedIfIdle() {
    if (_isSending.value) return
    if (messageQueue.isEmpty()) {
      _statusText.value =
        when {
          playbackSuppressed || _assistantPlaybackActive.value -> "Assistant speaking"
          pushToTalkHeld -> "Listening"
          _micEnabled.value -> "Listening"
          else -> "Mic off"
        }
      if (!pushToTalkHeld && !_micEnabled.value) {
        captureState = VoiceCaptureState.Idle
      }
      return
    }
    if (!gatewayConnected) {
      _statusText.value = queuedWaitingStatus()
      return
    }

    val next = messageQueue.first()
    _isSending.value = true
    captureState = VoiceCaptureState.Sending
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    pendingReplyPollJob?.cancel()
    pendingReplyPollJob = null
    pendingInterimMessageJob?.cancel()
    pendingInterimMessageJob = null
    _statusText.value = if (_micEnabled.value) "Listening | sending queued voice" else "Sending queued voice"
    pendingTurnDiagnostics =
      next.diagnostics.copy(
        runId = next.idempotencyKey,
        status = ConversationTurnStatus.Sending,
      )
    _turnDiagnostics.value = pendingTurnDiagnostics
    emitTrace(
      source = TraceSource.Client,
      eventType = TraceEventType.VoiceTurnSent,
      severity = TraceSeverity.Info,
      description = "Voice turn sent to gateway",
      payloadText = next.text,
      sessionId = next.diagnostics.sessionKey,
      agentName = null,
      deviceId = "android",
      timestampMs = System.currentTimeMillis(),
    )

    scope.launch {
      val replySinceSeconds = System.currentTimeMillis().toDouble() / 1000.0
      try {
        runCatching { ensureReplySubscription() }
        val runId = sendToGateway(next.text, next.idempotencyKey) { earlyRunId ->
          pendingRunId = earlyRunId
        }
        pendingTurnDiagnostics =
          pendingTurnDiagnostics?.copy(
            runId = runId ?: next.idempotencyKey,
            status = ConversationTurnStatus.Waiting,
            sendAckAtMs = System.currentTimeMillis(),
          )
        _turnDiagnostics.value = pendingTurnDiagnostics
        if (runId != null && runId != pendingRunId) {
          pendingRunId = runId
        }
        if (runId == null) {
          cancelPendingReplyTracking()
          messageQueue.removeFirst()
          publishQueue()
          _isSending.value = false
          pendingAssistantEntryId = null
          pendingTurnDiagnostics =
            pendingTurnDiagnostics?.copy(
              status = ConversationTurnStatus.Error,
              completionSource = "voice-error",
              errorMessage = "Gateway did not accept the voice turn",
              resolvedAtMs = System.currentTimeMillis(),
            )
          _turnDiagnostics.value = pendingTurnDiagnostics
          sendQueuedIfIdle()
        } else {
          armPendingRunTimeout(runId)
          armPendingReplyPoll(runId, replySinceSeconds)
          armPendingInterimMessage(runId)
        }
      } catch (err: Throwable) {
        cancelPendingReplyTracking()
        _isSending.value = false
        pendingRunId = null
        pendingAssistantEntryId = null
        pendingTurnDiagnostics =
          pendingTurnDiagnostics?.copy(
            status = ConversationTurnStatus.Error,
            completionSource = "voice-error",
            errorMessage = err.message ?: err::class.simpleName,
            resolvedAtMs = System.currentTimeMillis(),
          )
        _turnDiagnostics.value = pendingTurnDiagnostics
        _statusText.value =
          if (!gatewayConnected) {
            queuedWaitingStatus()
          } else {
            "Send failed: ${err.message ?: err::class.simpleName}"
          }
      }
    }
  }

  private fun armPendingRunTimeout(runId: String) {
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob =
      scope.launch {
        delay(pendingRunTimeoutMs)
        if (pendingRunId != runId) return@launch
        val previewText = currentPendingAssistantText()
        if (!previewText.isNullOrBlank()) {
          upsertPendingAssistant(text = previewText, isStreaming = false)
          playAssistantReplyAsync(previewText)
          completePendingTurn(completionSource = "timeout-preview")
          return@launch
        }
        cancelPendingReplyTracking()
        if (messageQueue.isNotEmpty()) {
          messageQueue.removeFirst()
          publishQueue()
        }
        pendingRunId = null
        pendingAssistantEntryId = null
        _isSending.value = false
        pendingTurnDiagnostics =
          pendingTurnDiagnostics?.copy(
            status = ConversationTurnStatus.Error,
            completionSource = "voice-error",
            errorMessage = "Reply delayed; check chat or try again",
            resolvedAtMs = System.currentTimeMillis(),
          )
        _turnDiagnostics.value = pendingTurnDiagnostics
        _statusText.value =
          if (gatewayConnected) {
            "Reply delayed; check chat or try again"
          } else {
            queuedWaitingStatus()
          }
      }
  }

  private fun armPendingReplyPoll(runId: String, sinceSeconds: Double) {
    pendingReplyPollJob?.cancel()
    pendingReplyPollJob =
      scope.launch {
        val deadline = SystemClock.elapsedRealtime() + pendingRunTimeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
          delay(700)
          if (pendingRunId != runId) return@launch
          val replyText =
            runCatching { loadLatestAssistantReply(sinceSeconds) }
              .getOrNull()
              ?.trim()
              .orEmpty()
          if (replyText.isEmpty()) continue
          markReplyStarted()
          markTerminalEvent()
          upsertPendingAssistant(text = replyText, isStreaming = false)
          playAssistantReplyAsync(replyText)
          completePendingTurn(completionSource = "voice-poll")
          return@launch
        }
      }
  }

  private fun armPendingInterimMessage(runId: String) {
    pendingInterimMessageJob?.cancel()
    pendingInterimMessageJob =
      scope.launch {
        delay(interimAssistantDelayMs)
        if (pendingRunId != runId) return@launch
        if (!currentPendingAssistantText().isNullOrBlank()) return@launch
        val text = interimAssistantText().trim().ifEmpty { "I'm looking into this..." }
        markReplyStarted()
        upsertPendingAssistant(text = text, isStreaming = true)
        playAssistantReplyAsync(text)
      }
  }

  private fun completePendingTurn(
    completionSource: String = "voice-event",
    status: ConversationTurnStatus = ConversationTurnStatus.Complete,
  ) {
    cancelPendingReplyTracking()
    if (messageQueue.isNotEmpty()) {
      messageQueue.removeFirst()
      publishQueue()
    }
    pendingRunId = null
    pendingAssistantEntryId = null
    _isSending.value = false
    pendingTurnDiagnostics =
      pendingTurnDiagnostics?.copy(
        status = status,
        completionSource = completionSource,
        resolvedAtMs = System.currentTimeMillis(),
      )
    _turnDiagnostics.value = pendingTurnDiagnostics
    pendingTurnDiagnostics = null
    captureState = if (pushToTalkHeld) VoiceCaptureState.PushToTalkHolding else VoiceCaptureState.Idle
    sendQueuedIfIdle()
  }

  private fun cancelPendingReplyTracking() {
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    pendingReplyPollJob?.cancel()
    pendingReplyPollJob = null
    pendingInterimMessageJob?.cancel()
    pendingInterimMessageJob = null
  }

  private fun queuedWaitingStatus(): String {
    return "${messageQueue.size} queued | waiting for gateway"
  }

  private fun pushToTalkHoldStatus(): String {
    return if (sessionSegments.isEmpty() && _liveTranscript.value.isNullOrBlank()) {
      "Listening"
    } else {
      "Keep holding or release to send"
    }
  }

  private fun finalizePushToTalkRelease() {
    if (!pushToTalkReleasePendingSend) return
    pushToTalkReleasePendingSend = false
    pendingPushToTalkFinalizeJob?.cancel()
    pendingPushToTalkFinalizeJob = null
    segmentedSessionActive = false
    resetPushToTalkAudioSource()
    closeRecognizer()
    queueCurrentVoiceTurn()
    sendQueuedIfIdle()
  }

  private fun markReplyStarted() {
    val firstReplyWasKnown = pendingTurnDiagnostics?.firstReplyAtMs != null
    pendingTurnDiagnostics =
      pendingTurnDiagnostics?.copy(
        status = ConversationTurnStatus.Streaming,
        firstReplyAtMs = pendingTurnDiagnostics?.firstReplyAtMs ?: System.currentTimeMillis(),
      )
    _turnDiagnostics.value = pendingTurnDiagnostics
    if (!firstReplyWasKnown) {
      emitTrace(
        source = TraceSource.Agent,
        eventType = TraceEventType.VoiceReplyStarted,
        severity = TraceSeverity.Info,
        description = "Assistant reply started",
        payloadText = currentPendingAssistantText(),
        sessionId = currentSessionKey().trim().ifEmpty { "main" },
        agentName = null,
        deviceId = "android",
        timestampMs = System.currentTimeMillis(),
      )
    }
  }

  private fun markTerminalEvent() {
    pendingTurnDiagnostics =
      pendingTurnDiagnostics?.copy(
        terminalEventAtMs = pendingTurnDiagnostics?.terminalEventAtMs ?: System.currentTimeMillis(),
      )
    _turnDiagnostics.value = pendingTurnDiagnostics
  }

  private fun appendConversation(
    role: VoiceConversationRole,
    text: String,
    isStreaming: Boolean = false,
  ): String {
    val id = UUID.randomUUID().toString()
    _conversation.value =
      (_conversation.value + VoiceConversationEntry(id = id, role = role, text = text, isStreaming = isStreaming))
        .takeLast(maxConversationEntries)
    return id
  }

  private fun updateConversationEntry(id: String, text: String?, isStreaming: Boolean) {
    val current = _conversation.value
    if (current.isEmpty()) return

    val targetIndex =
      when {
        current[current.lastIndex].id == id -> current.lastIndex
        else -> current.indexOfFirst { it.id == id }
      }
    if (targetIndex < 0) return

    val entry = current[targetIndex]
    val updatedText = text ?: entry.text
    if (updatedText == entry.text && entry.isStreaming == isStreaming) return
    val updated = current.toMutableList()
    updated[targetIndex] = entry.copy(text = updatedText, isStreaming = isStreaming)
    _conversation.value = updated
  }

  private fun upsertPendingAssistant(text: String, isStreaming: Boolean) {
    val currentId = pendingAssistantEntryId
    if (currentId == null) {
      pendingAssistantEntryId =
        appendConversation(
          role = VoiceConversationRole.Assistant,
          text = text,
          isStreaming = isStreaming,
        )
      return
    }
    updateConversationEntry(id = currentId, text = text, isStreaming = isStreaming)
  }

  private fun playAssistantReplyAsync(text: String) {
    val spoken = text.trim()
    if (spoken.isEmpty()) return
    pendingTurnDiagnostics =
      pendingTurnDiagnostics?.copy(
        playbackStartedAtMs = pendingTurnDiagnostics?.playbackStartedAtMs ?: System.currentTimeMillis(),
      )
    _turnDiagnostics.value = pendingTurnDiagnostics
    setAssistantPlaybackActive(true)
    scope.launch {
      try {
        speakAssistantReply(spoken)
      } catch (err: Throwable) {
        setAssistantPlaybackActive(false)
        Log.w(tag, "assistant speech failed: ${err.message ?: err::class.simpleName}")
      }
    }
  }

  private fun currentPendingAssistantText(): String? {
    val entryId = pendingAssistantEntryId ?: return null
    return _conversation.value.firstOrNull { it.id == entryId }?.text?.trim()?.takeIf { it.isNotEmpty() }
  }

  private fun onFinalTranscript(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    _liveTranscript.value = trimmed
    if (lastFinalSegment == trimmed) return
    lastFinalSegment = trimmed
    sessionSegments.add(trimmed)
  }

  private fun disableMic(status: String) {
    stopRequested = true
    playbackSuppressed = false
    _assistantPlaybackActive.value = false
    resumeAfterPlayback = false
    restartJob?.cancel()
    restartJob = null
    playbackResumeJob?.cancel()
    playbackResumeJob = null
    _micEnabled.value = false
    _isListening.value = false
    _inputLevel.value = 0f
    _statusText.value = status
    pendingPushToTalkFinalizeJob?.cancel()
    pendingPushToTalkFinalizeJob = null
    pushToTalkReleasePendingSend = false
    segmentedSessionActive = false
    closeRecognizer()
  }

  private fun hasMicPermission(): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
  }

  private fun parseAssistantText(payload: JsonObject): String? {
    val message = payload["message"].asObjectOrNull() ?: return null
    if (message["role"].asStringOrNull() != "assistant") return null
    val content = message["content"] as? JsonArray ?: return null

    val parts =
      content.mapNotNull { item ->
        val obj = item.asObjectOrNull() ?: return@mapNotNull null
        if (obj["type"].asStringOrNull() != "text") return@mapNotNull null
        obj["text"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
      }
    if (parts.isEmpty()) return null
    return parts.joinToString("\n")
  }

  private val listener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        _isListening.value = true
      }

      override fun onBeginningOfSpeech() {}

      override fun onRmsChanged(rmsdB: Float) {
        val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        _inputLevel.value = level
      }

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {
        _inputLevel.value = 0f
        if (pushToTalkReleasePendingSend) return
        if (pushToTalkHeld) {
          _statusText.value = pushToTalkHoldStatus()
          return
        }
        scheduleRestart()
      }

      override fun onError(error: Int) {
        if (stopRequested) return
        _isListening.value = false
        _inputLevel.value = 0f
        val status =
          when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "Listening"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported on this device"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable on this device"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service disconnected"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech requests limited; retrying"
            else -> "Speech error ($error)"
          }
        _statusText.value = status

        if (
          error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
        ) {
          disableMic(status)
          return
        }

        if (pushToTalkReleasePendingSend) {
          finalizePushToTalkRelease()
          return
        }

        val restartDelayMs =
          when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> 1_200L
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 2_500L
            else -> 600L
          }
        if (pushToTalkHeld) {
          _statusText.value = pushToTalkHoldStatus()
        }
        scheduleRestart(delayMs = restartDelayMs)
      }

      override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull()
        if (!text.isNullOrBlank()) {
          onFinalTranscript(text)
          if (pushToTalkReleasePendingSend) {
            finalizePushToTalkRelease()
            return
          }
          if (!isPushToTalkMode()) {
            flushSessionToQueue()
          } else {
            captureState = VoiceCaptureState.PushToTalkReadyToSend
            _statusText.value = pushToTalkHoldStatus()
            emitTrace(
              source = TraceSource.Client,
              eventType = TraceEventType.VoiceCaptureReady,
              severity = TraceSeverity.Info,
              description = "Push to Talk transcript captured",
              payloadText = text.trim(),
              sessionId = currentSessionKey().trim().ifEmpty { "main" },
              agentName = null,
              deviceId = "android",
              timestampMs = System.currentTimeMillis(),
            )
          }
        }
        if (pushToTalkReleasePendingSend) {
          finalizePushToTalkRelease()
          return
        }
        if (pushToTalkHeld) {
          if (!segmentedSessionActive) {
            scheduleRestart(delayMs = 220L)
          }
        } else {
          scheduleRestart()
        }
      }

      override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull()
        if (!text.isNullOrBlank()) {
          _liveTranscript.value = text.trim()
        }
      }

      override fun onSegmentResults(segmentResults: Bundle) {
        val text = segmentResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull()
        if (!text.isNullOrBlank()) {
          onFinalTranscript(text)
          if (pushToTalkReleasePendingSend) {
            finalizePushToTalkRelease()
          } else if (pushToTalkHeld) {
            captureState = VoiceCaptureState.PushToTalkReadyToSend
            _statusText.value = pushToTalkHoldStatus()
          }
        }
      }

      override fun onEndOfSegmentedSession() {
        segmentedSessionActive = false
        if (pushToTalkReleasePendingSend) {
          finalizePushToTalkRelease()
        } else if (pushToTalkHeld) {
          scheduleRestart(delayMs = 180L)
        }
      }

      override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

private fun kotlinx.serialization.json.JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun kotlinx.serialization.json.JsonElement?.asStringOrNull(): String? =
  (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun ParcelFileDescriptor?.closeQuietly() {
  try {
    this?.close()
  } catch (_: Throwable) {
    // Ignore close failures during teardown.
  }
}
