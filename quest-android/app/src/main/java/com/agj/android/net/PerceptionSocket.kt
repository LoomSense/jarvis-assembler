package com.agj.android.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `hello.limits` from the server; defaults are the server's documented values (server/perception/README.md). */
data class PerceptionLimits(
    val maxUnacked: Int = 5,
    val maxHz: Double = 4.0,
    val ackTimeoutMs: Long = 9000,
    val longEdgePx: Int = 768,
    val jpegQuality: Double = 0.7,
)

/** Hand summary, sent as `{type:"hands", …}` at about 10 Hz. */
data class HandSummary(
    val seq: Int,
    val handAtTarget: Boolean,
    val still: Boolean,
    val raised: Boolean,
    val present: Boolean,
    val tsMs: Long,
)

/**
 * Callbacks from the socket. Called on OkHttp's reader thread: post to the main thread before touching the scene.
 * [onEvent] gets every server `event` (`step_complete`, `wrong_hardware`, `flip_needed`, …) as raw JSON; read `event`,
 * `say.{en,el}`, `latency_ms`.
 */
interface PerceptionListener {
  fun onHello(limits: PerceptionLimits, raw: JsonObject) {}

  fun onStatus(raw: JsonObject) {}

  fun onAck(seq: Int?, latencyMs: Long?, dropped: String?) {}

  fun onEvent(event: String, raw: JsonObject) {}

  fun onClosed(code: Int, reason: String) {}

  fun onFailure(t: Throwable) {}
}

/**
 * `WS /ws/perception/{job}?role=display&source=headset&key=…`: camera frames in, guidance events out.
 * Protocol: server/perception/README.md (order: hello → start → frames/hands → step/action → stop).
 *
 * Flow control is the implementation's job: at most `limits.maxUnacked` frames un-acked, at most `limits.maxHz`
 * frames a second, a slot freed after `limits.ackTimeoutMs` without an ack. [sendFrame] returns false when the frame
 * was not sent for those reasons (drop it; do not queue).
 *
 * Threading: all methods are safe to call from any thread.
 */
interface PerceptionSocket {
  fun connect(jobId: String, listener: PerceptionListener)

  fun start(step: Int, action: Int)

  fun step(n: Int, action: Int)

  fun action(index: Int)

  /** JPEG [jpeg] (long edge ≤ limits.longEdgePx). Returns true when sent. */
  fun sendFrame(jpeg: ByteArray, step: Int, tsMs: Long, hint: String = "medium"): Boolean

  fun sendHands(h: HandSummary)

  fun stop()

  fun close()

  companion object {
    /**
     * Binary frame layout: `[uint32 header_len, big-endian][JSON header, UTF-8][JPEG bytes]`.
     * Header fields: `seq` (required), `step`, `ts` (ms), `hint`, `source`.
     */
    fun packFrame(header: JsonObject, jpeg: ByteArray): ByteArray {
      val head = header.toString().encodeToByteArray()
      require(head.size <= 4096) { "frame header over 4096 bytes" }
      return ByteBuffer.allocate(4 + head.size + jpeg.size)
          .order(ByteOrder.BIG_ENDIAN)
          .putInt(head.size)
          .put(head)
          .put(jpeg)
          .array()
    }

    fun frameHeader(seq: Int, step: Int, tsMs: Long, hint: String, source: String = "headset") =
        JsonObject(
            mapOf(
                "seq" to JsonPrimitive(seq),
                "step" to JsonPrimitive(step),
                "ts" to JsonPrimitive(tsMs),
                "hint" to JsonPrimitive(hint),
                "source" to JsonPrimitive(source),
            ),
        )
  }
}

/** Phase 1 stub: connects nowhere, drops every frame. TODO(phase 2, net owner): OkHttp WebSocket implementation. */
class StubPerceptionSocket : PerceptionSocket {
  override fun connect(jobId: String, listener: PerceptionListener) {}

  override fun start(step: Int, action: Int) {}

  override fun step(n: Int, action: Int) {}

  override fun action(index: Int) {}

  override fun sendFrame(jpeg: ByteArray, step: Int, tsMs: Long, hint: String) = false

  override fun sendHands(h: HandSummary) {}

  override fun stop() {}

  override fun close() {}
}
