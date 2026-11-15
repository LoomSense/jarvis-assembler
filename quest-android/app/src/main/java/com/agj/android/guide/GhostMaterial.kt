package com.agj.android.guide

import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthWrite
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType

/**
 * The ghost material: custom shader `app/src/shaders/ghost_fresnel.{vert,frag}` (compiled to SPIR-V by the Meta Spatial
 * Gradle plugin with the NDK's glslc), translucent, no depth write.
 *
 * Phase 1: compiled and packaged; not yet applied to GLB parts (GhostRenderer is a stub). Use as the Mesh
 * `defaultShaderOverride`, or per material on the ghost entities. Main thread only (creates GPU resources).
 *
 * @param tint linear RGB, e.g. --ag-accent #9ecfff ≈ (0.34, 0.62, 1.0) linear.
 * @param body opacity facing the viewer (0.12–0.25 reads as "ghost"), [rim] strength, [rimPower] exponent.
 */
object GhostMaterial {
  const val SHADER = "ghost_fresnel"

  fun create(
      tint: Vector4 = Vector4(0.34f, 0.62f, 1.0f, 0.18f),
      rim: Float = 0.8f,
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
            setAttribute("albedoFactor", tint)
            setAttribute("matParams", Vector4(rim, rimPower, opacity, 0f))
            setAttribute("stereoParams", Vector4(1f, 0f, 0f, 0f))
            setBlendMode(BlendMode.TRANSLUCENT)
            setDepthWrite(DepthWrite.DISABLE)
          }
}
