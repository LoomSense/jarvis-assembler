package com.agj.android.guide

import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.core.blenderToGltf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * The guide data model: `GET /files/{job}/manifest.json` (contract: docs/PLAN.md §4, docs/HANDOFF.md §2.1) plus what
 * the GLB adds (each node's final transform, sampled at the end of the `walkthrough` clip).
 *
 * Units: metres. Manifest vectors (`target_m`, `from_dir`, `assemblyRotationDeg`) are in Blender's Z-up frame; use the
 * `*Gltf` accessors, which convert to the ASSEMBLY frame (glTF Y-up, see core/Geom.kt).
 */

/** Lenient parser for server JSON: unknown keys ignored, so the server can add fields without breaking the app. */
val GuideJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

@Serializable
data class LocalizedText(val en: String = "", val el: String = "") {
  fun get(lang: String) = if (lang == "el" && el.isNotEmpty()) el else en
}

@Serializable data class HardwareQty(val code: String, val qty: Int = 1)

@Serializable data class Cite(val page: Int? = null, val label: String? = null)

/** One instruction: `type` ∈ place, fasten, turn, press, tighten. */
@Serializable
data class ManifestAction(
    val type: String,
    val text: LocalizedText = LocalizedText(),
    val node: String? = null,
    val partId: String? = null,
    val code: String? = null,
    /** Blender Z-up, metres, ASSEMBLY origin. */
    val target_m: List<Double>? = null,
    /** Blender Z-up unit vector from the target towards where the part comes from. */
    val from_dir: List<Double>? = null,
    val rotation_deg: List<Double>? = null,
    /** Seconds in the step clip. */
    val t0_s: Double? = null,
    val t1_s: Double? = null,
) {
  val targetGltf: Vec3?
    get() = target_m?.let(::blenderToGltf)

  val fromDirGltf: Vec3?
    get() = from_dir?.let(::blenderToGltf)
}

@Serializable
data class ManifestStep(
    val n: Int,
    val title: String = "",
    val clip: String? = null,
    val pose: String? = null,
    /** Blender Z-up Euler degrees: how the whole assembly is turned during this step. */
    val assemblyRotationDeg: List<Double> = listOf(0.0, 0.0, 0.0),
    val hardware: List<HardwareQty> = emptyList(),
    val cite: Cite = Cite(),
    val crop: String? = null,
    val visible: List<String> = emptyList(),
    val moving: List<String> = emptyList(),
    val actions: List<ManifestAction> = emptyList(),
)

@Serializable data class ManifestPart(val id: String, val short: String = "", val name: String = "")

@Serializable
data class ManifestHardware(val code: String, val short: String = "", val color: List<Double> = emptyList())

@Serializable data class OverallMm(val L: Double = 0.0, val W: Double = 0.0, val H: Double = 0.0)

@Serializable
data class Manifest(
    val title: String = "",
    val guideId: String? = null,
    val glb: String = "walkthrough.glb",
    val glbPacked: String? = null,
    val rootNode: String = "assembly",
    val markerNode: String? = null,
    val overall_mm: OverallMm = OverallMm(),
    /** `{clip, duration_s, steps:[{n, start_s, end_s}]}`; `n` can be the string "finish". Kept raw. */
    val walkthrough: JsonElement? = null,
    val parts: List<ManifestPart> = emptyList(),
    val hardware: List<ManifestHardware> = emptyList(),
    val steps: List<ManifestStep> = emptyList(),
) {
  val actionCount: Int
    get() = steps.sumOf { it.actions.size }

  fun step(n: Int): ManifestStep? = steps.firstOrNull { it.n == n }

  companion object {
    fun parse(json: String): Manifest = GuideJson.decodeFromString(serializer(), json)
  }
}

/**
 * A loaded guide: manifest + GLB file + each node's final local transform under the root node (ASSEMBLY frame).
 *
 * @property finalPoses node name → transform at the last frame of the `walkthrough` clip. Parts rest hidden at scale
 *   1e-4 in the GLB; this map is where they end up. Empty until the GLB is parsed (see [JobRepository]).
 */
data class Assembly(
    val jobId: String,
    val manifest: Manifest,
    val glbPath: String?,
    val finalPoses: Map<String, Pose3> = emptyMap(),
) {
  val stepNumbers: List<Int>
    get() = manifest.steps.map { it.n }

  /** Front-bottom corners for the two-corner touch, ASSEMBLY frame, from `overall_mm` (UNVERIFIED: origin centred). */
  fun frontCorners(): Pair<Vec3, Vec3> {
    val l = (manifest.overall_mm.L / 1000.0).toFloat()
    val w = (manifest.overall_mm.W / 1000.0).toFloat()
    return Vec3(-l / 2, 0f, w / 2) to Vec3(l / 2, 0f, w / 2)
  }
}
