package com.agj.android.vision

import com.agj.android.core.Pose3

/**
 * Recent head poses (WORLD ← HEAD, our right-handed WORLD) with timestamps, so a camera frame can get the head pose
 * at its capture time. The render loop pushes one sample per frame ([SpatialInput]); the camera thread asks for
 * [at] the frame's `SENSOR_TIMESTAMP`. Linear interpolation in position, nlerp in rotation.
 *
 * Pure Kotlin, unit-tested. Thread-safe (synchronized; tiny critical sections).
 *
 * @param capacity samples kept (72 Hz × 2 s ≈ 144).
 * @param maxExtrapolateNs how far outside the buffered range [at] still answers with the nearest sample.
 */
class HeadPoseBuffer(private val capacity: Int = 256, private val maxExtrapolateNs: Long = 100_000_000L) {
  private val ts = LongArray(capacity)
  private val poses = arrayOfNulls<Pose3>(capacity)
  private var n = 0
  private var head = 0 // index of the next write

  @Synchronized
  fun push(tNs: Long, worldFromHead: Pose3) {
    // keep time order: a sample older than the newest is dropped
    if (n > 0 && tNs <= ts[(head - 1 + capacity) % capacity]) return
    ts[head] = tNs
    poses[head] = worldFromHead
    head = (head + 1) % capacity
    if (n < capacity) n++
  }

  @Synchronized fun size() = n

  /** Newest sample time, or null when empty. */
  @Synchronized fun newestNs(): Long? = if (n == 0) null else ts[(head - 1 + capacity) % capacity]

  @Synchronized
  fun at(tNs: Long): Pose3? {
    if (n == 0) return null
    val oldest = (head - n + capacity) % capacity
    val newest = (head - 1 + capacity) % capacity
    if (tNs >= ts[newest]) return if (tNs - ts[newest] <= maxExtrapolateNs) poses[newest] else null
    if (tNs <= ts[oldest]) return if (ts[oldest] - tNs <= maxExtrapolateNs) poses[oldest] else null
    // binary search over the ring in logical order
    var lo = 0
    var hi = n - 1
    while (hi - lo > 1) {
      val mid = (lo + hi) / 2
      if (ts[(oldest + mid) % capacity] <= tNs) lo = mid else hi = mid
    }
    val a = (oldest + lo) % capacity
    val b = (oldest + hi) % capacity
    val pa = poses[a]!!
    val pb = poses[b]!!
    val f = ((tNs - ts[a]).toDouble() / (ts[b] - ts[a]).toDouble()).toFloat()
    return Pose3(pa.t.lerp(pb.t, f), pa.q.nlerp(pb.q, f))
  }

  @Synchronized
  fun clear() {
    n = 0
    head = 0
  }
}
