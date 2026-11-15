package com.agj.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agj.android.guide.GhostState
import com.agj.android.guide.LabelInfo

/*
 * World-space panels (Compose rendered by the Spatial SDK into quads in the scene; never head-locked).
 * Look: docs/design/XR_NATIVE_SPEC.md §2.4–2.5 (window 0.48 × 0.32 m, toolbar ornament), §3.1 (glass recipe), §2.2
 * (text sizes, as cap heights at 0.8 m), §4.5 (label pill). Colours: docs/design/tokens.css.
 *
 * Panel density: registered at [DP_PER_M] dp per metre, so 1 dp ≈ 1 mm (sizes below are millimetres at true size).
 * Text: cap height ≈ 0.72 em; spec body cap 15.4 mm → 21 sp; title 25 mm → 35 sp; caption 10.5 mm → 15 sp.
 */
const val DP_PER_M = 1000f

object AgColors {
  val glassTop = Color(0xE6181B1F) // rgba(24,27,31,.90)
  val glassBottom = Color(0xE608090B) // rgba(8,9,11,.90)
  val rimTop = Color(0x3DFFFFFF) // white 24 %
  val rimBottom = Color(0x14FFFFFF) // white 8 %
  val textStrong = Color(0xFFF2F2F2)
  val text = Color(0xE0FFFFFF) // 88 %
  val textMeta = Color(0xADFFFFFF) // 68 %
  val accent = Color(0xFF9ECFFF)
  val accentDeep = Color(0xFF274A6B)
  val success = Color(0xFF6FDC9B)
  val warn = Color(0xFFFFA95C)
  val error = Color(0xFFFF8A7A)
  val unsure = Color(0xFFE8C14B)
  val button = Color(0x1AFFFFFF)
  val border = Color(0x29FFFFFF)
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

enum class StatusKind {
  NONE,
  INFO,
  SUCCESS,
  ERROR,
  UNSURE,
}

/** What the step window shows (coach card or guide step). */
data class StepPanelState(
    /** Uppercase status label: "ΒΗΜΑ 3 ΑΠΟ 7" or "2 ΑΠΟ 5" on coach cards. */
    val stepLabel: String = "",
    val page: String = "",
    val title: String = "",
    /** At most two lines (spec §4.13). */
    val body: String = "",
    val hardware: String = "",
    val status: StatusKind = StatusKind.NONE,
    val statusText: String = "",
    /** 0..1 steps done, for the progress bar; null = no bar (coach). */
    val progress: Float? = null,
    val backLabel: String? = null,
    val whereLabel: String? = null,
    val repeatLabel: String? = null,
    val nextLabel: String? = null,
)

class StepPanelModel {
  var state by mutableStateOf(StepPanelState())
  var onBack: () -> Unit = {}
  var onNext: () -> Unit = {}
  var onWhere: () -> Unit = {}
  var onRepeat: () -> Unit = {}
}

class LabelModel {
  var info by mutableStateOf<LabelInfo?>(null)
  var lang by mutableStateOf("en")
}

/** Superellipse-ish continuous corners are approximated by Compose's rounded rect (UNVERIFIED look on device). */
private fun glassShape(r: Dp): Shape = RoundedCornerShape(r)

/** Glass recipe (§3.1): vertical tint, top-left sheen, rim brighter at the top, inner shadow at the bottom. */
private fun Modifier.glass(radius: Dp): Modifier =
    this.clip(glassShape(radius))
        .background(Brush.verticalGradient(listOf(AgColors.glassTop, AgColors.glassBottom)))
        .drawWithContent {
          drawContent()
          // sheen: white 7 % at the top-left → 0 at 45 % of the diagonal
          drawRect(
              Brush.linearGradient(
                  0f to Color(0x12FFFFFF),
                  0.45f to Color.Transparent,
                  start = Offset.Zero,
                  end = Offset(size.width, size.height),
              )
          )
          // inner shadow along the bottom edge (12 mm, black 22 %)
          drawRect(
              Brush.verticalGradient(
                  listOf(Color.Transparent, Color(0x38000000)),
                  startY = size.height - 12.dp.toPx(),
                  endY = size.height,
              ),
              topLeft = Offset(0f, size.height - 12.dp.toPx()),
          )
        }
        .border(1.5.dp, Brush.verticalGradient(listOf(AgColors.rimTop, AgColors.rimBottom)), glassShape(radius))

@Composable
private fun StatusShape(kind: StatusKind, sizeDp: Dp) {
  val c =
      when (kind) {
        StatusKind.SUCCESS -> AgColors.success
        StatusKind.ERROR -> AgColors.error
        StatusKind.UNSURE -> AgColors.unsure
        else -> AgColors.accent
      }
  Canvas(Modifier.size(sizeDp)) {
    val w = size.width
    when (kind) {
      StatusKind.ERROR -> { // octagon
        val p = Path()
        for (i in 0 until 8) {
          val a = Math.PI / 8 + i * Math.PI / 4
          val x = (w / 2 + w / 2 * Math.cos(a)).toFloat()
          val y = (w / 2 + w / 2 * Math.sin(a)).toFloat()
          if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        drawPath(p, c)
      }
      StatusKind.UNSURE -> { // diamond
        val p = Path().apply {
          moveTo(w / 2, 0f); lineTo(w, w / 2); lineTo(w / 2, w); lineTo(0f, w / 2); close()
        }
        drawPath(p, c)
      }
      else -> drawCircle(c)
    }
  }
}

@Composable
private fun AgButton(label: String, primary: Boolean, onClick: () -> Unit) {
  val src = androidx.compose.runtime.remember { MutableInteractionSource() }
  val hovered by src.collectIsHoveredAsState()
  val pressed by src.collectIsPressedAsState()
  val scale = if (pressed) 0.97f else if (hovered) 1.03f else 1f
  val fill =
      when {
        primary && pressed -> Color(0xFF3F6A90)
        primary -> AgColors.accentDeep
        pressed -> Color(0x3DFFFFFF)
        hovered -> Color(0x2EFFFFFF)
        else -> AgColors.button
      }
  Box(
      Modifier.scale(scale)
          .height(44.dp)
          .width(if (primary) 104.dp else 88.dp)
          .clip(RoundedCornerShape(22.dp))
          .background(fill)
          .border(1.dp, if (hovered) Color(0x59FFFFFF) else AgColors.border, RoundedCornerShape(22.dp))
          .hoverable(src)
          .clickable(interactionSource = src, indication = null, onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Text(label, color = AgColors.textStrong, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/** Panel size for [StepPanel]: window 0.48 × 0.32 m plus the toolbar ornament below (overlapping by half). */
const val STEP_PANEL_W = 0.50f
const val STEP_PANEL_H = 0.37f

/** The step window (§2.4) with its toolbar ornament (§2.5). Same window shows the coach cards (§5.4). */
@Composable
fun StepPanel(model: StepPanelModel) {
  val s = model.state
  Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.padding(horizontal = 10.dp).padding(top = 6.dp).width(480.dp).height(320.dp).glass(24.dp).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      s.progress?.let { p ->
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x1FFFFFFF))) {
          Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(4.dp).background(AgColors.accent))
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.status != StatusKind.NONE) StatusShape(s.status, 14.dp)
        Text(
            if (s.status != StatusKind.NONE && s.statusText.isNotEmpty()) s.statusText else s.stepLabel,
            color = AgColors.textMeta,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.9.sp,
        )
        Spacer(Modifier.weight(1f))
        if (s.page.isNotEmpty()) Text(s.page, color = AgColors.textMeta, fontSize = 15.sp, fontWeight = FontWeight.Medium)
      }
      Text(
          s.title,
          color = AgColors.textStrong,
          fontSize = 33.sp,
          lineHeight = 40.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
      if (s.body.isNotEmpty())
          Text(
              s.body,
              color = AgColors.text,
              fontSize = 21.sp,
              lineHeight = 28.sp,
              fontWeight = FontWeight.Medium,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
          )
      Spacer(Modifier.weight(1f))
      if (s.hardware.isNotEmpty())
          Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 12.dp, vertical = 5.dp)) {
            Text(s.hardware, color = AgColors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
          }
    }
    // Toolbar ornament: glass capsule centred under the window, overlapping its bottom edge by half its height.
    val buttons = listOfNotNull(
        s.backLabel?.let { Triple(it, false, model.onBack) },
        s.whereLabel?.let { Triple(it, false, model.onWhere) },
        s.repeatLabel?.let { Triple(it, false, model.onRepeat) },
        s.nextLabel?.let { Triple(it, true, model.onNext) },
    )
    if (buttons.isNotEmpty()) {
      Row(
          Modifier.align(Alignment.BottomCenter)
              .padding(bottom = 8.dp)
              .wrapContentSize()
              .glass(28.dp)
              .padding(horizontal = 6.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        for ((label, primary, cb) in buttons) AgButton(label, primary) { cb() }
      }
    }
  }
}

const val LABEL_PANEL_W = 0.26f
const val LABEL_PANEL_H = 0.04f

/** Part label pill (§4.5): name · code, count chip, status shape when known. Billboarded by the renderer. */
@Composable
fun LabelPill(model: LabelModel) {
  val info = model.info ?: return
  val lang = model.lang
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    Row(
        Modifier.wrapContentSize().glass(14.dp).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      val kind =
          when {
            info.lost -> StatusKind.UNSURE
            info.state == GhostState.CORRECT || info.state == GhostState.GOOD -> StatusKind.SUCCESS
            info.state == GhostState.WRONG -> StatusKind.ERROR
            info.state == GhostState.UNSURE -> StatusKind.UNSURE
            else -> StatusKind.NONE
          }
      if (kind != StatusKind.NONE) StatusShape(kind, 11.dp)
      Text(info.label.name, color = AgColors.textStrong, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
      info.label.code?.let { Text("· $it", color = AgColors.textMeta, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
      info.label.count?.let {
        Box(Modifier.clip(RoundedCornerShape(9.dp)).background(AgColors.accentDeep).padding(horizontal = 7.dp, vertical = 1.dp)) {
          Text(it, color = AgColors.textStrong, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
      }
      if (info.lost) Text(Strings.t(lang, "unsure"), color = AgColors.unsure, fontSize = 14.sp)
    }
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
  Column(
      modifier = Modifier.fillMaxSize().glass(24.dp).padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
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
