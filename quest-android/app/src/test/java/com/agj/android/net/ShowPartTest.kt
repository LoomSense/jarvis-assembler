package com.agj.android.net

import com.agj.android.ui.HeldEvent
import com.agj.android.ui.Verdict
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The explicit "check this part": the socket's `show_part` out, `show_part_result` back (server/perception/README.md). */
class ShowPartTest {
  private fun json(s: String?): JsonObject = Json.parseToJsonElement(s!!).jsonObject

  @Test
  fun showPartIsSentOnlyInAStartedSessionAndItsResultComesBackAsAnEvent() {
    val server = MockWebServer()
    val fromClient = LinkedBlockingQueue<String>()
    val opened = CountDownLatch(1)
    val serverWs = AtomicReference<WebSocket>()
    server.enqueue(
        MockResponse()
            .withWebSocketUpgrade(
                object : WebSocketListener() {
                  override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverWs.set(webSocket)
                    webSocket.send("""{"type":"hello","protocol":1,"job_id":"malm-drawer","session":"s1","vision":"simulated","limits":{}}""")
                    opened.countDown()
                  }

                  override fun onMessage(webSocket: WebSocket, text: String) {
                    fromClient.put(text)
                  }

                  override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                  }
                }))
    server.start()
    val sock = OkHttpPerceptionSocket(server.url("/").toString().trimEnd('/'), "key", null, reconnect = false)
    try {
      assertFalse("not connected: nothing to ask", sock.showPart("118331"))
      val hello = CountDownLatch(1)
      val events = LinkedBlockingQueue<Pair<String, JsonObject>>()
      sock.connect(
          "malm-drawer",
          object : PerceptionListener {
            override fun onHello(limits: PerceptionLimits, raw: JsonObject) = hello.countDown()

            override fun onEvent(event: String, raw: JsonObject) {
              events.put(event to raw)
            }
          },
      )
      assertTrue(opened.await(5, TimeUnit.SECONDS))
      assertTrue(hello.await(5, TimeUnit.SECONDS))
      assertFalse("before `start` the server answers not_started", sock.showPart("118331"))
      sock.start(2, 0)
      assertEquals("start", json(fromClient.poll(5, TimeUnit.SECONDS))["type"]!!.jsonPrimitive.content)

      assertTrue(sock.showPart("118331"))
      val asked = json(fromClient.poll(5, TimeUnit.SECONDS))
      assertEquals("show_part", asked["type"]!!.jsonPrimitive.content)
      assertEquals("118331", asked["expected"]!!.jsonPrimitive.content)
      // without an expected item the server uses the current action's
      assertTrue(sock.showPart(null))
      val plain = json(fromClient.poll(5, TimeUnit.SECONDS))
      assertEquals("show_part", plain["type"]!!.jsonPrimitive.content)
      assertNull(plain["expected"])

      // the answer arrives as an ordinary event and reads as a verdict the person asked for
      serverWs.get().send(
          """{"type":"event","event":"show_part_result","step":2,"page":3,"expected":"cam bolt 118331","observed":"cam bolt 118331",
            "expected_id":"118331","observed_id":"118331","p":0.94,"match":true,"evidence":{"part_in_hand.top":0.1,"hardware_in_hand.top":0.94},
            "say":{"en":"Good. That's the cam bolt, 118331.","el":"Ωραία. Κρατάς βίδα έκκεντρου, 118331."},"speak":true,"seq":7,"latency_ms":900}"""
              .replace("\n", ""))
      val (name, raw) = events.poll(5, TimeUnit.SECONDS)!!
      assertEquals("show_part_result", name)
      val ev = HeldEvent.parse(name, raw, "el")!!
      assertEquals(Verdict.RIGHT, ev.verdict)
      assertEquals(HeldEvent.MANUAL, ev.source)
      assertEquals("118331", ev.expectedId)
      assertEquals(0.94, ev.p!!, 1e-9)
      assertEquals(2, ev.step)

      sock.stop()
      assertEquals("stop", json(fromClient.poll(5, TimeUnit.SECONDS))["type"]!!.jsonPrimitive.content)
      assertFalse("after `stop` there is no step to check against", sock.showPart("118331"))
    } finally {
      sock.close()
      runCatching { server.shutdown() }
    }
  }
}
