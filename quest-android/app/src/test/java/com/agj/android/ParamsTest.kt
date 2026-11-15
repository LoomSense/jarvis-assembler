package com.agj.android

import com.agj.android.core.Params
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamsTest {
  @Test
  fun defaults() {
    val p = Params.resolve(null, emptyMap())
    assertEquals("", p.server)
    assertEquals("", p.key)
    assertNull(p.job)
    assertEquals("en", p.lang)
    assertFalse(p.replay)
    assertFalse(p.hud)
  }

  @Test
  fun extrasFromAdb() {
    // `--es` gives strings, `--ez` gives booleans
    val p =
        Params.resolve(
            null,
            mapOf(
                "server" to "https://host.example/",
                "key" to "k1",
                "job" to "malm-drawer",
                "lang" to "EL",
                "replay" to true,
                "hud" to "true",
                "other" to "ignored",
            ),
        )
    assertEquals("https://host.example", p.server)
    assertEquals("k1", p.key)
    assertEquals("malm-drawer", p.job)
    assertEquals("el", p.lang)
    assertTrue(p.replay)
    assertTrue(p.hud)
  }

  @Test
  fun configJsonThenExtrasWin() {
    val cfg = """{"server":"http://10.0.0.2:8000","key":"cfg","job":"knagglig","lang":"el","replay":true,"hud":false}"""
    val fromCfg = Params.resolve(cfg, emptyMap())
    assertEquals("http://10.0.0.2:8000", fromCfg.server)
    assertEquals("knagglig", fromCfg.job)
    assertTrue(fromCfg.replay)

    val p = Params.resolve(cfg, mapOf("job" to "lack", "hud" to true, "lang" to "en"))
    assertEquals("cfg", p.key)
    assertEquals("lack", p.job)
    assertEquals("en", p.lang)
    assertTrue(p.hud)
    assertTrue(p.replay)
  }

  @Test
  fun badInputIsTolerated() {
    assertEquals(Params(), Params.resolve("{not json", emptyMap()))
    assertEquals(Params(), Params.resolve("[1,2]", emptyMap()))
    assertEquals("en", Params.resolve("""{"lang":"de"}""", emptyMap()).lang)
    assertNull(Params.resolve(null, mapOf("job" to "  ")).job)
    assertTrue(Params.bool("1"))
    assertTrue(Params.bool("yes"))
    assertFalse(Params.bool("false"))
    assertFalse(Params.bool(null))
    assertTrue(Params.resolve("""{"replay":"true"}""", emptyMap()).replay)
  }

  @Test
  fun logMapHidesKey() {
    val m = Params(key = "secret").toLogMap()
    assertFalse(m.containsKey("key"))
    assertEquals(true, m["key_set"])
    assertFalse(m.values.any { it == "secret" })
  }
}
