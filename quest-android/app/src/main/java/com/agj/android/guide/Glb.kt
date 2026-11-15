package com.agj.android.guide

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * A small glTF 2.0 binary (GLB) reader: exactly what the guide needs, nothing else.
 *
 * - node hierarchy with each node's rest TRS;
 * - mesh primitives (POSITION, NORMAL, indices) as float/int arrays, in the mesh's own frame;
 * - an animation clip's channels, sampled at any time (LINEAR = lerp / nlerp, STEP, CUBICSPLINE = value part), so the
 *   `walkthrough` clip's last keyframe gives every part's final local transform (parts rest hidden at scale 1e-4).
 *
 * Not supported (not produced by the pipeline): EXT_meshopt_compression / Draco (use `walkthrough.glb`, not the packed
 * one), sparse accessors, morph targets, skins, textures. Pure Kotlin, JVM-tested against jobs/malm-drawer.
 */

/** Translation, rotation, scale of one node (glTF order: T · R · S). */
data class Trs(val t: Vec3 = Vec3.ZERO, val r: Quat = Quat.IDENTITY, val s: Vec3 = Vec3(1f, 1f, 1f)) {
  val pose: Pose3
    get() = Pose3(t, r)

  /** Parent ← child for points: parent.apply(child.apply(p)). Scale is assumed uniform-ish (true for the pipeline). */
  fun apply(p: Vec3): Vec3 = r.rotate(Vec3(p.x * s.x, p.y * s.y, p.z * s.z)) + t

  /** this ∘ o (apply [o] first). Exact for uniform scale. */
  operator fun times(o: Trs): Trs =
      Trs(apply(o.t), (r * o.r).normalized(), Vec3(s.x * o.s.x, s.y * o.s.y, s.z * o.s.z))
}

data class GlbNode(
    val index: Int,
    val name: String,
    val children: List<Int>,
    val mesh: Int?,
    val rest: Trs,
    /** glTF `extras` raw (the pipeline puts `partId`, `step`, `instance` there). */
    val extras: JsonObject?,
)

/** One triangle list. Positions and normals are xyz triples; [indices] are 0-based vertex indices. */
class GlbPrimitive(
    val positions: FloatArray,
    val normals: FloatArray?,
    val indices: IntArray,
    val material: Int?,
) {
  val vertexCount: Int
    get() = positions.size / 3
}

class GlbMaterial(val name: String, val baseColor: FloatArray)

/** Channel `node`.`path` (translation | rotation | scale) of a clip. */
class GlbChannel(
    val node: Int,
    val path: String,
    val interpolation: String,
    val times: FloatArray,
    /** Flattened outputs, [width] floats per key (CUBICSPLINE: in-tangent, value, out-tangent per key). */
    val values: FloatArray,
    val width: Int,
) {
  val endTime: Float
    get() = times.lastOrNull() ?: 0f

  /** Value at time [t] (clamped to the keys). */
  fun sample(t: Float): FloatArray {
    val n = times.size
    if (n == 0) return FloatArray(width)
    val cubic = interpolation == "CUBICSPLINE"
    fun key(i: Int): FloatArray {
      val stride = if (cubic) width * 3 else width
      val off = i * stride + if (cubic) width else 0
      return values.copyOfRange(off, off + width)
    }
    if (t <= times[0]) return key(0)
    if (t >= times[n - 1]) return key(n - 1)
    var i = 0
    while (i < n - 2 && times[i + 1] <= t) i++
    val a = key(i)
    if (interpolation == "STEP") return a
    val b = key(i + 1)
    val k = ((t - times[i]) / (times[i + 1] - times[i])).coerceIn(0f, 1f)
    if (path == "rotation") {
      val q = Quat(a[0], a[1], a[2], a[3]).nlerp(Quat(b[0], b[1], b[2], b[3]), k)
      return floatArrayOf(q.x, q.y, q.z, q.w)
    }
    return FloatArray(width) { a[it] + (b[it] - a[it]) * k }
  }
}

class GlbAnimation(val name: String, val channels: List<GlbChannel>) {
  val duration: Float
    get() = channels.maxOfOrNull { it.endTime } ?: 0f
}

class Glb private constructor(
    val json: JsonObject,
    private val bin: ByteBuffer,
) {
  val nodes: List<GlbNode>
  val animations: List<GlbAnimation>
  val materials: List<GlbMaterial>
  private val meshCache = HashMap<Int, List<GlbPrimitive>>()
  private val parentOf: IntArray

  init {
    nodes =
        json.arr("nodes").mapIndexed { i, e ->
          val o = e.jsonObject
          GlbNode(
              index = i,
              name = o["name"]?.jsonPrimitive?.contentOrNull ?: "node_$i",
              children = o["children"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(),
              mesh = o["mesh"]?.jsonPrimitive?.intOrNull,
              rest = trsOf(o),
              extras = o["extras"] as? JsonObject,
          )
        }
    parentOf = IntArray(nodes.size) { -1 }
    for (n in nodes) for (c in n.children) parentOf[c] = n.index
    materials =
        json.arr("materials").map { e ->
          val o = e.jsonObject
          val pbr = o["pbrMetallicRoughness"] as? JsonObject
          val c = pbr?.get("baseColorFactor")?.jsonArray?.map { it.jsonPrimitive.double.toFloat() }
          GlbMaterial(o["name"]?.jsonPrimitive?.contentOrNull ?: "", (c ?: listOf(1f, 1f, 1f, 1f)).toFloatArray())
        }
    animations =
        json.arr("animations").map { e ->
          val o = e.jsonObject
          val samplers = o.arr("samplers").map { it.jsonObject }
          val channels =
              o.arr("channels").mapNotNull { ce ->
                val c = ce.jsonObject
                val target = c["target"]?.jsonObject ?: return@mapNotNull null
                val node = target["node"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val path = target["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (path == "weights") return@mapNotNull null
                val s = samplers[c["sampler"]!!.jsonPrimitive.int]
                val times = readFloats(s["input"]!!.jsonPrimitive.int)
                val width = if (path == "rotation") 4 else 3
                GlbChannel(
                    node,
                    path,
                    s["interpolation"]?.jsonPrimitive?.contentOrNull ?: "LINEAR",
                    times,
                    readFloats(s["output"]!!.jsonPrimitive.int),
                    width,
                )
              }
          GlbAnimation(o["name"]?.jsonPrimitive?.contentOrNull ?: "", channels)
        }
  }

  fun node(name: String): GlbNode? = nodes.firstOrNull { it.name == name }

  fun parent(node: Int): Int? = parentOf[node].takeIf { it >= 0 }

  fun animation(name: String): GlbAnimation? = animations.firstOrNull { it.name == name }

  /** Every node's local TRS with [clip] applied at time [t] (null clip or channels missing = rest pose). */
  fun localPosesAt(clip: GlbAnimation?, t: Float): Array<Trs> {
    val out = Array(nodes.size) { nodes[it].rest }
    if (clip == null) return out
    for (c in clip.channels) {
      val v = c.sample(t)
      val cur = out[c.node]
      out[c.node] =
          when (c.path) {
            "translation" -> cur.copy(t = Vec3(v[0], v[1], v[2]))
            "rotation" -> cur.copy(r = Quat(v[0], v[1], v[2], v[3]).normalized())
            "scale" -> cur.copy(s = Vec3(v[0], v[1], v[2]))
            else -> cur
          }
    }
    return out
  }

  /** [root] ← [node] transform from [locals]; null when [node] is not under [root]. */
  fun relativeTo(root: Int, node: Int, locals: Array<Trs>): Trs? {
    var acc = Trs()
    var cur = node
    while (cur != root) {
      acc = locals[cur] * acc
      cur = parent(cur) ?: return null
    }
    return acc
  }

  /** Every node below [root] (not [root] itself), depth first. */
  fun descendants(root: Int): List<Int> {
    val out = ArrayList<Int>()
    fun walk(i: Int) {
      for (c in nodes[i].children) {
        out += c
        walk(c)
      }
    }
    walk(root)
    return out
  }

  fun mesh(index: Int): List<GlbPrimitive> =
      meshCache.getOrPut(index) {
        val m = json.arr("meshes")[index].jsonObject
        m.arr("primitives").mapNotNull { pe ->
          val p = pe.jsonObject
          val mode = p["mode"]?.jsonPrimitive?.intOrNull ?: 4
          if (mode != 4) return@mapNotNull null // triangles only
          val attrs = p["attributes"]!!.jsonObject
          val pos = readFloats(attrs["POSITION"]!!.jsonPrimitive.int)
          val nrm = attrs["NORMAL"]?.jsonPrimitive?.intOrNull?.let { readFloats(it) }
          val idx = p["indices"]?.jsonPrimitive?.intOrNull?.let { readInts(it) } ?: IntArray(pos.size / 3) { it }
          GlbPrimitive(pos, nrm, idx, p["material"]?.jsonPrimitive?.intOrNull)
        }
      }

  // ---- accessors ----

  private fun accessorView(acc: Int): Triple<JsonObject, Int, Int> {
    val a = json.arr("accessors")[acc].jsonObject
    require(a["sparse"] == null) { "sparse accessors are not supported" }
    val bv = json.arr("bufferViews")[a["bufferView"]!!.jsonPrimitive.int].jsonObject
    require((bv["buffer"]?.jsonPrimitive?.intOrNull ?: 0) == 0) { "only the GLB BIN buffer is supported" }
    val offset = (bv["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0) + (a["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0)
    val stride = bv["byteStride"]?.jsonPrimitive?.intOrNull ?: 0
    return Triple(a, offset, stride)
  }

  private fun comps(type: String) =
      when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        "MAT4" -> 16
        else -> error("accessor type $type")
      }

  private fun compSize(ct: Int) =
      when (ct) {
        5120, 5121 -> 1
        5122, 5123 -> 2
        5125, 5126 -> 4
        else -> error("componentType $ct")
      }

  private fun readComp(off: Int, ct: Int, normalized: Boolean): Float =
      when (ct) {
        5126 -> bin.getFloat(off)
        5120 -> bin.get(off).toFloat().let { if (normalized) maxOf(it / 127f, -1f) else it }
        5121 -> (bin.get(off).toInt() and 0xFF).toFloat().let { if (normalized) it / 255f else it }
        5122 -> bin.getShort(off).toFloat().let { if (normalized) maxOf(it / 32767f, -1f) else it }
        5123 -> (bin.getShort(off).toInt() and 0xFFFF).toFloat().let { if (normalized) it / 65535f else it }
        5125 -> (bin.getInt(off).toLong() and 0xFFFFFFFFL).toFloat()
        else -> error("componentType $ct")
      }

  fun readFloats(acc: Int): FloatArray {
    val (a, offset, stride0) = accessorView(acc)
    val count = a["count"]!!.jsonPrimitive.int
    val n = comps(a["type"]!!.jsonPrimitive.content)
    val ct = a["componentType"]!!.jsonPrimitive.int
    val norm = a["normalized"]?.jsonPrimitive?.booleanOrNull ?: false
    val cs = compSize(ct)
    val stride = if (stride0 > 0) stride0 else n * cs
    val out = FloatArray(count * n)
    for (i in 0 until count) for (k in 0 until n) out[i * n + k] = readComp(offset + i * stride + k * cs, ct, norm)
    return out
  }

  fun readInts(acc: Int): IntArray {
    val (a, offset, stride0) = accessorView(acc)
    val count = a["count"]!!.jsonPrimitive.int
    val ct = a["componentType"]!!.jsonPrimitive.int
    val cs = compSize(ct)
    val stride = if (stride0 > 0) stride0 else cs
    return IntArray(count) {
      val o = offset + it * stride
      when (ct) {
        5121 -> bin.get(o).toInt() and 0xFF
        5123 -> bin.getShort(o).toInt() and 0xFFFF
        5125 -> bin.getInt(o)
        else -> error("index componentType $ct")
      }
    }
  }

  companion object {
    private const val MAGIC = 0x46546C67 // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(file: File): Glb = parse(file.readBytes())

    fun parse(bytes: ByteArray): Glb {
      val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      require(bytes.size >= 20 && b.getInt(0) == MAGIC) { "not a GLB file" }
      require(b.getInt(4) == 2) { "glTF version ${b.getInt(4)} not supported" }
      var off = 12
      var jsonObj: JsonObject? = null
      var bin: ByteBuffer? = null
      while (off + 8 <= bytes.size) {
        val len = b.getInt(off)
        val type = b.getInt(off + 4)
        val start = off + 8
        when (type) {
          CHUNK_JSON -> jsonObj = json.parseToJsonElement(String(bytes, start, len, Charsets.UTF_8)).jsonObject
          CHUNK_BIN ->
              bin = ByteBuffer.wrap(bytes, start, len).slice().order(ByteOrder.LITTLE_ENDIAN)
        }
        off = start + len
      }
      val j = requireNotNull(jsonObj) { "GLB has no JSON chunk" }
      j["extensionsRequired"]?.jsonArray?.let { req ->
        require(req.isEmpty()) { "GLB requires extensions ${req.joinToString()} (use the unpacked walkthrough.glb)" }
      }
      return Glb(j, bin ?: ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN))
    }

    private fun JsonObject.arr(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())

    private fun floats(o: JsonObject, key: String): List<Float>? =
        (o[key] as? JsonArray)?.map { it.jsonPrimitive.double.toFloat() }

    private fun trsOf(o: JsonObject): Trs {
      require(o["matrix"] == null) { "node matrices are not supported (the pipeline writes TRS)" }
      val t = floats(o, "translation")?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3.ZERO
      val r = floats(o, "rotation")?.let { Quat(it[0], it[1], it[2], it[3]).normalized() } ?: Quat.IDENTITY
      val s = floats(o, "scale")?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3(1f, 1f, 1f)
      return Trs(t, r, s)
    }
  }
}
