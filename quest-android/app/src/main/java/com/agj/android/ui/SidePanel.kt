package com.agj.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agj.android.core.Vec3
import com.agj.android.guide.Edges
import kotlin.math.cos
import kotlin.math.sin

/** A normalized box on the camera image (0..1, x right, y down) with a short label. */
data class PreviewBox(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val label: String = "")

/** The parts ornament (§2.5): "ΠΑΡΕ", a turntable wireframe of the next part, its name, code and count. */
data class PartCard(
    val take: String,
    val name: String,
    val code: String?,
    val count: String?,
    val wire: FloatArray,
    /** How many the step needs and how many are in view ("Χρειάζεσαι 2", "φαίνονται 1 από 2"); null = no line. */
    val note: String? = null,
)

object PartWire {
  /** Feature-edge segments → flat [x0,y0,z0,x1,y1,z1,…] centred on the part and scaled into the unit sphere. Pure. */
  fun of(segs: List<Edges.Seg>, max: Int = 400): FloatArray {
    if (segs.isEmpty()) return FloatArray(0)
    val use = if (segs.size > max) segs.sortedByDescending { it.length }.take(max) else segs
    val pts = use.flatMap { listOf(it.a, it.b) }
    val lo = Vec3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z })
    val hi = Vec3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z })
    val c = (lo + hi) * 0.5f
    val r = pts.maxOf { (it - c).length() }.coerceAtLeast(1e-6f)
    val out = FloatArray(use.size * 6)
    use.forEachIndexed { i, s ->
      val a = (s.a - c) * (1f / r)
      val b = (s.b - c) * (1f / r)
      out[i * 6] = a.x; out[i * 6 + 1] = a.y; out[i * 6 + 2] = a.z
      out[i * 6 + 3] = b.x; out[i * 6 + 4] = b.y; out[i * 6 + 5] = b.z
    }
    return out
  }
}

/** Main-thread state of the side column next to the step window (parts card + optional camera preview). */
class SidePanelModel {
  var part by mutableStateOf<PartCard?>(null)
  var showCamera by mutableStateOf(false)
  var preview by mutableStateOf<ImageBitmap?>(null)
  var caption by mutableStateOf("")
  var box by mutableStateOf<PreviewBox?>(null)
  var reducedMotion by mutableStateOf(false)
  /** Fades with the step window (fade-relocate, §2.6 rule 5). */
  var alpha by mutableStateOf(1f)
  /** The held-part verdict on the card's header, used when the badge cannot sit at the wrist; null = "ΠΑΡΕ". */
  var verdict by mutableStateOf<VerdictBadgeState?>(null)
  /** "Έλεγχος" under the part: pinch it to check the part in the hand now. Null label = no button. */
  var checkLabel by mutableStateOf<String?>(null)
  var onCheck: () -> Unit = {}
  var onPress: () -> Unit = {}
}

/** Side column: 0.15 × 0.40 m. Camera preview 12 × 9 cm on top (hud only), parts card 14 × 26 cm below. */
const val SIDE_PANEL_W = 0.15f
const val SIDE_PANEL_H = 0.40f

@Composable
fun SidePanel(model: SidePanelModel) {
  Column(
      Modifier.fillMaxSize().alpha(model.alpha),
      verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (model.showCamera) CameraPreview(model)
    model.part?.let { PartsCard(it, model) }
  }
}

@Composable
private fun CameraPreview(model: SidePanelModel) {
  Column(Modifier.width(128.dp).glass(14.dp).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.width(120.dp).height(90.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0C0E))) {
      model.preview?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
      model.box?.let { b ->
        Canvas(Modifier.fillMaxSize()) {
          val tl = Offset(b.x0 * size.width, b.y0 * size.height)
          drawRect(
              AgColors.accent,
              topLeft = tl,
              size = Size((b.x1 - b.x0) * size.width, (b.y1 - b.y0) * size.height),
              style = Stroke(2.dp.toPx()),
          )
        }
        if (b.label.isNotEmpty())
            Text(
                b.label,
                Modifier.align(Alignment.BottomStart).padding(4.dp),
                color = AgColors.textStrong,
                fontSize = 9.sp,
                maxLines = 1,
            )
      }
    }
    Text(model.caption, color = AgColors.textMeta, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
  }
}

@Composable
private fun PartsCard(p: PartCard, model: SidePanelModel) {
  val reducedMotion = model.reducedMotion
  val check = model.checkLabel
  Column(
      // header 16 + wireframe 84 + name 36 + code 16 + count 16 (+ button 34), gaps and padding: 258 / 196 mm
      Modifier.width(140.dp).height(if (check != null) 258.dp else 196.dp).glass(18.dp).padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    val v = model.verdict
    if (v == null) {
      Text(p.take, color = AgColors.textMeta, fontSize = TextSize.caption, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
    } else {
      // the verdict takes the header's place (shape + colour + word), so the card never carries two signals
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusGlyph(v.kind, 12.dp)
        Text(v.title, color = v.kind.color(), fontSize = TextSize.caption, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    val angle =
        if (reducedMotion) 0.6f
        else {
          val tr = rememberInfiniteTransition(label = "turntable")
          // 20°/s → 18 s per turn (§2.5)
          tr.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart), label = "a").value
        }
    Canvas(Modifier.fillMaxWidth().height(84.dp)) {
      val w = p.wire
      val s = minOf(size.width, size.height) * 0.46f
      val cx = size.width / 2
      val cy = size.height / 2
      val ca = cos(angle)
      val sa = sin(angle)
      val tilt = 0.35f // ≈ 20° look-down
      val ct = cos(tilt)
      val st = sin(tilt)
      fun proj(x: Float, y: Float, z: Float): Offset {
        val rx = ca * x + sa * z
        val rz = -sa * x + ca * z
        val ry = ct * y - st * rz
        return Offset(cx + rx * s, cy - ry * s)
      }
      var i = 0
      while (i + 5 < w.size) {
        drawLine(AgColors.accent, proj(w[i], w[i + 1], w[i + 2]), proj(w[i + 3], w[i + 4], w[i + 5]), 1.2.dp.toPx())
        i += 6
      }
    }
    Spacer(Modifier.size(2.dp))
    Text(p.name, color = AgColors.textStrong, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
    val meta = listOfNotNull(p.code, p.count).joinToString(" · ")
    if (meta.isNotEmpty()) Text(meta, color = AgColors.textMeta, fontSize = TextSize.caption, maxLines = 1)
    p.note?.takeIf { it.isNotBlank() }?.let {
      Text(it, color = AgColors.accent, fontSize = TextSize.caption, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (check != null) {
      Spacer(Modifier.weight(1f))
      CheckButton(check, model.onPress, model.onCheck)
    }
  }
}

/** "Έλεγχος": a capsule the width of the card; pinch = look at the part in the hand now. */
@Composable
private fun CheckButton(label: String, onPress: () -> Unit, onClick: () -> Unit) {
  val src = remember { MutableInteractionSource() }
  val hovered by src.collectIsHoveredAsState()
  val pressed by src.collectIsPressedAsState()
  LaunchedEffect(pressed) { if (pressed) onPress() }
  Box(
      Modifier.fillMaxWidth()
          .height(34.dp)
          .clip(RoundedCornerShape(17.dp))
          .background(if (pressed) Color(0x3DFFFFFF) else if (hovered) Color(0x2EFFFFFF) else AgColors.button)
          .border(1.dp, if (hovered) Color(0x59FFFFFF) else AgColors.border, RoundedCornerShape(17.dp))
          .hoverable(src)
          .clickable(interactionSource = src, indication = null, onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Text(label, color = AgColors.textStrong, fontSize = TextSize.button, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}
