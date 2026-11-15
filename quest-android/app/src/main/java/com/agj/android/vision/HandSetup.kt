package com.agj.android.vision

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.agj.android.core.Agq

/**
 * The part of hand tracking that needs an Android Context: what the system says about the manifest features and
 * permissions (AGQ `hands_setup`, once per start) and the body-tracking permission prompt.
 *
 * Why body tracking: in Spatial SDK 0.15.0 the only Kotlin API with finger joints is the body-tracking skeleton
 * (`Scene.updateBodyTrackingBuffersAtTime`, joints `*_HAND_WRIST`, `*_HAND_PALM`, `*_HAND_*_TIP`). Meta's
 * BodyTrackingSample declares `com.oculus.software.body_tracking` (and `com.oculus.experimental.enabled`) in its
 * manifest; without the feature the session fails with "Failed to initialize session for body" (seen on the
 * headset, Sun 15 Nov). `com.oculus.permission.BODY_TRACKING` is declared and asked for as well: Meta's native
 * body-tracking documentation lists it, the sample does not. UNVERIFIED which of the two the OS wants.
 */
object HandSetup {
  const val HAND_PERMISSION = "com.oculus.permission.HAND_TRACKING"
  const val BODY_PERMISSION = "com.oculus.permission.BODY_TRACKING"
  const val HAND_FEATURE = "oculus.software.handtracking"
  const val BODY_FEATURE = "com.oculus.software.body_tracking"
  const val REQ_BODY = 4711

  @Volatile private var app: Context? = null
  @Volatile private var asked = false

  /**
   * Call once the camera permissions were answered (VisionModule.start): logs `hands_setup` and, when the
   * body-tracking permission is missing, asks for it 0.8 s later on the main thread (never while another
   * permission dialog of the same pass is still open; a refused or unknown permission only logs).
   */
  fun onStart(context: Context) {
    app = context.applicationContext
    val body = granted(context, BODY_PERMISSION)
    val ask = !body && !asked && context is Activity
    val pm = context.packageManager
    Agq.log.event(
        "hands_setup",
        mapOf(
            "hand_permission" to granted(context, HAND_PERMISSION),
            "body_permission" to body,
            "hand_feature" to runCatching { pm.hasSystemFeature(HAND_FEATURE) }.getOrNull(),
            "body_feature" to runCatching { pm.hasSystemFeature(BODY_FEATURE) }.getOrNull(),
            "asking_body_permission" to ask,
        ),
    )
    if (!ask) return
    asked = true
    Handler(Looper.getMainLooper()).postDelayed(
        {
          try {
            (context as Activity).requestPermissions(arrayOf(BODY_PERMISSION), REQ_BODY)
          } catch (e: Exception) {
            Agq.log.error("hands", "body permission request: ${e.message}")
          }
        },
        800,
    )
  }

  /** The body-tracking permission is granted; null before [onStart]. Cheap enough to poll every few seconds. */
  fun bodyPermission(): Boolean? = app?.let { granted(it, BODY_PERMISSION) }

  /** The hand-tracking permission (a normal permission, granted at install when the manifest declares it). */
  fun handPermission(): Boolean? = app?.let { granted(it, HAND_PERMISSION) }

  private fun granted(c: Context, permission: String): Boolean =
      runCatching { c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
}
