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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
 * Text (spec §2.2, em sizes): body 1.25° at 0.8 m = 17.5 mm → 17.5 sp; title 2.0° = 27.9 mm → 28 sp; label name
 * 1.2° = 16.8 mm → 17 sp. Line height 1.2 titles, 1.36 body.
 * UI-FLOOR: the caption (the smallest text anywhere) is 0.85° at the FAR end of the window zone, 0.9 m (13.4 mm →
 * 13.5 sp; it was 12 sp, 0.76° there); the step window's one instruction is the hero, 1.5 × the title (3.0° at 0.8 m).
 */
object TextSize {
  val caption = 13.5.sp
  val body = 17.5.sp
  val bodyLine = 24.sp
  val title = 28.sp
  val titleLine = 34.sp
  val hero = 42.sp
  val heroLine = 48.sp
  val labelName = 17.sp
  val button = 17.sp
  val primary = 20.sp
}
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
  /** Glyph on a status shape (✕ ? ✓): the window's dark glass. */
  val glyph = Color(0xFF14171B)
  /** tokens.css `--ag-scrim`: black 60 % under text that sits over passthrough (bright boards, sunlit floor). */
  val scrim = Color(0x99000000)
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

/** The status shape (circle ✓, octagon ✕, diamond ?) for the package's other files: the held-part badge, the parts card. */
@Composable internal fun StatusGlyph(kind: StatusKind, sizeDp: Dp) = StatusShape(kind, sizeDp)

/** What the step window shows (coach card or guide step). */
data class StepPanelState(
    /** Uppercase status label: "ΒΗΜΑ 3 ΑΠΟ 7" or "2 ΑΠΟ 5" on coach cards. */
    val stepLabel: String = "",
    val page: String = "",
    val title: String = "",
    /** One short line under the instruction (two on coach cards and steps without a phase). */
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
    /** Mic button label ("Μίλα" / "Speak"); null = no mic in the toolbar. */
    val micLabel: String? = null,
    /** The step's guide drawing as white line art; shown on the side card while placing ([SidePanel]); null = none. */
    val drawing: ImageBitmap? = null,
    /** Coach cards use the compact window, 0.40 × 0.20 m (§2.4). */
    val compact: Boolean = false,
    /** Status pill (§2.5): tracking lost, offline; at the right of the window's status line. Null = hidden. */
    val pill: String? = null,
    val pillKind: StatusKind = StatusKind.UNSURE,
    // ACTION-COACH: the two-dot phase indicator above the title ("1 Πάρε · 2 Βάλε"). 0 = hidden, 1 = take, 2 = put.
    val phase: Int = 0,
    val phaseTake: String = "",
    val phasePut: String = "",
    /** "1 από 2" for repeated hardware, at the right of the dots; "" = none. */
    val phaseCount: String = "",
)

class StepPanelModel {
  var state by mutableStateOf(StepPanelState())
  var onBack: () -> Unit = {}
  var onNext: () -> Unit = {}
  var onWhere: () -> Unit = {}
  var onRepeat: () -> Unit = {}
  /** Called at pinch-down on any button (the tick sound, §5.2). */
  var onPress: () -> Unit = {}
  /** Push-to-talk: pinch-down / release on the mic button. */
  var onMicDown: () -> Unit = {}
  var onMicUp: () -> Unit = {}
  var mic by mutableStateOf(MicState.IDLE)
  /** The no-marker instruction ("Place … inside the blue outline."), first line of the body while it applies; "" = none. */
  var coach by mutableStateOf("")
  /** "Re-align" / «Ευθυγράμμιση»: a quiet item of the secondary row while the ghost is only placed; null = hidden. */
  var align by mutableStateOf<String?>(null)
  var onAlign: () -> Unit = {}
  /** Fade-relocate (§2.6 rule 5): 0..1, written by the frame tick. */
  var alpha by mutableStateOf(1f)
}

class LabelModel {
  var info by mutableStateOf<LabelInfo?>(null)
  var lang by mutableStateOf("en")
}

/** Superellipse-ish continuous corners are approximated by Compose's rounded rect (UNVERIFIED look on device). */
private fun glassShape(r: Dp): Shape = RoundedCornerShape(r)

/**
 * Glass recipe (§3.1): vertical tint, top-left sheen, rim brighter at the top, inner shadow at the bottom.
 * UI-FLOOR [scrim]: for glass that carries text over the work itself (the step window beyond the parts, the pills on
 * parts, the badge at the hand): `--ag-scrim` under the content takes the glass from α 0.90 to 0.96, so white text
 * keeps over 10:1 on a sunlit white board (it was 6.6:1 there, under the 7:1 the spec asks for body text).
 */
internal fun Modifier.glass(radius: Dp, scrim: Boolean = false): Modifier =
    this.clip(glassShape(radius))
        .background(Brush.verticalGradient(listOf(AgColors.glassTop, AgColors.glassBottom)))
        .then(if (scrim) Modifier.background(AgColors.scrim) else Modifier)
        .drawWithContent {
          drawContent()
          // edge band: the outer 16 mm let passthrough through (α 0.90 → 0.80), drawn as a darkening-free fade
          val band = 16.dp.toPx()
          drawRect(
              Brush.horizontalGradient(
                  0f to Color(0x1A000000),
                  band / size.width to Color.Transparent,
                  1f - band / size.width to Color.Transparent,
                  1f to Color(0x1A000000),
              ),
              blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
          )
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
        // ✕
        val k = w * 0.30f
        val sw = w * 0.13f
        drawLine(AgColors.glyph, Offset(k, k), Offset(w - k, w - k), sw)
        drawLine(AgColors.glyph, Offset(w - k, k), Offset(k, w - k), sw)
      }
      StatusKind.UNSURE -> { // diamond with "?"
        val p = Path().apply {
          moveTo(w / 2, 0f); lineTo(w, w / 2); lineTo(w / 2, w); lineTo(0f, w / 2); close()
        }
        drawPath(p, c)
        val sw = w * 0.11f
        drawArc(AgColors.glyph, 200f, 250f, false, Offset(w * 0.37f, w * 0.24f), androidx.compose.ui.geometry.Size(w * 0.26f, w * 0.24f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawLine(AgColors.glyph, Offset(w / 2, w * 0.48f), Offset(w / 2, w * 0.60f), sw)
        drawCircle(AgColors.glyph, sw * 0.6f, Offset(w / 2, w * 0.72f))
      }
      StatusKind.SUCCESS -> { // circle with ✓
        drawCircle(c)
        val sw = w * 0.13f
        drawLine(AgColors.glyph, Offset(w * 0.28f, w * 0.52f), Offset(w * 0.44f, w * 0.68f), sw)
        drawLine(AgColors.glyph, Offset(w * 0.44f, w * 0.68f), Offset(w * 0.74f, w * 0.36f), sw)
      }
      else -> drawCircle(c)
    }
  }
}

// ---- UI-FLOOR: the step window: one instruction, one primary button, the rest quiet ----

private enum class Glyph { BACK, REPEAT, MIC, ALIGN, CHECK, WHERE }

/** Line icons of the quiet row, drawn (no icon font): 15 mm, stroke 1.6 mm, round caps. */
@Composable
private fun GlyphIcon(g: Glyph, color: Color, sizeDp: Dp = 15.dp) {
  Canvas(Modifier.size(sizeDp)) {
    val w = size.width
    val sw = w * 0.11f
    val round = androidx.compose.ui.graphics.StrokeCap.Round
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = round)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(color, Offset(w * x0, w * y0), Offset(w * x1, w * y1), sw, round)
    when (g) {
      Glyph.BACK -> { // ‹
        line(0.62f, 0.16f, 0.30f, 0.50f)
        line(0.30f, 0.50f, 0.62f, 0.84f)
      }
      Glyph.REPEAT -> { // an open circle that ends in an arrowhead
        val r = w * 0.36f
        drawArc(color, 40f, 280f, false, Offset(w / 2 - r, w / 2 - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = stroke)
        val a = Math.toRadians(320.0)
        val ex = w / 2 + r * Math.cos(a).toFloat()
        val ey = w / 2 + r * Math.sin(a).toFloat()
        for (d in listOf(-0.75, 0.75)) {
          val back = a + 1.5 * Math.PI + d
          drawLine(color, Offset(ex, ey), Offset(ex + w * 0.26f * Math.cos(back).toFloat(), ey + w * 0.26f * Math.sin(back).toFloat()), sw, round)
        }
      }
      Glyph.MIC -> { // capsule + stand
        drawRoundRect(color, Offset(w * 0.34f, 0f), androidx.compose.ui.geometry.Size(w * 0.32f, w * 0.62f), androidx.compose.ui.geometry.CornerRadius(w * 0.16f))
        drawArc(color, 0f, 180f, false, Offset(w * 0.2f, w * 0.3f), androidx.compose.ui.geometry.Size(w * 0.6f, w * 0.5f), style = stroke)
        line(0.5f, 0.8f, 0.5f, 1f)
      }
      Glyph.ALIGN -> { // crosshair
        drawCircle(color, w * 0.27f, style = stroke)
        line(0.5f, 0.02f, 0.5f, 0.23f)
        line(0.5f, 0.77f, 0.5f, 0.98f)
        line(0.02f, 0.5f, 0.23f, 0.5f)
        line(0.77f, 0.5f, 0.98f, 0.5f)
      }
      Glyph.CHECK -> { // ✓
        line(0.14f, 0.54f, 0.40f, 0.80f)
        line(0.40f, 0.80f, 0.88f, 0.24f)
      }
      Glyph.WHERE -> { // a ring with its centre marked
        drawCircle(color, w * 0.40f, style = stroke)
        drawCircle(color, w * 0.12f)
      }
    }
  }
}

/**
 * A quiet secondary action: icon + short label, no fill until the ray is on it. 36 mm tall (2.6° at 0.8 m, over the
 * 2.5° minimum target), items 12 mm apart (§1 [M-hands]). [hold] = push-to-talk: down at pinch, up at release.
 */
@Composable
private fun QuietButton(
    label: String,
    glyph: Glyph,
    onPress: () -> Unit,
    onClick: () -> Unit,
    mic: MicState? = null,
    onDown: () -> Unit = {},
    onUp: () -> Unit = {},
) {
  val src = androidx.compose.runtime.remember { MutableInteractionSource() }
  val hovered by src.collectIsHoveredAsState()
  val pressed by src.collectIsPressedAsState()
  val scale by androidx.compose.animation.core.animateFloatAsState(
      if (pressed) 0.97f else if (hovered) 1.03f else 1f,
      androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 630f),
      label = "quiet",
  )
  androidx.compose.runtime.LaunchedEffect(pressed) {
    if (pressed) {
      onPress()
      onDown()
    } else onUp()
  }
  val fill =
      when {
        mic == MicState.LISTENING -> Color(0x4DFF8A7A) // error tint 30 %: recording
        mic == MicState.THINKING -> Color(0x33E8C14B)
        pressed -> Color(0x3DFFFFFF)
        hovered -> Color(0x2EFFFFFF)
        else -> Color.Transparent
      }
  val lit = hovered || pressed || (mic != null && mic != MicState.IDLE)
  val ink = if (mic == MicState.LISTENING) AgColors.error else if (lit) AgColors.textStrong else AgColors.text
  Row(
      Modifier.scale(scale)
          .height(36.dp)
          .clip(RoundedCornerShape(18.dp))
          .background(fill)
          .border(1.dp, if (lit) Color(0x59FFFFFF) else Color.Transparent, RoundedCornerShape(18.dp))
          .hoverable(src)
          .clickable(interactionSource = src, indication = null, onClick = onClick)
          .padding(horizontal = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    GlyphIcon(glyph, ink)
    Text(label, color = ink, fontSize = TextSize.caption, fontWeight = FontWeight.Medium, maxLines = 1)
  }
}

/** The one primary action of the phase ("Το πήρα", "Επόμενο", "Τέλος"): 60 mm tall (4.3°), the only filled control. */
@Composable
private fun PrimaryButton(label: String, onPress: () -> Unit, onClick: () -> Unit) {
  val src = androidx.compose.runtime.remember { MutableInteractionSource() }
  val hovered by src.collectIsHoveredAsState()
  val pressed by src.collectIsPressedAsState()
  val scale by androidx.compose.animation.core.animateFloatAsState(
      if (pressed) 0.97f else if (hovered) 1.03f else 1f,
      androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 630f), // response ≈ 0.25 s
      label = "primary",
  )
  androidx.compose.runtime.LaunchedEffect(pressed) { if (pressed) onPress() }
  Box(
      Modifier.scale(scale)
          .height(60.dp)
          .widthIn(min = 128.dp)
          .clip(RoundedCornerShape(30.dp))
          .background(if (pressed) Color(0xFF3F6A90) else if (hovered) Color(0xFF305A80) else AgColors.accentDeep)
          .border(1.5.dp, AgColors.accent.copy(alpha = if (hovered) 0.9f else 0.55f), RoundedCornerShape(30.dp))
          .hoverable(src)
          .clickable(interactionSource = src, indication = null, onClick = onClick)
          .padding(horizontal = 22.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(label, color = AgColors.textStrong, fontSize = TextSize.primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/** A plain capsule button (the coach cards' "Ξανά"), 44 mm tall. */
@Composable
private fun AgButton(label: String, onPress: () -> Unit, onClick: () -> Unit) {
  val src = androidx.compose.runtime.remember { MutableInteractionSource() }
  val hovered by src.collectIsHoveredAsState()
  val pressed by src.collectIsPressedAsState()
  val scale by androidx.compose.animation.core.animateFloatAsState(
      if (pressed) 0.97f else if (hovered) 1.03f else 1f,
      androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 630f),
      label = "press",
  )
  androidx.compose.runtime.LaunchedEffect(pressed) { if (pressed) onPress() }
  Box(
      Modifier.scale(scale)
          .height(44.dp)
          .widthIn(min = 88.dp)
          .clip(RoundedCornerShape(22.dp))
          .background(if (pressed) Color(0x3DFFFFFF) else if (hovered) Color(0x2EFFFFFF) else AgColors.button)
          .border(1.dp, if (hovered) Color(0x59FFFFFF) else AgColors.border, RoundedCornerShape(22.dp))
          .hoverable(src)
          .clickable(interactionSource = src, indication = null, onClick = onClick)
          .padding(horizontal = 16.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(label, color = AgColors.textStrong, fontSize = TextSize.button, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/**
 * ACTION-COACH: "1 Πάρε · 2 Βάλε", small, in the status line. The phase shown now is a filled azure dot with its
 * word in full white; the other is a hollow dot with a dimmed word.
 */
@Composable
private fun PhaseDots(s: StepPanelState) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    for ((n, word) in listOf(1 to s.phaseTake, 2 to s.phasePut)) {
      val on = s.phase == n
      Box(
          Modifier.size(20.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(if (on) AgColors.accent else Color.Transparent)
              .border(1.5.dp, if (on) AgColors.accent else AgColors.border, RoundedCornerShape(10.dp)),
          contentAlignment = Alignment.Center,
      ) {
        Text("$n", color = if (on) AgColors.glyph else AgColors.textMeta, fontSize = TextSize.caption, lineHeight = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      }
      Text(word, color = if (on) AgColors.textStrong else AgColors.textMeta, fontSize = TextSize.caption,
          fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.4.sp, maxLines = 1)
      if (n == 1) Text("·", color = AgColors.textMeta, fontSize = TextSize.caption, maxLines = 1)
    }
  }
}

/**
 * The small line at the top of the window: status shape + "ΒΗΜΑ 3 ΑΠΟ 7" (or the verdict word), the two phase dots,
 * and at the right the status pill (tracking lost, offline) or the count of repeated hardware ("1 από 2").
 */
@Composable
private fun StatusLine(s: StepPanelState) {
  Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    // Status: colour + shape + word (an accent dot when there is nothing to report).
    StatusShape(if (s.status == StatusKind.NONE) StatusKind.INFO else s.status, 12.dp)
    val head = if (s.status != StatusKind.NONE && s.statusText.isNotEmpty()) s.statusText else s.stepLabel
    Text(
        if (s.page.isNotEmpty() && s.phase == 0) "$head · ${s.page}" else head,
        Modifier.weight(1f, fill = false),
        color = when (s.status) {
          StatusKind.ERROR -> AgColors.error
          StatusKind.SUCCESS -> AgColors.success
          StatusKind.UNSURE -> AgColors.unsure
          else -> AgColors.textMeta
        },
        fontSize = TextSize.caption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.7.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    val pill = s.pill
    if (pill == null && s.phase > 0 && !s.compact) PhaseDots(s) // ACTION-COACH
    Spacer(Modifier.weight(1f))
    if (pill != null) {
      Row(
          Modifier.clip(RoundedCornerShape(11.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 10.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        StatusShape(s.pillKind, 12.dp)
        Text(pill, color = AgColors.textStrong, fontSize = TextSize.caption, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    } else if (s.phaseCount.isNotEmpty()) {
      Box(Modifier.clip(RoundedCornerShape(11.dp)).background(AgColors.accentDeep).padding(horizontal = 10.dp, vertical = 2.dp)) {
        Text(s.phaseCount, color = AgColors.textStrong, fontSize = TextSize.caption, fontWeight = FontWeight.Medium, maxLines = 1)
      }
    }
  }
}

/**
 * Panel size for [StepPanel] (UI-FLOOR): the window is the whole panel, 0.54 × 0.22 m (37.3° × 15.7° at 0.8 m). Low
 * and wide, so it fits just beyond the work without covering it ([WindowFollow]); the status pill and every button
 * are inside it, 20 mm from its edge.
 */
const val STEP_PANEL_W = 0.54f
const val STEP_PANEL_H = 0.22f

/**
 * The step window (UI-FLOOR): one clear thing at a time, for a glance from above the work.
 *  - a small status line: step, the two phase dots, the status pill;
 *  - the HERO: the instruction of the phase ("Πάρε: Αριστερό πλαϊνό" / "Βάλ' το εδώ"), 42 sp (1.5 × the title, 3.0°
 *    at 0.8 m), at most two lines, with one short line under it;
 *  - ONE primary button for the phase at its right ("Το πήρα" / "Επόμενο" / "Τέλος");
 *  - a quiet row of icon + word for everything else: Back, Repeat, the mic, Re-align, Check ([side]'s "Έλεγχος").
 * Coach cards use the compact size and keep two body lines.
 */
@Composable
fun StepPanel(model: StepPanelModel, side: SidePanelModel? = null) {
  val s = model.state
  val w = if (s.compact) 400.dp else 540.dp
  val h = if (s.compact) 200.dp else 220.dp
  Box(Modifier.fillMaxSize().alpha(model.alpha), contentAlignment = Alignment.Center) {
    Column(Modifier.width(w).height(h).glass(24.dp, scrim = true).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp)) {
      StatusLine(s)
      Row(
          Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          // a name too long for two lines at the hero size steps down once rather than being cut
          val hero = !s.compact && s.title.length <= 32
          Text(
              s.title,
              color = AgColors.textStrong,
              fontSize = if (hero) TextSize.hero else if (s.compact) TextSize.title else 34.sp,
              lineHeight = if (hero) TextSize.heroLine else if (s.compact) TextSize.titleLine else 40.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
          )
          // The no-marker instruction, in the outline's own azure, in the body's place.
          // ACTION-COACH: it says where the part goes, so it waits while "take" is the instruction (phase 1)
          val coach = model.coach.takeIf { it.isNotEmpty() && !s.compact && s.phase != 1 }
          if (coach != null)
              Text(
                  coach,
                  color = AgColors.accent,
                  fontSize = TextSize.body,
                  lineHeight = TextSize.bodyLine,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = if (s.title.length <= 16) 2 else 1, // under a two-line instruction there is room for one
                  overflow = TextOverflow.Ellipsis,
              )
          val body = s.body.ifEmpty { if (s.phase == 0) s.hardware else "" }
          if (body.isNotEmpty() && (coach == null || s.title.length <= 16))
              Text(
                  body,
                  color = AgColors.text,
                  fontSize = TextSize.body,
                  lineHeight = TextSize.bodyLine,
                  fontWeight = FontWeight.Medium,
                  maxLines = if (s.compact || (s.phase == 0 && coach == null)) 2 else 1,
                  overflow = TextOverflow.Ellipsis,
              )
        }
        if (s.compact) s.whereLabel?.let { AgButton(it, model.onPress) { model.onWhere() } }
        else s.nextLabel?.let { PrimaryButton(it, model.onPress) { model.onNext() } }
      }
      // Everything else, quiet: icon + word, no fill until the ray is on it. "Where am I" only when there is no mic.
      val check = side?.checkLabel
      val any = s.backLabel != null || s.repeatLabel != null || s.micLabel != null || s.whereLabel != null || model.align != null || check != null
      if (!s.compact && any) {
        Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
          s.backLabel?.let { QuietButton(it, Glyph.BACK, model.onPress, { model.onBack() }) }
          s.repeatLabel?.let { QuietButton(it, Glyph.REPEAT, model.onPress, { model.onRepeat() }) }
          s.micLabel?.let { QuietButton(it, Glyph.MIC, model.onPress, {}, mic = model.mic, onDown = model.onMicDown, onUp = model.onMicUp) }
          s.whereLabel?.takeIf { s.micLabel == null }?.let { QuietButton(it, Glyph.WHERE, model.onPress, { model.onWhere() }) }
          model.align?.let { QuietButton(it, Glyph.ALIGN, model.onPress, { model.onAlign() }) }
          if (side != null && check != null) QuietButton(check, Glyph.CHECK, side.onPress, { side.onCheck() })
        }
      }
    }
    // Progress of the steps: a hairline inside the window's top edge, inset by the corner radius (§2.4).
    s.progress?.takeIf { !s.compact }?.let { p ->
      Box(Modifier.align(Alignment.TopCenter).padding(top = 5.dp).width(w - 48.dp).height(3.dp).clip(RoundedCornerShape(1.5.dp)).background(Color(0x1FFFFFFF))) {
        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(3.dp).background(AgColors.accent))
      }
    }
  }
}

/** Pill height 28 mm (2.0°) in a 0.32 × 0.032 m panel (the pill is centred, the rest transparent). */
const val LABEL_PANEL_W = 0.32f
const val LABEL_PANEL_H = 0.032f

/** Part label pill (§4.5): name · code, count chip, status shape when known. Billboarded by the renderer. */
@Composable
fun LabelPill(model: LabelModel) {
  val info = model.info ?: return
  val lang = model.lang
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    Row(
        Modifier.height(28.dp).wrapContentSize().glass(14.dp, scrim = true).padding(horizontal = 11.dp),
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
      if (kind != StatusKind.NONE) StatusShape(kind, 12.dp)
      Text(info.label.name, color = AgColors.textStrong, fontSize = TextSize.labelName, fontWeight = FontWeight.SemiBold, maxLines = 1)
      info.label.code?.let { Text("· $it", color = AgColors.textMeta, fontSize = TextSize.caption, fontWeight = FontWeight.Medium, maxLines = 1) }
      info.label.count?.let {
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(AgColors.accentDeep).padding(horizontal = 8.dp, vertical = 2.dp)) {
          Text(it, color = AgColors.textStrong, fontSize = TextSize.caption, fontWeight = FontWeight.Medium, maxLines = 1)
        }
      }
      val word =
          when {
            info.lost -> Strings.t(lang, "unsure")
            kind == StatusKind.SUCCESS -> Strings.t(lang, "correct")
            kind == StatusKind.ERROR -> Strings.t(lang, "wrong")
            kind == StatusKind.UNSURE -> Strings.t(lang, "unsure")
            else -> null
          }
      if (word != null) Text(word, color = AgColors.textStrong, fontSize = TextSize.caption, fontWeight = FontWeight.SemiBold)
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
