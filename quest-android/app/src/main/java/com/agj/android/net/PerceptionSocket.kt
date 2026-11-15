package com.agj.android.net

import com.agj.android.core.AgqLog
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** `hello.limits` from the server; defaults are the server's documented values (server/perception/README.md). */
data class PerceptionLimits(
    val maxUnacked: Int = 5,
    val maxHz: Double = 4.0,
    val ackTimeoutMs: Long = 9000,
    val longEdgePx: Int = 768,
    val jpegQuality: Double = 0.7,
) {
  companion object {
    fun parse(o: JsonObject?): PerceptionLimits {
      val d = PerceptionLimits()
      if (o == null) return d
      fun n(k: String) = runCatching { o[k]?.jsonPrimitive?.doubleOrNull }.getOrNull()
      return PerceptionLimits(
          maxUnacked = n("max_unacked")?.toInt()?.coerceAtLeast(1) ?: d.maxUnacked,
          maxHz = n("max_hz")?.takeIf { it > 0 } ?: d.maxHz,
          ackTimeoutMs = n("ack_timeout_ms")?.toLong()?.takeIf { it > 0 } ?: d.ackTimeoutMs,
          longEdgePx = n("long_edge_px")?.toInt() ?: d.longEdgePx,
          jpegQuality = n("jpeg_quality") ?: d.jpegQuality,
      )
    }
  }
}

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
 * [onEvent] gets every server `event` as raw JSON (read `event`, `say.{en,el}`, `latency_ms`); the typed callbacks
 * below are called in addition for the events the guide reacts to.
 */
interface PerceptionListener {
  fun onHello(limits: PerceptionLimits, raw: JsonObject) {}

  fun onStatus(raw: JsonObject) {}

  fun onAck(seq: Int?, latencyMs: Long?, dropped: String?) {}

  fun onEvent(event: String, raw: JsonObject) {}

  /** `step_complete` for [step]. */
  fun onStepComplete(step: Int?, raw: JsonObject) {}

  /** `wrong_hardware`: [expected]/[observed] display strings (`expected` null on a step without hardware). */
  fun onWrongHardware(expected: String?, observed: String?, raw: JsonObject) {}

  fun onFlipNeeded(raw: JsonObject) {}

  /** `unsure_show_part`: ask the wearer to show the part. */
  fun onUnsure(raw: JsonObject) {}

  /** Server `error` message (`code`, `message`). */
  fun onError(code: String?, message: String?) {}

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

    /** `{server}/ws/perception/{job}?role=display&source=headset&key=…`; http(s) and ws(s) bases both work. */
    fun url(server: String, jobId: String, key: String, device: String = "quest3-android"): String {
      val base = server.trimEnd('/').replaceFirst(Regex("^ws://"), "http://").replaceFirst(Regex("^wss://"), "https://")
      fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
      return "$base/ws/perception/${enc(jobId)}?role=display&source=headset&device=${enc(device)}&key=${enc(key)}"
    }
  }
}

/** Phase 1 stub: connects nowhere, drops every frame. Kept for tests and offline runs. */
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

/**
 * OkHttp implementation. Reconnects after a failure (1 s, 2 s, 4 s … 15 s) and re-sends the last `start`/`step` so
 * the server session resumes on the same step; a [close] stops that.
 *
 * AGQ events (tag AGQ, [log]): `perception` with `kind` = `hello` | `event` | `error` | `closed` | `failure`,
 * `event` (the server event name), `seq`, `step`, `latency_ms` (server: frame receipt → event), `rtt_ms` (client:
 * frame sent → event or ack), `ms` (= rtt_ms, the field summarize.py reads); and every 5 s `perception_stats` (frames
 * sent / acked / dropped by reason / skipped by flow control, ack round-trip p50, hands sent).
 *
 * @param clockMs monotonic ms clock (tests inject one).
 */
class OkHttpPerceptionSocket(
    private val server: String,
    private val key: String,
    private val log: AgqLog?,
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build(),
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val reconnect: Boolean = true,
) : PerceptionSocket {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private val lock = Any()
  private var ws: WebSocket? = null
  private var jobId: String? = null
  private var listener: PerceptionListener? = null
  private var closedByUser = false
  private var attempts = 0
  private val sched: ScheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor { Thread(it, "agj-perception").apply { isDaemon = true } }

  @Volatile var limits = PerceptionLimits()
    private set

  @Volatile var connected = false
    private set

  // session state, re-sent after a reconnect
  private var started: Pair<Int, Int>? = null
  private var curStep: Int? = null

  // flow control
  private var seq = 0
  private val unacked = LinkedHashMap<Int, Long>() // seq → sent ms
  private val sentAt = HashMap<Int, Long>() // seq → sent ms, kept until its event or 60 s
  private var lastSendMs = Long.MIN_VALUE

  // stats
  private var sent = 0
  private var acked = 0
  private var skipped = 0
  private var handsSent = 0
  private val dropped = HashMap<String, Int>()
  private val rtts = ArrayList<Long>()

  init {
    sched.scheduleAtFixedRate({ runCatching { logStats() } }, 5, 5, TimeUnit.SECONDS)
  }

  override fun connect(jobId: String, listener: PerceptionListener) {
    synchronized(lock) {
      this.jobId = jobId
      this.listener = listener
      closedByUser = false
    }
    open()
  }

  private fun open() {
    val job = synchronized(lock) { jobId } ?: return
    val req = Request.Builder().url(PerceptionSocket.url(server, job, key)).build()
    val socket = client.newWebSocket(req, Handler())
    synchronized(lock) { ws = socket }
  }

  private inner class Handler : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      synchronized(lock) {
        if (webSocket != ws) return
        connected = true
        attempts = 0
        unacked.clear()
        // resume the session after a reconnect
        val s = started
        val step = curStep
        if (s != null) sendJson(mapOf("type" to "start", "step" to (step ?: s.first), "action" to s.second))
      }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      if (webSocket != synchronized(lock) { ws }) return
      val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
      handle(o)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      webSocket.close(1000, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      if (webSocket != synchronized(lock) { ws }) return
      connected = false
      log?.event("perception", mapOf("kind" to "closed", "code" to code, "reason" to reason))
      listener?.onClosed(code, reason)
      // 4400/4404/4409/4401 are refusals: do not retry those
      if (code < 4400 || code >= 4500) retry()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      if (webSocket != synchronized(lock) { ws }) return
      connected = false
      log?.event(
          "perception",
          mapOf("kind" to "failure", "message" to (t.message ?: t.javaClass.simpleName), "http" to response?.code),
      )
      listener?.onFailure(t)
      if (response?.code != 403) retry()
    }
  }

  private fun retry() {
    val delay =
        synchronized(lock) {
          if (closedByUser || !reconnect) return
          attempts++
          minOf(15_000L, 1000L shl minOf(attempts - 1, 4))
        }
    sched.schedule({ if (!synchronized(lock) { closedByUser }) open() }, delay, TimeUnit.MILLISECONDS)
  }

  internal fun handle(o: JsonObject) {
    val type = o.str("type")
    val l = listener
    when (type) {
      "hello" -> {
        val lim = PerceptionLimits.parse(runCatching { o["limits"]?.jsonObject }.getOrNull())
        limits = lim
        log?.event(
            "perception",
            mapOf("kind" to "hello", "vision" to o.str("vision"), "session" to o.str("session"),
                "max_unacked" to lim.maxUnacked, "max_hz" to lim.maxHz, "ack_timeout_ms" to lim.ackTimeoutMs),
        )
        l?.onHello(lim, o)
      }
      "status" -> l?.onStatus(o)
      "ack" -> {
        val s = o.int("seq")
        val lat = o.long("latency_ms")
        val drop = o.str("dropped")
        synchronized(lock) {
          if (s != null) {
            val t = unacked.remove(s)
            if (t != null) {
              rtts += clockMs() - t
              if (rtts.size > 200) rtts.subList(0, 100).clear()
            }
          }
          acked++
          if (drop != null) dropped[drop] = (dropped[drop] ?: 0) + 1
        }
        l?.onAck(s, lat, drop)
      }
      "event" -> {
        val ev = o.str("event") ?: "unknown"
        val s = o.int("seq")
        val rtt = synchronized(lock) { s?.let { sentAt[it] }?.let { clockMs() - it } }
        log?.event(
            "perception",
            mapOf("kind" to "event", "event" to ev, "seq" to s, "step" to o.int("step"),
                "latency_ms" to o.long("latency_ms"), "rtt_ms" to rtt, "ms" to rtt, "p" to o.dbl("p"),
                "votes" to o.str("votes"), "reason" to o.str("reason")),
        )
        l?.onEvent(ev, o)
        when (ev) {
          "step_complete" -> l?.onStepComplete(o.int("step"), o)
          "wrong_hardware" -> l?.onWrongHardware(o.str("expected"), o.str("observed"), o)
          "flip_needed" -> l?.onFlipNeeded(o)
          "unsure_show_part" -> l?.onUnsure(o)
        }
      }
      "error" -> {
        log?.event("perception", mapOf("kind" to "error", "code" to o.str("code"), "message" to o.str("message")))
        l?.onError(o.str("code"), o.str("message"))
      }
    }
  }

  override fun start(step: Int, action: Int) {
    synchronized(lock) {
      started = step to action
      curStep = step
      seq = 0
      unacked.clear()
      sendJson(mapOf("type" to "start", "step" to step, "action" to action))
    }
  }

  override fun step(n: Int, action: Int) {
    synchronized(lock) {
      curStep = n
      started = started?.let { n to action }
      unacked.clear() // frames of the old step are acked stale; free the slots now
      sendJson(mapOf("type" to "step", "n" to n, "action" to action))
    }
  }

  override fun action(index: Int) {
    synchronized(lock) {
      started = started?.let { it.first to index }
      sendJson(mapOf("type" to "action", "index" to index, "step" to curStep))
    }
  }

  override fun sendFrame(jpeg: ByteArray, step: Int, tsMs: Long, hint: String): Boolean {
    synchronized(lock) {
      val w = ws
      if (w == null || !connected || started == null) {
        skipped++
        return false
      }
      val now = clockMs()
      val lim = limits
      // free slots whose ack did not come in time
      val it = unacked.entries.iterator()
      while (it.hasNext()) if (now - it.next().value > lim.ackTimeoutMs) it.remove()
      if (unacked.size >= lim.maxUnacked) {
        skipped++
        return false
      }
      val minGap = (1000.0 / lim.maxHz).toLong()
      if (lastSendMs != Long.MIN_VALUE && now - lastSendMs < minGap) {
        skipped++
        return false
      }
      val s = ++seq
      val bytes = PerceptionSocket.packFrame(PerceptionSocket.frameHeader(s, step, tsMs, hint), jpeg)
      if (!w.send(bytes.toByteString())) {
        skipped++
        return false
      }
      lastSendMs = now
      unacked[s] = now
      sentAt[s] = now
      if (sentAt.size > 300) sentAt.keys.take(150).forEach { k -> sentAt.remove(k) }
      sent++
      return true
    }
  }

  override fun sendHands(h: HandSummary) {
    synchronized(lock) {
      if (!connected) return
      handsSent++
      sendJson(
          mapOf(
              "type" to "hands",
              "seq" to h.seq,
              "hand_at_target" to h.handAtTarget,
              "still" to h.still,
              "raised" to h.raised,
              "present" to h.present,
              "ts" to h.tsMs,
          ),
      )
    }
  }

  override fun stop() {
    synchronized(lock) {
      started = null
      sendJson(mapOf("type" to "stop"))
    }
  }

  override fun close() {
    synchronized(lock) {
      closedByUser = true
      ws?.close(1000, "bye")
      ws = null
      connected = false
    }
    sched.shutdownNow()
  }

  /** Frames that may be sent now (slots and rate), for callers that want to skip the JPEG encode. */
  fun canSend(): Boolean =
      synchronized(lock) {
        val now = clockMs()
        connected &&
            started != null &&
            unacked.count { now - it.value <= limits.ackTimeoutMs } < limits.maxUnacked &&
            (lastSendMs == Long.MIN_VALUE || now - lastSendMs >= (1000.0 / limits.maxHz).toLong())
      }

  private fun sendJson(m: Map<String, Any?>) {
    val w = ws ?: return
    w.send(AgqLog.toJson(m).toString())
  }

  private fun logStats() {
    val l = log ?: return
    val m =
        synchronized(lock) {
          if (sent == 0 && handsSent == 0 && acked == 0) return
          val sorted = rtts.sorted()
          mapOf(
              "connected" to connected,
              "sent" to sent,
              "acked" to acked,
              "skipped" to skipped,
              "unacked" to unacked.size,
              "dropped" to HashMap(dropped),
              "ack_rtt_ms_p50" to sorted.getOrNull(sorted.size / 2),
              "hands" to handsSent,
          ).also {
            sent = 0
            acked = 0
            skipped = 0
            handsSent = 0
            dropped.clear()
          }
        }
    l.event("perception_stats", m)
  }

  private fun JsonObject.str(k: String) = runCatching { this[k]?.jsonPrimitive?.contentOrNull }.getOrNull()

  private fun JsonObject.int(k: String) = runCatching { this[k]?.jsonPrimitive?.intOrNull }.getOrNull()

  private fun JsonObject.long(k: String) = runCatching { this[k]?.jsonPrimitive?.longOrNull }.getOrNull()

  private fun JsonObject.dbl(k: String) = runCatching { this[k]?.jsonPrimitive?.doubleOrNull }.getOrNull()
}
