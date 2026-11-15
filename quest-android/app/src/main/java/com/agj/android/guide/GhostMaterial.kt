package com.agj.android.guide

import android.graphics.Color
import com.agj.android.core.Agq
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthWrite
import com.meta.spatial.runtime.MaterialSidedness
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType
import com.meta.spatial.runtime.SceneTexture

/** Which ghost material is in use (logged once as AGQ `ghost_material`). */
enum class GhostMaterialKind(val wire: String) {
  /** Our fresnel shader `app/src/shaders/ghost_fresnel.{vert,frag}`. */
  CUSTOM("custom_fresnel"),
  /** The SDK's unlit shader, translucent, a flat tint (no rim): the edges mesh carries the "brighter edges". */
  UNLIT("unlit_translucent"),
}

/**
 * One ghost material (a role: ghost, placed, mover, path, edges…) shared by every mesh in that role, so a state change
 * or the breathing is one [set] per frame. Main thread only (creates GPU resources).
 *
 * Round 1 rendered a solid ORANGE ghost on the first device run. Cause (SDK 0.15.0 classes + custom-shader docs):
 *  1. a custom material's vec4 attributes are packed into `set 3, binding 0` **in the order they are declared**, but
 *     the round-1 shader included the SDK's default MaterialUniform block (matParams, alphaParams, stereoParams,
 *     emissiveFactor, albedoFactor, …), so it read the tint from past the end of the 4-vec4 buffer;
 *  2. `SceneMaterial.custom(name, attrs)` defaults to `AlphaMode.OPAQUE`, so alpha was ignored.
 * Fix: the shaders declare their own block in [ATTRIBUTES] order, and the material is created `TRANSLUCENT`.
 * If the custom path fails at runtime (or `ghost_material=unlit` is passed), [create] falls back to the SDK's unlit
 * shader with a translucent azure fill (token #9ecfff).
 */
class GhostMat private constructor(val kind: GhostMaterialKind, val material: SceneMaterial, private val mark: Boolean = false) {
  /** Shader inputs: tint (linear RGB), body opacity at normal incidence, rim strength / exponent, overall opacity. */
  fun set(tint: FloatArray, body: Float, rim: Float, rimPower: Float = 2.5f, opacity: Float = 1f) {
    when (kind) {
      GhostMaterialKind.CUSTOM -> {
        val t = GhostMaterial.shaderTint(tint, mark)
        material.setAttribute("albedoFactor", Vector4(t[0], t[1], t[2], body))
        material.setAttribute("matParams", Vector4(rim, rimPower, opacity, 0f))
      }
      GhostMaterialKind.UNLIT -> {
        // No view-dependent rim: a little of the rim goes into the flat alpha so thin parts stay visible.
        val a = GhostMaterial.unlitAlpha(body, rim, opacity)
        material.setAlbedoColor(Color.valueOf(tint[0], tint[1], tint[2], a))
      }
    }
  }

  companion object {
    fun create(
        tint: FloatArray = GhostMaterial.Tint.ACCENT,
        body: Float = 0.14f,
        rim: Float = 0.65f,
        rimPower: Float = 2.5f,
        opacity: Float = 1f,
        /**
         * A guidance mark (hole ring, path dot, arrow, edge, leader): drawn in the token colour itself. The fresnel
         * shader draws a face seen straight on at 55 % of the tint (right for a ghost's body, too dull for a ring).
         */
        mark: Boolean = false,
    ): GhostMat {
      val m =
          if (GhostMaterial.preferred == GhostMaterialKind.CUSTOM && !GhostMaterial.customFailed) {
            runCatching { GhostMat(GhostMaterialKind.CUSTOM, GhostMaterial.customMaterial(), mark) }
                .onFailure {
                  GhostMaterial.customFailed = true
                  Agq.log.error("ghost_material", "custom shader failed, using unlit: ${it.message}")
                }
                .getOrNull()
          } else null
      val g = m ?: GhostMat(GhostMaterialKind.UNLIT, GhostMaterial.unlitMaterial())
      g.set(tint, body, rim, rimPower, opacity)
      return g
    }
  }
}

object GhostMaterial {
  const val SHADER = "ghost_fresnel"

  /** Same order as the `MaterialUniform` block in ghost_fresnel.vert / .frag (packing is by declaration order). */
  val ATTRIBUTES = listOf("albedoFactor", "matParams", "stereoParams")

  /** Launch override (`ghost_material` = `custom` | `unlit`); main thread before the job loads. */
  @Volatile var preferred = GhostMaterialKind.CUSTOM
  @Volatile internal var customFailed = false

  /** Token colours (docs/design/tokens.css) in linear RGB. */
  object Tint {
    val ACCENT = floatArrayOf(0.34f, 0.62f, 1.0f) // #9ecfff
    val SUCCESS = floatArrayOf(0.159f, 0.716f, 0.332f) // #6fdc9b
    val ERROR = floatArrayOf(1.0f, 0.254f, 0.195f) // #ff8a7a
    val UNSURE = floatArrayOf(0.807f, 0.533f, 0.07f) // #e8c14b
    val WHITE = floatArrayOf(0.86f, 0.86f, 0.86f) // never pure white (spec §1.4.13)
    /** Edges: azure lightened 8 % (spec §4.1). */
    val EDGE = floatArrayOf(0.42f, 0.69f, 1.0f)
    /**
     * "Put it here": the ghost at the target, its path, hole ring, turning arrow and the outline on the floor. Green,
     * so that it cannot be taken for the azure "take this" cues on the loose parts (asked for by the user after the
     * headset runs of 15 Nov: both were azure, and azure reads poorly on the dark blue-grey carpet).
     */
    val PLACE = floatArrayOf(0.20f, 0.85f, 0.30f)
    val PLACE_EDGE = floatArrayOf(0.36f, 0.93f, 0.46f)
  }

  /** `ghost_fresnel.frag`: colour = tint × (BODY_GAIN + 0.9 × rim). A JVM test keeps this equal to the shader. */
  const val BODY_GAIN = 0.55f

  /**
   * The tint handed to the fresnel shader. A guidance mark is pre-divided by [BODY_GAIN], so a face seen straight on
   * comes out in the token colour (the unlit fallback already draws the tint as it is). Pure, tested.
   */
  fun shaderTint(tint: FloatArray, mark: Boolean): FloatArray =
      if (mark) floatArrayOf(tint[0] / BODY_GAIN, tint[1] / BODY_GAIN, tint[2] / BODY_GAIN) else tint

  /** Flat alpha for the unlit fallback: fill + 30 % of the rim, times the overall opacity (pure, tested). */
  fun unlitAlpha(body: Float, rim: Float, opacity: Float): Float = ((body + 0.3f * rim) * opacity).coerceIn(0f, 1f)

  internal fun customMaterial(): SceneMaterial =
      SceneMaterial.custom(
              SHADER,
              ATTRIBUTES.map { SceneMaterialAttribute(it, SceneMaterialDataType.Vector4) }.toTypedArray(),
              AlphaMode.TRANSLUCENT,
          )
          .apply {
            setAttribute("stereoParams", Vector4(0f, 0f, 1f, 1f))
            common()
          }

  internal fun unlitMaterial(): SceneMaterial =
      SceneMaterial(SceneTexture(Color.valueOf(1f, 1f, 1f, 1f)), AlphaMode.TRANSLUCENT, SceneMaterial.UNLIT_SHADER).apply {
        setUnlit(true)
        common()
      }

  private fun SceneMaterial.common() {
    setBlendMode(BlendMode.TRANSLUCENT)
    setDepthWrite(DepthWrite.DISABLE)
    setSidedness(MaterialSidedness.DOUBLE_SIDED)
  }
}
