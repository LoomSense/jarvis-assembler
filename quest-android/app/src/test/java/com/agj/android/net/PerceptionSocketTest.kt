package com.agj.android.net

import com.agj.android.core.AgqLog
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The OkHttp socket against a scripted MockWebServer that speaks server/perception/README.md. */
class PerceptionSocketTest {
  private lateinit var server: MockWebServer
  private val fromClient = LinkedBlockingQueue<Any>() // String (JSON) or ByteString (frame)
  @Volatile private var serverWs: WebSocket? = null
  private val opened = CountDownLatch(1)
  private val logLines = LinkedBlockingQueue<String>()

  @Before
  fun setUp() {
    server = MockWebServer()
    server.enqueue(
        MockResponse()
            .withWebSocketUpgrade(
                object : WebSocketListener() {
                  override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverWs = webSocket
                    webSocket.send(
                        """{"type":"hello","protocol":1,"job_id":"malm-drawer","session":"s1","vision":"simulated",
                           "limits":{"max_unacked":2,"in_flight":4,"max_hz":1000,"ack_timeout_ms":9000,"long_edge_px":768,"jpeg_quality":0.7}}"""
                            .replace("\n", ""))
                    opened.countDown()
                  }

                  override fun onMessage(webSocket: WebSocket, text: String) {
                    fromClient.put(text)
                  }

                  override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    fromClient.put(bytes)
                  }

                  override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                  }
                }))
    server.start()
  }

  @After
  fun tearDown() {
    runCatching { serverWs?.close(1000, null) }
    runCatching { server.shutdown() }
  }

  private fun json(s: Any?): JsonObject = Json.parseToJsonElement(s as String).jsonObject

  @Test
  fun helloLimitsFlowControlEventsAndHands() {
    var clock = 1_000L
    val log = AgqLog({ 0L }, { logLines.put(it) })
    val sock = OkHttpPerceptionSocket(server.url("/").toString().trimEnd('/'), "k&y", log, clockMs = { clock }, reconnect = false)
    val hello = CountDownLatch(1)
    val stepDone = CountDownLatch(1)
    val acks = LinkedBlockingQueue<Int>()
    var wrong: Pair<String?, String?>? = null
    sock.connect(
        "malm-drawer",
        object : PerceptionListener {
          override fun onHello(limits: PerceptionLimits, raw: JsonObject) {
            hello.countDown()
          }

          override fun onAck(seq: Int?, latencyMs: Long?, dropped: String?) {
            seq?.let { acks.put(it) }
          }

          override fun onStepComplete(step: Int?, raw: JsonObject) {
            assertEquals(2, step)
            stepDone.countDown()
          }

          override fun onWrongHardware(expected: String?, observed: String?, raw: JsonObject) {
            wrong = expected to observed
          }
        },
    )
    assertTrue(opened.await(5, TimeUnit.SECONDS))
    assertTrue(hello.await(5, TimeUnit.SECONDS))
    val req = server.takeRequest(5, TimeUnit.SECONDS)!!
    assertEquals("/ws/perception/malm-drawer", req.requestUrl!!.encodedPath)
    assertEquals("display", req.requestUrl!!.queryParameter("role"))
    assertEquals("headset", req.requestUrl!!.queryParameter("source"))
    assertEquals("k&y", req.requestUrl!!.queryParameter("key"))
    assertEquals(2, sock.limits.maxUnacked)

    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9)
    assertFalse("no frames before start", sock.sendFrame(jpeg, 2, 1L))
    sock.start(2, 0)
    val start = json(fromClient.poll(5, TimeUnit.SECONDS))
    assertEquals("start", start["type"]!!.jsonPrimitive.content)
    assertEquals(2, start["step"]!!.jsonPrimitive.int)

    assertTrue(sock.sendFrame(jpeg, 2, 1L))
    clock += 10
    assertTrue(sock.sendFrame(jpeg, 2, 2L))
    clock += 10
    assertFalse("max_unacked = 2", sock.sendFrame(jpeg, 2, 3L))
    val f1 = fromClient.poll(5, TimeUnit.SECONDS) as ByteString
    val bb = ByteBuffer.wrap(f1.toByteArray())
    val n = bb.int
    val head = Json.parseToJsonElement(f1.toByteArray().copyOfRange(4, 4 + n).decodeToString()).jsonObject
    assertEquals(1, head["seq"]!!.jsonPrimitive.int)
    assertEquals("headset", head["source"]!!.jsonPrimitive.content)
    fromClient.poll(5, TimeUnit.SECONDS) // frame 2

    serverWs!!.send("""{"type":"ack","seq":1,"latency_ms":800,"used":true,"dropped":null}""")
    assertEquals(1, acks.poll(5, TimeUnit.SECONDS))
    assertTrue("slot freed by the ack", sock.sendFrame(jpeg, 2, 4L))
    fromClient.poll(5, TimeUnit.SECONDS)

    // ack timeout frees slots too
    clock += 9_001
    assertTrue(sock.sendFrame(jpeg, 2, 5L))
    fromClient.poll(5, TimeUnit.SECONDS)

    serverWs!!.send(
        """{"type":"event","event":"wrong_hardware","step":2,"expected":"long screw 108363","observed":"short screw 108560","seq":2,"latency_ms":43,"say":{"en":"x","el":"y"}}""")
    serverWs!!.send("""{"type":"event","event":"step_complete","step":2,"seq":3,"latency_ms":900,"reason":"hand_summary"}""")
    assertTrue(stepDone.await(5, TimeUnit.SECONDS))
    assertEquals("long screw 108363" to "short screw 108560", wrong)

    sock.sendHands(HandSummary(4, handAtTarget = true, still = false, raised = false, present = true, tsMs = 99))
    val hands = json(fromClient.poll(5, TimeUnit.SECONDS))
    assertEquals("hands", hands["type"]!!.jsonPrimitive.content)
    assertTrue(hands["hand_at_target"]!!.jsonPrimitive.boolean)
    assertTrue(hands["present"]!!.jsonPrimitive.boolean)

    sock.step(3, 0)
    val step = json(fromClient.poll(5, TimeUnit.SECONDS))
    assertEquals("step", step["type"]!!.jsonPrimitive.content)
    assertEquals(3, step["n"]!!.jsonPrimitive.int)
    sock.action(1)
    assertEquals(1, json(fromClient.poll(5, TimeUnit.SECONDS))["index"]!!.jsonPrimitive.int)

    // AGQ perception events with latency
    val events = generateSequence { logLines.poll() }.map { Json.parseToJsonElement(it).jsonObject }
        .filter { it["ev"]!!.jsonPrimitive.content == "perception" }.toList()
    assertNotNull(events.firstOrNull { it["kind"]!!.jsonPrimitive.content == "hello" })
    val sc = events.first { it["event"]?.jsonPrimitive?.content == "step_complete" }
    assertEquals(900, sc["latency_ms"]!!.jsonPrimitive.int)
    sock.close()
  }

  @Test
  fun urlBuildsFromHttpAndWsBases() {
    assertEquals(
        "https://h.example/ws/perception/malm-drawer?role=display&source=headset&device=quest3-android&key=a%2Bb",
        PerceptionSocket.url("https://h.example/", "malm-drawer", "a+b"))
    assertTrue(PerceptionSocket.url("wss://h", "j", "k").startsWith("https://h/ws/perception/j?"))
  }

  @Test
  fun limitsParseWithDefaults() {
    val l = PerceptionLimits.parse(Json.parseToJsonElement("""{"max_unacked":3,"max_hz":"x"}""").jsonObject)
    assertEquals(3, l.maxUnacked)
    assertEquals(4.0, l.maxHz, 0.0)
    assertEquals(9000L, l.ackTimeoutMs)
  }
}
