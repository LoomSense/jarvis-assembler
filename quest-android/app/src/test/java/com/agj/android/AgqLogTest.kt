package com.agj.android

import com.agj.android.core.AgqLog
import com.agj.android.core.Params
import com.agj.android.core.StepBy
import com.agj.android.core.TrackSource
import com.agj.android.core.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgqLogTest {
  private var now = 1000L
  private val lines = mutableListOf<String>()
  private val log = AgqLog({ now }, { lines += it })

  private fun last(): JsonObject = Json.parseToJsonElement(lines.last()).jsonObject

  @Test
  fun headerFieldsComeFirstAndTimeIsRelative() {
    now = 1250
    log.event("x", mapOf("a" to 1))
    val o = last()
    assertEquals(listOf("t", "app", "ev", "a"), o.keys.toList())
    assertEquals(250L, o["t"]!!.jsonPrimitive.long)
    assertEquals("android", o["app"]!!.jsonPrimitive.content)
    assertEquals("x", o["ev"]!!.jsonPrimitive.content)
    assertFalse(lines.last().contains('\n'))
  }

  @Test
  fun reservedKeysCannotBeOverwritten() {
    log.event("x", mapOf("ev" to "y", "app" to "unity", "t" to -1))
    val o = last()
    assertEquals("x", o["ev"]!!.jsonPrimitive.content)
    assertEquals("android", o["app"]!!.jsonPrimitive.content)
  }

  @Test
  fun startDoesNotLeakKey() {
    log.start("0.1.0", Params(server = "https://h", key = "SECRET", job = "malm-drawer", replay = true))
    assertFalse(lines.last().contains("SECRET"))
    val p = last()["params"]!!.jsonObject
    assertEquals("0.1.0", last()["version"]!!.jsonPrimitive.content)
    assertTrue(p["key_set"]!!.jsonPrimitive.boolean)
    assertTrue(p["replay"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun trackEventShape() {
    log.track(9.8, true, Vec3(0.1f, 0.7f, -0.5f), 12.5, 0.8, TrackSource.MARKER)
    val o = last()
    assertEquals("track", o["ev"]!!.jsonPrimitive.content)
    assertEquals(3, o["pos_m"]!!.jsonArray.size)
    assertEquals(0.7, o["pos_m"]!!.jsonArray[1].jsonPrimitive.double, 1e-6)
    assertEquals("marker", o["source"]!!.jsonPrimitive.content)
    assertTrue(o["tracked"]!!.jsonPrimitive.boolean)

    log.track(0.0, false, null, null, null, TrackSource.NONE)
    val n = last()
    assertEquals(JsonNull, n["pos_m"])
    assertEquals("none", n["source"]!!.jsonPrimitive.content)
  }

  @Test
  fun requiredEvents() {
    log.jobLoaded("malm-drawer", 7, 26, 840)
    assertEquals(26, last()["actions"]!!.jsonPrimitive.int)
    log.identify(3200, "MALM desk", 0.99)
    assertEquals("MALM desk", last()["product"]!!.jsonPrimitive.content)
    log.locate(1100, 2)
    assertEquals(2, last()["next_step"]!!.jsonPrimitive.int)
    log.perception("step_complete", 447)
    assertEquals("step_complete", last()["event"]!!.jsonPrimitive.content)
    log.step(3, 1, StepBy.BUTTON)
    assertEquals("button", last()["by"]!!.jsonPrimitive.content)
    log.frame(72.0, null, null)
    assertEquals(72.0, last()["render_fps"]!!.jsonPrimitive.double, 1e-9)
    log.error("net", "HTTP 403")
    assertEquals("net", last()["where"]!!.jsonPrimitive.content)
    assertEquals(
        listOf("job_loaded", "identify", "locate", "perception", "step", "frame", "error"),
        lines.map { Json.parseToJsonElement(it).jsonObject["ev"]!!.jsonPrimitive.content },
    )
  }

  @Test
  fun nonFiniteNumbersBecomeNull() {
    log.event("x", mapOf("a" to Double.NaN, "b" to Float.POSITIVE_INFINITY, "c" to floatArrayOf(1f, Float.NaN)))
    val o = last()
    assertEquals(JsonNull, o["a"])
    assertEquals(JsonNull, o["b"])
    assertEquals(JsonNull, o["c"]!!.jsonArray[1])
  }
}
