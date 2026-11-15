package com.agj.android.guide

import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthWrite
import com.meta.spatial.runtime.MaterialSidedness
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType

/**
 * The ghost material: custom shader `app/src/shaders/ghost_fresnel.{vert,frag}` (compiled to SPIR-V by the Meta Spatial
 * Gradle plugin with the NDK's glslc), translucent, no depth write, double-sided (the shader uses |N·V|).
 *
 * One material instance is shared by every mesh in a role (ghost, placed, mover, path…), so a state change or the
 * breathing is one [set] call per frame. Main thread only (creates GPU resources).
 *
 * Shader inputs: `albedoFactor` = (tint linear RGB, body opacity at normal incidence); `matParams` = (rim strength,
 * rim exponent, overall opacity, unused). Spec numbers: XR_NATIVE_SPEC §4.1 (fill 0.14, rim 0.65, exponent 2.5).
 */
object GhostMaterial {
  const val SHADER = "ghost_fresnel"

  /** Token colours (docs/design/tokens.css) in linear RGB. */
  object Tint {
    val ACCENT = floatArrayOf(0.34f, 0.62f, 1.0f) // #9ecfff
    val SUCCESS = floatArrayOf(0.159f, 0.716f, 0.332f) // #6fdc9b
    val ERROR = floatArrayOf(1.0f, 0.254f, 0.195f) // #ff8a7a
    val UNSURE = floatArrayOf(0.807f, 0.533f, 0.07f) // #e8c14b
    val WHITE = floatArrayOf(0.86f, 0.86f, 0.86f) // never pure white (spec §1.4.13)
  }

  fun create(
      tint: FloatArray = Tint.ACCENT,
      body: Float = 0.14f,
      rim: Float = 0.65f,
      rimPower: Float = 2.5f,
      opacity: Float = 1f,
  ): SceneMaterial =
      SceneMaterial.custom(
              SHADER,
              arrayOf(
                  SceneMaterialAttribute("emissiveFactor", SceneMaterialDataType.Vector4),
                  SceneMaterialAttribute("albedoFactor", SceneMaterialDataType.Vector4),
                  SceneMaterialAttribute("matParams", SceneMaterialDataType.Vector4),
                  SceneMaterialAttribute("stereoParams", SceneMaterialDataType.Vector4),
              ),
          )
          .apply {
            setAttribute("emissiveFactor", Vector4(0f, 0f, 0f, 0f))
            setAttribute("stereoParams", Vector4(1f, 0f, 0f, 0f))
            set(this, tint, body, rim, rimPower, opacity)
            setBlendMode(BlendMode.TRANSLUCENT)
            setDepthWrite(DepthWrite.DISABLE)
            setSidedness(MaterialSidedness.DOUBLE_SIDED)
          }

  fun set(m: SceneMaterial, tint: FloatArray, body: Float, rim: Float, rimPower: Float = 2.5f, opacity: Float = 1f) {
    m.setAttribute("albedoFactor", Vector4(tint[0], tint[1], tint[2], body))
    m.setAttribute("matParams", Vector4(rim, rimPower, opacity, 0f))
  }
}
