package com.agj.android

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.platform.ComposeView
import com.agj.android.core.Agq
import com.agj.android.core.Params
import com.agj.android.core.StepBy
import com.agj.android.core.TrackSource
import com.agj.android.guide.Assembly
import com.agj.android.guide.GhostRenderer
import com.agj.android.guide.HttpJobRepository
import com.agj.android.guide.StubGhostRenderer
import com.agj.android.net.HttpServerApi
import com.agj.android.ui.CoachFlow
import com.agj.android.ui.Hud
import com.agj.android.ui.HudModel
import com.agj.android.ui.StepPanel
import com.agj.android.ui.StepPanelModel
import com.agj.android.ui.StepPanelState
import com.agj.android.ui.StubVoice
import com.agj.android.vision.CameraSource
import com.agj.android.vision.ExpPoseFilter
import com.agj.android.vision.LiveCameraSource
import com.agj.android.vision.MarkerTracker
import com.agj.android.vision.ReplayCameraSource
import com.agj.android.vision.TrackResult
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wiring only: reads the launch parameters, turns on passthrough, places the world-space panels, starts the camera
 * source, the tracker and the coach flow, and emits the periodic `track` / `frame` logs. Each module lives in its own
 * package (guide/, vision/, net/, ui/) behind an interface so it can be replaced without touching this file much.
 */
class MainActivity : AppSystemActivity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private lateinit var params: Params
  private val hud = HudModel()
  private val stepPanel = StepPanelModel()
  private val ghost: GhostRenderer = StubGhostRenderer()
  private val voice = StubVoice()
  private val filter = ExpPoseFilter()
  private val tracker = MarkerTracker()
  private var camera: CameraSource? = null
  private var assembly: Assembly? = null

  private val renderFrames = AtomicInteger()
  private val cameraFrames = AtomicInteger()
  @Volatile private var lastTrack: TrackResult? = null

  private val coach =
      CoachFlow { from, to, flow ->
        Agq.log.event("coach", mapOf("from" to from, "to" to to, "job" to flow.jobId))
        hud.state = hud.state.copy(stage = to.name.lowercase())
      }

  override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    params = loadParams()
    Agq.log.start(BuildConfig.VERSION_NAME, params)
    hud.state = hud.state.copy(server = params.server)

    systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
    systemManager.registerSystem(FrameCounter(renderFrames))
    scene.enablePassthrough(true)

    requestCameraPermissions()
    startTicks()
    params.job?.let { openJob(it) }
  }

  // ---- launch parameters: files/config.json, then intent extras ----

  private fun loadParams(): Params {
    val cfg = File(getExternalFilesDir(null), "config.json")
    val json = runCatching { if (cfg.exists()) cfg.readText() else null }.getOrNull()
    val extras = intent?.extras
    @Suppress("DEPRECATION")
    val map = extras?.keySet()?.associateWith { extras.get(it) } ?: emptyMap()
    return Params.resolve(json, map)
  }

  // ---- permissions and camera ----

  private fun requestCameraPermissions() {
    if (params.replay) {
      startCamera()
      return
    }
    val missing = CAMERA_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isEmpty()) startCamera() else requestPermissions(missing.toTypedArray(), REQ_CAMERA)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != REQ_CAMERA) return
    val denied = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED }
    if (denied.isNotEmpty()) Agq.log.error("permission", "denied: ${denied.joinToString()}")
    startCamera()
  }

  private fun startCamera() {
    val src: CameraSource =
        if (params.replay) ReplayCameraSource(File(getExternalFilesDir(null), "replay")) else LiveCameraSource(this)
    camera = src
    hud.state = hud.state.copy(cameraKind = src.kind)
    src.start { frame ->
      cameraFrames.incrementAndGet()
      lastTrack = tracker.onFrame(frame)
      if (coach.stage == CoachFlow.Stage.LOOK) scope.launch { coach.frameCaptured() }
    }
  }

  // ---- guide ----

  private fun openJob(id: String) {
    if (params.server.isEmpty()) {
      Agq.log.error("job", "no server set")
      return
    }
    coach.startWithJob(id)
    val repo = HttpJobRepository(HttpServerApi(params.server, params.key), cacheDir)
    scope.launch {
      try {
        val a = withContext(Dispatchers.IO) { repo.load(id) }
        assembly = a
        ghost.load(a)
        coach.located(null)
        showStep(a.stepNumbers.firstOrNull() ?: 1, 0, StepBy.AUTO)
      } catch (e: Exception) {
        Agq.log.error("job", e.message)
      }
    }
  }

  private fun showStep(step: Int, action: Int, by: StepBy) {
    val a = assembly ?: return
    val s = a.manifest.step(step) ?: return
    val act = s.actions.getOrNull(action)
    stepPanel.state =
        StepPanelState(
            title = s.title,
            body = act?.text?.get(params.lang) ?: "",
            stepLabel = (if (params.lang == "el") "Βήμα" else "Step") + " $step / ${a.manifest.steps.size}",
            hardware = s.hardware.joinToString("  ") { "${it.qty}× ${it.code}" },
        )
    hud.state = hud.state.copy(step = step, action = action)
    ghost.show(step, action)
    act?.text?.get(params.lang)?.let { voice.say(it, params.lang) }
    Agq.log.step(step, action, by)
  }

  // ---- periodic logs, HUD, server reachability ----

  private fun startTicks() {
    scope.launch {
      var last = SystemClock.elapsedRealtime()
      var tick = 0
      while (isActive) {
        delay(1000)
        val now = SystemClock.elapsedRealtime()
        val dt = (now - last) / 1000.0
        last = now
        tick++
        val camFps = cameraFrames.getAndSet(0) / dt
        val t = lastTrack
        val pose = filter.update(t?.worldFromAssembly, SystemClock.elapsedRealtimeNanos())
        ghost.setWorldFromAssembly(pose)
        val src = if (t?.tracked == true) t.source else TrackSource.NONE
        Agq.log.track(camFps, t?.tracked == true, pose?.t, pose?.q?.yawDeg(), t?.reprojPx, src)
        hud.state =
            hud.state.copy(cameraFps = camFps, tracked = t?.tracked == true, trackSource = src.wire, reprojPx = t?.reprojPx)
        if (tick % 5 == 0) {
          val fps = renderFrames.getAndSet(0) / 5.0
          // cpu/gpu frame times: OVR Metrics (meta-spatial-sdk-ovrmetrics) is the likely source; UNVERIFIED API.
          Agq.log.frame(fps, null, null)
          hud.state = hud.state.copy(renderFps = fps)
        }
        if (tick % 10 == 1 && params.server.isNotEmpty()) {
          val ok = HttpServerApi(params.server, params.key).healthy()
          hud.state = hud.state.copy(serverOk = ok)
        }
      }
    }
  }

  // ---- scene: world-space panels ----

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setLightingEnvironment(
        ambientColor = Vector3(0.6f),
        sunColor = Vector3(4f, 4f, 4f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.3f,
    )
    // Placed in the world at start (never head-locked); phase 2 anchors them next to the assembly.
    Entity.createPanelEntity(R.id.panel_step, Transform(Pose(Vector3(0.45f, 1.15f, -0.9f), Quaternion(0f, 200f, 0f))))
    if (params.hud) {
      Entity.createPanelEntity(R.id.panel_hud, Transform(Pose(Vector3(-0.5f, 1.0f, -0.9f), Quaternion(0f, 160f, 0f))))
    }
    if (assembly == null) {
      stepPanel.state =
          StepPanelState(
              stepLabel = if (params.lang == "el") "Ξεκινάμε" else "Getting started",
              title = if (params.lang == "el") "Κοίτα το κουτί ή την ετικέτα" else "Look at the box or its label",
              body = if (params.job == null) "" else params.job!!,
          )
    }
  }

  override fun registerPanels(): List<PanelRegistration> =
      listOf(
          panel(R.id.panel_step, 0.56f, 0.34f) { ComposeView(it).apply { setContent { StepPanel(stepPanel) } } },
          panel(R.id.panel_hud, 0.42f, 0.30f) { ComposeView(it).apply { setContent { Hud(hud) } } },
      )

  private fun panel(id: Int, w: Float, h: Float, view: (android.content.Context) -> ComposeView) =
      ComposeViewPanelRegistration(
          id,
          composeViewCreator = { _, ctx -> view(ctx) },
          settingsCreator = {
            UIPanelSettings(
                shape = QuadShapeOptions(width = w, height = h),
                style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                display = DpPerMeterDisplayOptions(),
            )
          },
      )

  override fun onDestroy() {
    camera?.stop()
    scope.cancel()
    super.onDestroy()
  }

  /** Counts render frames (one `execute` per frame) for the `frame` log and the HUD. */
  private class FrameCounter(private val n: AtomicInteger) : SystemBase() {
    override fun execute() {
      n.incrementAndGet()
    }
  }

  companion object {
    private const val REQ_CAMERA = 1
    val CAMERA_PERMISSIONS =
        listOf("android.permission.CAMERA", "horizonos.permission.HEADSET_CAMERA", "com.oculus.permission.USE_SCENE")
  }
}
