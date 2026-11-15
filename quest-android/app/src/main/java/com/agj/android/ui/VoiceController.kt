package com.agj.android.ui

import android.os.SystemClock
import com.agj.android.core.Agq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mic state shown on the toolbar button (§2.5 "microphone state") and in the status line. */
enum class MicState {
  IDLE,
  LISTENING,
  THINKING,
}

/**
 * Push-to-talk (R7): the toolbar mic button calls [down] at pinch-down and [up] at release. A hold of ≥ 450 ms is
 * push-to-talk (release sends); a short tap starts hands-free recording that ends on 1.2 s of silence, after 7 s, or
 * on a second tap. The clip goes to `POST /api/voice/{job}` and the reply is handed to [Host.onReply] on the main
 * thread (the activity applies `target`, logs `step.by: "voice"`, speaks `answer`).
 */
class VoiceController(
    private val client: VoiceCommandClient?,
    private val scope: CoroutineScope,
    private val host: Host,
    private val recorder: MicRecorder = MicRecorder(),
) {
  interface Host {
    val job: String?
    val lang: String
    val step: Int
    val action: Int

    /** Recording permission granted? (else the controller asks once through [requestPermission]). */
    fun hasPermission(): Boolean

    fun requestPermission()

    fun onMicState(state: MicState)

    /** Main thread. */
    fun onReply(reply: VoiceReply, roundTripMs: Long)

    /** Main thread. */
    fun onVoiceError(message: String)
  }

  var state = MicState.IDLE
    private set

  private var downAt = 0L
  private var startedThisPress = false

  init {
    recorder.onAutoStop = { pcm -> scope.launch(Dispatchers.Main) { send(pcm) } }
  }

  fun down() {
    downAt = SystemClock.elapsedRealtime()
    startedThisPress = false
    when (state) {
      MicState.IDLE -> {
        if (client == null || host.job == null) {
          host.onVoiceError("no server or job")
          return
        }
        if (!host.hasPermission()) {
          host.requestPermission()
          return
        }
        if (recorder.start()) {
          startedThisPress = true
          set(MicState.LISTENING)
          Agq.log.event("mic", mapOf("on" to true))
        } else host.onVoiceError("mic failed")
      }
      MicState.LISTENING -> finish() // second tap ends hands-free recording
      MicState.THINKING -> {}
    }
  }

  fun up() {
    val held = SystemClock.elapsedRealtime() - downAt
    if (startedThisPress && state == MicState.LISTENING && held >= HOLD_MS) finish()
  }

  /** Stop recording now and send. */
  fun finish() {
    if (state != MicState.LISTENING) return
    send(recorder.stop())
  }

  fun cancel() {
    recorder.stop()
    set(MicState.IDLE)
  }

  private fun send(pcm: ShortArray) {
    if (state == MicState.THINKING) return
    val job = host.job
    val c = client
    Agq.log.event("mic", mapOf("on" to false, "ms" to pcm.size / 16))
    if (job == null || c == null || pcm.size < 16_000 / 4) { // < 250 ms: nothing said
      set(MicState.IDLE)
      return
    }
    set(MicState.THINKING)
    val lang = host.lang
    val step = host.step
    val action = host.action
    scope.launch {
      val t0 = SystemClock.elapsedRealtime()
      try {
        val wav = withContext(Dispatchers.Default) { Wav.fromPcm16(pcm) }
        val r = withContext(Dispatchers.IO) { c.audio(job, wav, lang, step, action, speak = true) }
        val ms = SystemClock.elapsedRealtime() - t0
        Agq.log.event(
            "voice_cmd",
            mapOf(
                "transcript" to r.transcript,
                "intent" to r.intent,
                "target" to r.target?.let { mapOf("step" to it.step, "action" to it.action) },
                "answer" to r.answer,
                "tts" to (r.tts != null),
                "ms" to ms,
                "server_ms" to r.latency_ms,
                "model" to r.model,
                "simulated" to r.simulated,
                "audio_ms" to pcm.size / 16,
                "step" to step,
                "action" to action,
            ),
        )
        set(MicState.IDLE)
        host.onReply(r, ms)
      } catch (e: Exception) {
        Agq.log.error("voice_cmd", e.message)
        set(MicState.IDLE)
        host.onVoiceError(e.message ?: "voice failed")
      }
    }
  }

  private fun set(s: MicState) {
    state = s
    host.onMicState(s)
  }

  companion object {
    const val HOLD_MS = 450L
  }
}
