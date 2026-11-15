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
 * Text (spec §2.2, em sizes at 0.8 m): caption / uppercase 0.85° = 11.9 mm → 12 sp; body 1.25° = 17.5 mm → 17.5 sp;
 * title 2.0° = 27.9 mm → 28 sp; label name 1.2° = 16.8 mm → 17 sp. Line height 1.2 titles, 1.36 body.
 */
object TextSize {
  val caption = 12.sp
  val body = 17.5.sp
  val bodyLine = 24.sp
  val title = 28.sp
  val titleLine = 34.sp
  val labelName = 17.sp
  val button = 17.sp
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
    /** Mic button label ("Μίλα" / "Speak"); null = no mic in the toolbar. */
    val micLabel: String? = null,
    /** The step's guide drawing as white line art (right column, §2.4); null = text only. */
    val drawing: ImageBitmap? = null,
    /** Coach cards use the compact window, 0.36 × 0.20 m (§2.4). */
    val compact: Boolean = false,
    /** Status pill above the window (§2.5): tracking lost, offline. Null = hidden. */
    val pill: String? = null,
    val pillKind: StatusKind = StatusKind.UNSURE,
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
  /** "Re-align" / «Ευθυγράμμιση»: small button above the window's top right corner while the ghost is only placed; null = hidden. */
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

/** Glass recipe (§3.1): vertical tint, top-left sheen, rim brighter at the top, inner shadow at the bottom. */
internal fun Modifier.glass(radius: Dp): Modifier =
    this.clip(glassShape(radius))
        .background(Brush.verticalGradient(listOf(AgColors.glassTop, AgColors.glassBottom)))
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

@Composable
private fun MicButton(label: String, mic: MicState, onPress: () -> Unit, onDown: () -> Unit, onUp: () -> Unit) {
  val src = androidx.compose.runtime.remember { MutableInteractionSource() }
  val hovered by src.collectIsHoveredAsState()
  val pressed by src.collectIsPressedAsState()
  val scale by androidx.compose.animation.core.animateFloatAsState(
      if (pressed) 0.97f else if (hovered) 1.03f else 1f,
      androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 630f),
      label = "mic",
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
        else -> AgColors.button
      }
  Row(
      Modifier.scale(scale)
          .height(44.dp)
          .width(88.dp)
          .clip(RoundedCornerShape(22.dp))
          .background(fill)
          .border(1.dp, if (hovered || mic != MicState.IDLE) Color(0x59FFFFFF) else AgColors.border, RoundedCornerShape(22.dp))
          .hoverable(src)
          .clickable(interactionSource = src, indication = null, onClick = {}),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // mic glyph: capsule + stand; red dot while recording
    Canvas(Modifier.size(16.dp)) {
      val w = size.width
      val col = if (mic == MicState.LISTENING) AgColors.error else AgColors.textStrong
      drawRoundRect(col, Offset(w * 0.34f, 0f), androidx.compose.ui.geometry.Size(w * 0.32f, w * 0.62f), androidx.compose.ui.geometry.CornerRadius(w * 0.16f))
      drawArc(col, 0f, 180f, false, Offset(w * 0.2f, w * 0.3f), androidx.compose.ui.geometry.Size(w * 0.6f, w * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.09f))
      drawLine(col, Offset(w / 2, w * 0.8f), Offset(w / 2, w), w * 0.09f)
    }
    Spacer(Modifier.width(6.dp))
    Text(label, color = AgColors.textStrong, fontSize = TextSize.button, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

@Composable
private fun AgButton(label: String, primary: Boolean, onPress: () -> Unit, onClick: () -> Unit) {
  val src = androidx.compose.runtime.remember { MutableInteractionSource() }
  val hovered by src.collectIsHoveredAsState()
  val pressed by src.collectIsPressedAsState()
  val scale by androidx.compose.animation.core.animateFloatAsState(
      if (pressed) 0.97f else if (hovered) 1.03f else 1f,
      androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 630f), // response ≈ 0.25 s
      label = "press",
  )
  androidx.compose.runtime.LaunchedEffect(pressed) { if (pressed) onPress() }
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
    Text(label, color = AgColors.textStrong, fontSize = TextSize.button, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/** Small secondary action, 38 mm tall (2.7° at 0.8 m, over the 2.5° minimum target); feedback as [AgButton]. */
@Composable
private fun SmallButton(label: String, onPress: () -> Unit, onClick: () -> Unit) {
  val src = androidx.compose.runtime.remember { MutableInteractionSource() }
  val hovered by src.collectIsHoveredAsState()
  val pressed by src.collectIsPressedAsState()
  androidx.compose.runtime.LaunchedEffect(pressed) { if (pressed) onPress() }
  Box(
      Modifier.height(38.dp)
          .clip(RoundedCornerShape(19.dp))
          .background(if (pressed) Color(0x3DFFFFFF) else if (hovered) Color(0x2EFFFFFF) else AgColors.button)
          .border(1.dp, if (hovered) Color(0x59FFFFFF) else AgColors.border, RoundedCornerShape(19.dp))
          .hoverable(src)
          .clickable(interactionSource = src, indication = null, onClick = onClick)
          .padding(horizontal = 14.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(label, color = AgColors.textStrong, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/**
 * Panel size for [StepPanel]: status pill row (40 mm) + window 0.52 × 0.34 m + the toolbar ornament below
 * (56 mm, overlapping the window's bottom edge by half).
 */
const val STEP_PANEL_W = 0.54f
const val STEP_PANEL_H = 0.42f

/** The step window (§2.4) with its toolbar ornament and status pill (§2.5). Coach cards use the compact size. */
@Composable
fun StepPanel(model: StepPanelModel) {
  val s = model.state
  val w = if (s.compact) 360.dp else 520.dp
  val h = if (s.compact) 200.dp else 340.dp
  Box(Modifier.fillMaxSize().alpha(model.alpha)) {
    s.pill?.let { text ->
      Row(
          Modifier.align(Alignment.TopCenter).wrapContentSize().glass(18.dp).padding(horizontal = 14.dp, vertical = 7.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        StatusShape(s.pillKind, 12.dp)
        Text(text, color = AgColors.textStrong, fontSize = TextSize.caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
      }
    }
    // Re-align: a small glass capsule in the pill row, flush with the window's right edge (not a fifth toolbar item).
    model.align?.takeIf { !s.compact }?.let { label ->
      Box(Modifier.align(Alignment.TopEnd).padding(end = (540.dp - w) / 2).glass(21.dp).padding(2.dp)) {
        SmallButton(label, model.onPress) { model.onAlign() }
      }
    }
    Column(
        Modifier.align(Alignment.TopCenter).padding(top = 44.dp).width(w).height(h).glass(24.dp).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      s.progress?.let { p ->
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x1FFFFFFF))) {
          Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(4.dp).background(AgColors.accent))
        }
      }
      Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Column(Modifier.weight(0.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Status: colour + shape + word (an accent dot when there is nothing to report).
        StatusShape(if (s.status == StatusKind.NONE) StatusKind.INFO else s.status, 12.dp)
        val head = if (s.status != StatusKind.NONE && s.statusText.isNotEmpty()) s.statusText else s.stepLabel
        Text(
            if (s.page.isNotEmpty()) "$head · ${s.page}" else head,
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
        )
      }
      Text(
          s.title,
          color = AgColors.textStrong,
          fontSize = TextSize.title,
          lineHeight = TextSize.titleLine,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
      // The no-marker instruction: first in the body area, whole (up to three lines), in the outline's own azure.
      if (model.coach.isNotEmpty() && !s.compact)
          Text(
              model.coach,
              color = AgColors.accent,
              fontSize = TextSize.body,
              lineHeight = TextSize.bodyLine,
              fontWeight = FontWeight.SemiBold,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
          )
      if (s.body.isNotEmpty())
          Text(
              s.body,
              color = AgColors.text,
              fontSize = TextSize.body,
              lineHeight = TextSize.bodyLine,
              fontWeight = FontWeight.Medium,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
          )
      if (s.hardware.isNotEmpty())
          Box(Modifier.clip(RoundedCornerShape(15.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(s.hardware, color = AgColors.text, fontSize = TextSize.caption, fontWeight = FontWeight.Medium, maxLines = 1)
          }
      }
      // Right column (40 %): the guide drawing as white line art on the glass, radius 14 mm (§2.4).
      s.drawing?.let { img ->
        Box(Modifier.weight(0.4f).fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color(0x0FFFFFFF)).padding(6.dp)) {
          androidx.compose.foundation.Image(img, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        }
      }
      }
    }
    // Toolbar ornament: glass capsule centred under the window, overlapping its bottom edge by half its height.
    // Four items at most (§2.5): Back, Repeat, mic, Next. "Where am I" only when there is no mic (coach: Retry).
    val buttons = listOfNotNull(
        s.backLabel?.let { Triple(it, false, model.onBack) },
        s.whereLabel?.takeIf { s.micLabel == null }?.let { Triple(it, false, model.onWhere) },
        s.repeatLabel?.let { Triple(it, false, model.onRepeat) },
        s.micLabel?.let { Triple(it, false, {}) },
        s.nextLabel?.let { Triple(it, true, model.onNext) },
    )
    if (buttons.isNotEmpty()) {
      Row(
          Modifier.align(Alignment.TopCenter)
              .padding(top = 44.dp + h - 28.dp)
              .wrapContentSize()
              .glass(28.dp)
              .padding(horizontal = 6.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        for ((label, primary, cb) in buttons) {
          if (label == s.micLabel) MicButton(label, model.mic, model.onPress, model.onMicDown, model.onMicUp)
          else AgButton(label, primary, model.onPress) { cb() }
        }
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
        Modifier.height(28.dp).wrapContentSize().glass(14.dp).padding(horizontal = 11.dp),
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
