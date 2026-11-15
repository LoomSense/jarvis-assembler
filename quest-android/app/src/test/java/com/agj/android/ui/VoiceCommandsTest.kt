package com.agj.android.ui

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** `POST /api/voice/{job}` request/response mapping (server/perception/README.md "Voice endpoint"). */
class VoiceCommandsTest {
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() = server.shutdown()

  private fun client() = VoiceCommandClient(server.url("/").toString(), "demo-key")

  @Test
  fun wavHeaderIs16kMonoPcm16() {
    val pcm = shortArrayOf(0, 1000, -1000, 32767)
    val wav = Wav.fromPcm16(pcm)
    assertEquals(44 + 8, wav.size)
    assertEquals("RIFF", String(wav, 0, 4))
    assertEquals("WAVE", String(wav, 8, 4))
    val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(36 + 8, b.getInt(4))
    assertEquals(1, b.getShort(20).toInt()) // PCM
    assertEquals(1, b.getShort(22).toInt()) // mono
    assertEquals(16_000, b.getInt(24))
    assertEquals(32_000, b.getInt(28)) // byte rate
    assertEquals(16, b.getShort(34).toInt())
    assertEquals("data", String(wav, 36, 4))
    assertEquals(8, b.getInt(40))
    assertEquals(1000, b.getShort(46).toInt())
    assertEquals(-1000, b.getShort(48).toInt())
  }

  @Test
  fun rmsOfSilenceAndTone() {
    assertEquals(0f, Wav.rms(ShortArray(800)), 1e-6f)
    assertEquals(0.5f, Wav.rms(ShortArray(800) { if (it % 2 == 0) 16384 else -16384 }), 1e-3f)
  }

  @Test
  fun audioRequestCarriesTheContractFields() = runBlocking {
    server.enqueue(
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{"transcript":"επόμενο","intent":"next","answer":"Πάρε το δεξί πλαϊνό.","target":{"step":2,"action":2},
                  |"actions":[],"tts":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}","tts_ms":2100,
                  |"latency_ms":2300,"model":"gemini-4-hydrogen","simulated":false,"extra":{"ignored":true}}""".trimMargin()
            )
    )
    val wav = Wav.fromPcm16(ShortArray(16_000))
    val r = client().audio("malm-drawer", wav, "el", 2, 1)

    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("/api/voice/malm-drawer", req.path)
    assertEquals("demo-key", req.getHeader("X-Demo-Key"))
    assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
    assertArrayEquals(wav, Base64.getDecoder().decode(body["audio"]!!.jsonPrimitive.content))
    assertEquals("audio/wav", body["mime"]!!.jsonPrimitive.content)
    assertEquals("el", body["lang"]!!.jsonPrimitive.content)
    assertEquals(2, body["step"]!!.jsonPrimitive.int)
    assertEquals(1, body["action"]!!.jsonPrimitive.int)
    assertTrue(body["speak"]!!.jsonPrimitive.boolean)
    assertEquals(16_000, body["rate"]!!.jsonPrimitive.int)

    assertEquals("επόμενο", r.transcript)
    assertEquals("next", r.intent)
    assertTrue(r.isCommand)
    assertEquals(VoiceTarget(2, 2), r.target)
    assertEquals("Πάρε το δεξί πλαϊνό.", r.answer)
    assertArrayEquals(byteArrayOf(1, 2, 3), r.ttsWav())
    assertEquals(2300L, r.latency_ms)
    assertEquals("gemini-4-hydrogen", r.model)
    assertFalse(r.simulated)
  }

  @Test
  fun endOfGuideHasNullTargetAndAskIsNotACommand() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"transcript":"next","intent":"next","answer":"This is the last step.","target":null}"""))
    server.enqueue(
        MockResponse()
            .setBody(
                """{"transcript":"which screw?","intent":"ask","answer":"Step 2 uses 2 x wooden dowel 101345, page 5.",
                  |"target":{"step":2,"action":0},"tts":null,"tts_error":"rate","simulated":true}""".trimMargin()
            )
    )
    val end = client().text("malm-drawer", "next", "en", 7, 3)
    assertNull(end.target)
    assertTrue(end.isCommand)
    val ask = client().text("malm-drawer", "which screw?", "en", 2, 0)
    assertFalse(ask.isCommand)
    assertNull(ask.ttsWav())
    assertTrue(ask.simulated)
    server.takeRequest()
    val typed = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    assertEquals("which screw?", typed["text"]!!.jsonPrimitive.content)
    assertFalse(typed.containsKey("audio"))
  }

  @Test
  fun httpErrorsSurfaceWithTheirCode() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"bad base64"}"""))
    try {
      client().text("malm-drawer", "next", "en", 99, 0)
      fail("expected an error")
    } catch (e: VoiceHttpException) {
      assertEquals(422, e.code)
    }
  }

  @Test
  fun noneIntentDefaults() {
    val r = VoiceCommandClient.parse("""{"transcript":"","intent":"none","answer":""}""")
    assertFalse(r.isCommand)
    assertNull(r.target)
    assertEquals("", r.answer)
  }
}
