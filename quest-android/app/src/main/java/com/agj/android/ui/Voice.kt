package com.agj.android.ui

import com.agj.android.core.Agq

/**
 * Speaks guidance in the launch language (`el` | `en`). Server voice first (`POST /api/tts` → WAV, Gemini TTS, the
 * same voice as the web client); Android `TextToSpeech` as an offline fallback (Greek voice availability on Horizon OS
 * is UNVERIFIED).
 *
 * Threading: [say] from any thread; it must not block (fetch and playback run on a background thread). A new [say]
 * with `interrupt = true` stops the current sentence.
 */
interface Voice {
  fun say(text: String, lang: String, interrupt: Boolean = true)

  fun stop()

  /** True while a sentence plays (for the HUD, and to avoid talking over a perception event). */
  val speaking: Boolean
}

/** Phase 1 stub: logs what would be said. TODO(phase 2, ui owner): ServerApi.tts + AudioTrack, TextToSpeech fallback. */
class StubVoice : Voice {
  override fun say(text: String, lang: String, interrupt: Boolean) {
    Agq.log.event("voice", mapOf("lang" to lang, "text" to text, "stub" to true))
  }

  override fun stop() {}

  override val speaking = false
}
