package com.agj.android.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the held-part badge says: shape + colour + word (never colour alone, §4.10), and the part it is about. */
data class VerdictBadgeState(val kind: StatusKind, val title: String, val detail: String? = null)

class VerdictBadgeModel {
  var state by mutableStateOf<VerdictBadgeState?>(null)
  var alpha by mutableStateOf(1f)
}

/** Panel for [VerdictBadge]: the badge is centred in it, the rest is transparent. */
const val VERDICT_PANEL_W = 0.38f
const val VERDICT_PANEL_H = 0.11f

fun StatusKind.color() =
    when (this) {
      StatusKind.SUCCESS -> AgColors.success
      StatusKind.ERROR -> AgColors.error
      StatusKind.UNSURE -> AgColors.unsure
      else -> AgColors.accent
    }

/**
 * The verdict on the part in the hand: ✓ "Σωστό" + the part, ✕ "Λάθος εξάρτημα" + "Χρειάζεσαι: …", ? "Δείξε μου το
 * εξάρτημα πιο κοντά" (and a dot + "Ελέγχω…" while a check is answered). One glass capsule with the status colour on
 * its rim; the title is 20 sp, the detail the step window's body size; the panel is scaled with distance so both read
 * at arm's length ([BadgePlacement.scale]). Text wraps to two lines so the badge stays narrow beside the hand.
 */
@Composable
fun VerdictBadge(model: VerdictBadgeModel) {
  val s = model.state ?: return
  val c = s.kind.color()
  Box(Modifier.fillMaxSize().alpha(model.alpha), contentAlignment = Alignment.Center) {
    Row(
        Modifier.wrapContentSize()
            .glass(26.dp, scrim = true)
            .border(2.dp, c.copy(alpha = 0.85f), RoundedCornerShape(26.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      StatusGlyph(s.kind, 34.dp)
      Column(Modifier.widthIn(max = 276.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            s.title,
            color = c,
            fontSize = 20.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        s.detail?.takeIf { it.isNotBlank() }?.let {
          Text(
              it,
              color = AgColors.textStrong,
              fontSize = TextSize.body,
              lineHeight = 22.sp,
              fontWeight = FontWeight.Medium,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}
