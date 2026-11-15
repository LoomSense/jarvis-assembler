package com.agj.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * World-space panels (Compose rendered by the Spatial SDK into a quad in the scene; never head-locked).
 * Colours from docs/design/tokens.css (--ag-*). The look (glass, angular text sizes, motion) is owned by
 * docs/design/XR_NATIVE_SPEC.md; this file is the phase 1 placeholder with the right data flow.
 */
object AgColors {
  val surfaceGlass = Color(0xD10C0C0C) // --ag-surface-glass rgba(12,12,12,.82)
  val border = Color(0x24FFFFFF) // --ag-border-strong-ish
  val text = Color(0xD1FFFFFF) // --ag-text
  val textStrong = Color.White
  val textMeta = Color(0xA8FFFFFF)
  val accent = Color(0xFF9ECFFF) // --ag-accent
  val success = Color(0xFF6FDC9B)
  val warn = Color(0xFFFFA95C)
  val error = Color(0xFFFF8A7A)
}

/** Everything the debug HUD shows. Immutable snapshot; the activity replaces it (main thread). */
data class HudState(
    val renderFps: Double = 0.0,
    val cameraFps: Double = 0.0,
    val cameraKind: String = "-",
    val tracked: Boolean = false,
    val trackSource: String = "none",
    val reprojPx: Double? = null,
    val lastPerception: String = "-",
    val lastPerceptionMs: Long? = null,
    val step: Int? = null,
    val action: Int? = null,
    val stage: String = "-",
    val server: String = "",
    val serverOk: Boolean? = null,
)

/** Compose-observable holder: write [state] on the main thread. */
class HudModel {
  var state by mutableStateOf(HudState())
}

/** What the step panel shows. */
data class StepPanelState(
    val title: String = "",
    val body: String = "",
    val stepLabel: String = "",
    val hardware: String = "",
)

class StepPanelModel {
  var state by mutableStateOf(StepPanelState())
}

@Composable
private fun Glass(content: @Composable () -> Unit) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(AgColors.surfaceGlass, RoundedCornerShape(28.dp))
              .border(1.dp, AgColors.border, RoundedCornerShape(28.dp))
              .padding(28.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    content()
  }
}

/**
 * The step panel: anchored next to the assembly (world space). TODO(phase 2, ui owner): follow XR_NATIVE_SPEC.md
 * (glass, typography, parts card, transitions).
 */
@Composable
fun StepPanel(model: StepPanelModel) {
  val s = model.state
  Glass {
    Text(s.stepLabel, color = AgColors.accent, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    Text(s.title, color = AgColors.textStrong, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
    Text(s.body, color = AgColors.text, fontSize = 26.sp)
    if (s.hardware.isNotEmpty()) Text(s.hardware, color = AgColors.textMeta, fontSize = 22.sp)
  }
}

private fun fmt(v: Double?, digits: Int = 1) = v?.let { "%.${digits}f".format(it) } ?: "-"

/** Debug HUD (launch parameter `hud=true`): fps, tracker, last perception event + latency, step, server. */
@Composable
fun Hud(model: HudModel) {
  val s = model.state
  val mono = FontFamily.Monospace
  @Composable
  fun line(label: String, value: String, color: Color = AgColors.text) =
      Text("$label  $value", color = color, fontSize = 20.sp, fontFamily = mono)
  Glass {
    line("render", "${fmt(s.renderFps)} fps")
    line("camera", "${s.cameraKind} ${fmt(s.cameraFps)} fps")
    line(
        "track",
        "${if (s.tracked) "ok" else "lost"} (${s.trackSource}) reproj ${fmt(s.reprojPx)} px",
        if (s.tracked) AgColors.success else AgColors.warn,
    )
    line("percep", "${s.lastPerception} ${s.lastPerceptionMs?.let { "$it ms" } ?: ""}")
    line("stage", s.stage)
    line("step", "${s.step ?: "-"} / action ${s.action ?: "-"}")
    line(
        "server",
        "${s.server.ifEmpty { "(not set)" }} ${when (s.serverOk) { true -> "ok"; false -> "unreachable"; null -> "…" }}",
        when (s.serverOk) {
          true -> AgColors.success
          false -> AgColors.error
          null -> AgColors.textMeta
        },
    )
  }
}
