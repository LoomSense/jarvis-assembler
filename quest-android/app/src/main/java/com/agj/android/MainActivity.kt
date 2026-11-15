package com.agj.android

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.platform.ComposeView
import com.agj.android.core.Agq
import com.agj.android.core.Params
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.StepBy
import com.agj.android.core.TrackSource
import com.agj.android.core.Vec3
import com.agj.android.guide.ActionKind
import com.agj.android.guide.Assembly
import com.agj.android.guide.GhostRenderer
import com.agj.android.guide.HttpJobRepository
import com.agj.android.guide.SpatialGhostRenderer
import com.agj.android.guide.StubGhostRenderer
import com.agj.android.guide.toPose3
import com.agj.android.guide.toSdk
import com.agj.android.net.HttpServerApi
import com.agj.android.net.ServerApi
import com.agj.android.ui.CoachFlow
import com.agj.android.ui.DP_PER_M
import com.agj.android.ui.Hud
import com.agj.android.ui.HudModel
import com.agj.android.ui.LABEL_PANEL_H
import com.agj.android.ui.LABEL_PANEL_W
import com.agj.android.ui.LabelModel
import com.agj.android.ui.LabelPill
import com.agj.android.ui.STEP_PANEL_H
import com.agj.android.ui.STEP_PANEL_W
import com.agj.android.ui.ServerVoice
import com.agj.android.ui.StatusKind
import com.agj.android.ui.StepPanel
import com.agj.android.ui.StepPanelModel
import com.agj.android.ui.StepPanelState
import com.agj.android.ui.Strings
import com.agj.android.ui.StubVoice
import com.agj.android.ui.Voice
import com.agj.android.ui.WindowFollow
import com.agj.android.ui.downscaleJpeg
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
import com.meta.spatial.toolkit.MeshCreationSystem
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelRenderOptions
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
 * Wiring: launch parameters, passthrough, panels (step window + toolbar, label pill, HUD), camera + tracker (vision/),
 * the coach flow (look → identify → build → locate → register → guide) against [ServerApi], the guide (GLB parsed into
 * [com.agj.android.guide.Guide], drawn by [SpatialGhostRenderer]), voice and the AGQ logs.
 */
class MainActivity : AppSystemActivity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private lateinit var params: Params
  private var api: ServerApi? = null
  private val hud = HudModel()
  private val stepPanel = StepPanelModel()
  private val label = LabelModel()
  private var ghost: GhostRenderer = StubGhostRenderer()
  private var voice: Voice = StubVoice()
  private val filter = ExpPoseFilter()
  private val tracker = MarkerTracker()
  private val follow = WindowFollow()
  private var camera: CameraSource? = null
  private var assembly: Assembly? = null
  private var sceneReady = false
  private var stepEntity: Entity? = null

  private var curStep = 0
  private var curAction = 0
  private var finished = false
  private var tableFallback: Pose3? = null
  private var registerSince = 0L

  private val renderFrames = AtomicInteger()
  private val cameraFrames = AtomicInteger()
  @Volatile private var lastTrack: TrackResult? = null
  @Volatile private var lastJpeg: ByteArray? = null
  @Volatile private var busy = false

  private val lang
    get() = params.lang

  private fun s(key: String, vararg v: Pair<String, Any?>) = Strings.t(lang, key, *v)

  private val coach =
      CoachFlow { from, to, flow ->
        Agq.log.event("coach", mapOf("from" to from, "to" to to, "job" to flow.jobId))
        hud.state = hud.state.copy(stage = to.name.lowercase())
        onStage(to)
      }

  override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    params = loadParams()
    Agq.log.start(BuildConfig.VERSION_NAME, params)
    hud.state = hud.state.copy(server = params.server)
    label.lang = params.lang
    if (params.server.isNotEmpty()) {
      api = HttpServerApi(params.server, params.key).also { voice = ServerVoice(it, cacheDir) }
    }
    ghost =
        SpatialGhostRenderer(
            registerMesh = { uri, make ->
              systemManager.findSystem<MeshCreationSystem>().meshManager.registerMeshCreator(uri) { _, _ -> make() }
            },
            viewer = { runCatching { scene.getViewerPose().toPose3() }.getOrNull() },
            labelPanelId = R.id.panel_label,
            lang = params.lang,
            onLabel = { label.info = it },
        )
    stepPanel.onBack = { back() }
    stepPanel.onNext = { next() }
    stepPanel.onWhere = { whereAmI() }
    stepPanel.onRepeat = { repeat() }

    systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
    systemManager.registerSystem(FrameTick())
    scene.enablePassthrough(true)

    requestCameraPermissions()
    startTicks()
    val job = params.job
    if (job != null) coach.startWithJob(job) else onStage(CoachFlow.Stage.LOOK)
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
      lastJpeg = frame.jpeg
      lastTrack = tracker.onFrame(frame)
      if (coach.stage == CoachFlow.Stage.LOOK && !busy) scope.launch { identify() }
    }
  }

  // ---- coach flow (XR_NATIVE_SPEC §5.4) ----

  private fun card(i: Int, title: String, body: String, status: StatusKind = StatusKind.NONE, statusText: String = "", where: Boolean = false) {
    stepPanel.state =
        StepPanelState(
            stepLabel = Strings.upper(lang, s("coachCards", "i" to i)),
            title = title,
            body = body,
            status = status,
            statusText = statusText,
            whereLabel = if (where) s("retry") else null,
        )
  }

  private fun onStage(to: CoachFlow.Stage) {
    when (to) {
      CoachFlow.Stage.LOOK -> card(1, s("coachLookTitle"), s("coachLookBody"))
      CoachFlow.Stage.IDENTIFY -> card(2, s("coachIdTitle"), s("coachIdBody"))
      CoachFlow.Stage.BUILD -> card(3, s("coachBuildTitle"), s("stageRead"))
      CoachFlow.Stage.LOCATE -> {
        card(4, s("coachLoading"), coach.product ?: coach.jobId ?: "")
        coach.jobId?.let { openJob(it) }
      }
      CoachFlow.Stage.REGISTER -> {
        registerSince = SystemClock.elapsedRealtime()
        card(5, s("coachLockTitle"), s("coachLockBody"))
      }
      CoachFlow.Stage.GUIDE -> {
        val a = assembly?.guide ?: return
        val start = coach.startStep?.let { a.first(it) } ?: a.flatten().firstOrNull() ?: return
        showStep(start.first, start.second, StepBy.AUTO)
      }
    }
  }

  private suspend fun identify() {
    val api = api ?: return
    val jpeg = lastJpeg ?: return
    if (busy) return
    busy = true
    coach.frameCaptured()
    try {
      val t0 = SystemClock.elapsedRealtime()
      val r = api.identify(withContext(Dispatchers.Default) { downscaleJpeg(jpeg) }, lang)
      Agq.log.identify(SystemClock.elapsedRealtime() - t0, r.match?.product, r.confidence)
      val m = r.match
      if (m != null) card(2, s("coachFound", "product" to m.product), s("coachIdBody"))
      coach.identified(m?.job_id, m?.product, m?.ready ?: false)
      if (m != null && !m.ready) build(m.product.ifEmpty { m.job_id })
      if (m == null) delay(2500) // let the user re-aim before the next try
    } catch (e: Exception) {
      Agq.log.error("identify", e.message)
      coach.failed()
      card(1, s("coachLookTitle"), s("coachLookBody"), StatusKind.ERROR, s("noServer"))
      delay(3000)
    } finally {
      busy = false
    }
  }

  private suspend fun build(query: String) {
    val api = api ?: return
    try {
      val open = api.catalogOpen(query)
      if (open.ready) {
        coach.built()
        return
      }
      while (coach.stage == CoachFlow.Stage.BUILD) {
        val st = api.job(open.job_id)
        val stage =
            when (st.status) {
              "awaiting_approval", "waiting" -> s("stageWait")
              "parts" -> s("stageParts")
              "steps", "assembly" -> s("stageSteps")
              else -> s("stageRead")
            }
        card(3, s("coachBuildTitle"), stage)
        if (st.status == "done" || st.status == "ready") {
          coach.built()
          return
        }
        if (st.status == "failed" || st.status == "error") {
          card(3, s("buildFailed"), "", StatusKind.ERROR, s("buildFailed"))
          coach.failed()
          return
        }
        delay(2000)
      }
    } catch (e: Exception) {
      Agq.log.error("build", e.message)
      coach.failed()
    }
  }

  private fun openJob(id: String) {
    val api = api
    if (api == null) {
      Agq.log.error("job", "no server set")
      card(4, s("coachLoading"), id, StatusKind.ERROR, s("noServer"))
      return
    }
    val repo = HttpJobRepository(api, cacheDir)
    scope.launch {
      try {
        val a = withContext(Dispatchers.IO) { repo.load(id) }
        assembly = a
        if (sceneReady) ghost.load(a)
        locate(auto = true)
      } catch (e: Exception) {
        Agq.log.error("job", e.message)
        card(4, s("coachLoading"), id, StatusKind.ERROR, s("noServer"))
      }
    }
  }

  /** `/locate` with the latest frame; [auto] = part of the coach flow (else the "Where am I" button). */
  private fun locate(auto: Boolean) {
    val api = api ?: return
    val job = assembly?.jobId ?: return
    val total = assembly?.guide?.steps?.size ?: 0
    val jpeg = lastJpeg
    if (jpeg == null) {
      if (auto) coach.located(null)
      return
    }
    if (auto) card(4, s("coachLocateTitle"), "")
    scope.launch {
      try {
        val t0 = SystemClock.elapsedRealtime()
        val r = api.locate(job, withContext(Dispatchers.Default) { downscaleJpeg(jpeg) }, lang)
        Agq.log.locate(SystemClock.elapsedRealtime() - t0, r.next_step)
        if (auto) {
          r.next_step?.let { card(4, s("coachLocateTitle"), s("coachLocated", "n" to it, "total" to total)) }
          delay(1200)
          coach.located(r.next_step)
        } else {
          val g = assembly?.guide ?: return@launch
          val target = r.next_step?.let { g.first(it) }
          if (target != null) showStep(target.first, target.second, StepBy.BUTTON)
          else stepPanel.state = stepPanel.state.copy(status = StatusKind.UNSURE, statusText = s("locateFailed"))
        }
      } catch (e: Exception) {
        Agq.log.error("locate", e.message)
        if (auto) coach.failed()
        else stepPanel.state = stepPanel.state.copy(status = StatusKind.UNSURE, statusText = s("locateFailed"))
      }
    }
  }

  // ---- guide ----

  private fun showStep(step: Int, action: Int, by: StepBy) {
    val g = assembly?.guide ?: return
    val st = g.step(step) ?: return
    val act = st.actions.getOrNull(action)
    val firstOfStep = step != curStep || finished
    curStep = step
    curAction = action
    finished = false
    val flat = g.flatten()
    val idx = flat.indexOf(step to action).coerceAtLeast(0)
    val body = act?.text?.get(lang) ?: ""
    val last = g.next(step, action) == null
    stepPanel.state =
        StepPanelState(
            stepLabel = Strings.upper(lang, s("step", "n" to step, "total" to g.steps.size)),
            page = st.page?.let { s("page", "page" to it) } ?: "",
            title = act?.label(lang)?.let { l -> listOfNotNull(l.name, l.count).joinToString(" ") }
                ?: if (act?.kind == ActionKind.TURN) s("turnIt") else st.title,
            body = body,
            hardware = st.hardware.joinToString("  ·  ") { "${it.qty}× ${it.code}" },
            progress = idx / flat.size.coerceAtLeast(1).toFloat(),
            backLabel = s("back"),
            whereLabel = s("whereAmI"),
            repeatLabel = s("repeat"),
            nextLabel = if (last) s("finish") else s("next"),
        )
    hud.state = hud.state.copy(step = step, action = action)
    ghost.setStep(step, action)
    val line =
        if (firstOfStep && st.page != null)
            (if (lang == "el") "Βήμα $step, σελίδα ${st.page}. " else "Step $step, page ${st.page}. ") + body
        else body
    voice.say(line, lang)
    // prefetch the next two lines
    var n = g.next(step, action)
    repeat(2) {
      val p = n ?: return@repeat
      g.step(p.first)?.actions?.getOrNull(p.second)?.text?.get(lang)?.let { voice.prefetch(it, lang) }
      n = g.next(p.first, p.second)
    }
    Agq.log.step(step, action, by)
  }

  private fun next() {
    val g = assembly?.guide ?: return
    if (coach.stage != CoachFlow.Stage.GUIDE) return
    val n = g.next(curStep, curAction)
    if (n == null) {
      finishGuide()
      return
    }
    showStep(n.first, n.second, StepBy.BUTTON)
  }

  private fun back() {
    val g = assembly?.guide ?: return
    if (coach.stage != CoachFlow.Stage.GUIDE) return
    val p = g.previous(curStep, curAction) ?: return
    showStep(p.first, p.second, StepBy.BUTTON)
  }

  private fun repeat() {
    if (coach.stage == CoachFlow.Stage.GUIDE && !finished) showStep(curStep, curAction, StepBy.BUTTON)
    else if (coach.stage == CoachFlow.Stage.LOOK) scope.launch { identify() }
  }

  private fun whereAmI() {
    when (coach.stage) {
      CoachFlow.Stage.GUIDE -> locate(auto = false)
      CoachFlow.Stage.LOOK -> scope.launch { identify() }
      else -> {}
    }
  }

  private fun finishGuide() {
    val g = assembly?.guide ?: return
    finished = true
    ghost.setState(com.agj.android.guide.GhostState.CORRECT)
    stepPanel.state =
        StepPanelState(
            stepLabel = Strings.upper(lang, s("step", "n" to g.steps.size, "total" to g.steps.size)),
            title = s("allDone"),
            body = s("summary", "steps" to g.steps.size, "actions" to g.guidedCount),
            status = StatusKind.SUCCESS,
            statusText = Strings.upper(lang, s("correct")),
            progress = 1f,
            backLabel = s("back"),
        )
    voice.say(s("allDone"), lang)
    Agq.log.event("finish", mapOf("job" to assembly?.jobId, "steps" to g.steps.size))
  }

  // ---- per frame: tracker pose → ghost, window follows the object ----

  private var lastTickNs = 0L

  private fun frameTick() {
    renderFrames.incrementAndGet()
    val now = SystemClock.elapsedRealtimeNanos()
    val dt = if (lastTickNs == 0L) 1f / 72f else ((now - lastTickNs) / 1e9f).coerceIn(0f, 0.1f)
    lastTickNs = now
    val t = lastTrack
    val pose = filter.update(t?.worldFromAssembly, now)
    val head = runCatching { scene.getViewerPose().toPose3() }.getOrNull()

    if (assembly != null && sceneReady) {
      when {
        pose != null -> {
          tableFallback = null
          ghost.setAssemblyPose(pose)
        }
        tableFallback != null -> ghost.setTablePose(tableFallback)
        else -> ghost.setAssemblyPose(null)
      }
      // REGISTER: marker pose → guide; no pose for 5 s → a fixed table spot in front of the user (demo fallback).
      if (coach.stage == CoachFlow.Stage.REGISTER) {
        if (pose != null) {
          card(5, s("coachLockTitle"), s("coachLocked"))
          coach.registered()
        } else if (SystemClock.elapsedRealtime() - registerSince > 5000 && head != null) {
          tableFallback = fixedTableSpot(head)
          Agq.log.event("coach", mapOf("register" to "fixed_spot", "pos_m" to tableFallback!!.t))
          ghost.setTablePose(tableFallback)
          coach.registered()
        }
      }
      ghost.tick(dt)
    }

    // The step window: beside the object once there is one, else where it was placed at start (world-locked).
    val e = stepEntity ?: return
    val objectTop = if (coach.stage == CoachFlow.Stage.GUIDE) ghost.assemblyCenterWorld() else null
    if (objectTop != null && head != null) {
      val target = follow.target(objectTop + Vec3(0f, 0.10f, 0f), 0.22f, head.t)
      val p = follow.update(target, dt) ?: return
      val f = p - head.t
      e.setComponent(Transform(Pose(p.toSdk(), Quaternion.lookRotationAroundY(Vector3(f.x, 0f, f.z)))))
    }
  }

  /** 0.55 m ahead of the head, 0.45 m below it, turned to face the user: the demo spot when nothing is tracked. */
  private fun fixedTableSpot(head: Pose3): Pose3 {
    val fwd = head.q.rotate(Vec3(0f, 0f, -1f))
    val flat = Vec3(fwd.x, 0f, fwd.z).let { v -> v * (1f / v.length().coerceAtLeast(1e-3f)) }
    val pos = Vec3(head.t.x, head.t.y - 0.45f, head.t.z) + flat * 0.55f
    // the assembly's front (+Z in glTF) faces the user
    val yaw = kotlin.math.atan2(-flat.x, -flat.z)
    return Pose3(pos, Quat.yaw(yaw))
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
        val pose = filter.current()
        val src = if (t?.tracked == true) t.source else if (tableFallback != null) TrackSource.NONE else TrackSource.NONE
        Agq.log.track(camFps, t?.tracked == true, pose?.t, pose?.q?.yawDeg(), t?.reprojPx, src)
        hud.state =
            hud.state.copy(cameraFps = camFps, tracked = t?.tracked == true, trackSource = src.wire, reprojPx = t?.reprojPx)
        if (tick % 5 == 0) {
          val fps = renderFrames.getAndSet(0) / 5.0
          Agq.log.frame(fps, null, null)
          hud.state = hud.state.copy(renderFps = fps)
        }
        if (tick % 10 == 1) api?.let { hud.state = hud.state.copy(serverOk = it.healthy()) }
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
    // Coach cards: 0.8 m ahead, 15° down, world-locked once (spec §5.4). Session start is at the origin looking −Z.
    stepEntity =
        Entity.createPanelEntity(R.id.panel_step, Transform(Pose(Vector3(0f, 1.15f, -0.8f), Quaternion(1f, 0f, 0f, 0f))))
    if (params.hud) {
      Entity.createPanelEntity(R.id.panel_hud, Transform(Pose(Vector3(-0.55f, 1.0f, -0.9f), Quaternion(0f, 160f, 0f))))
    }
    sceneReady = true
    assembly?.let { ghost.load(it) }
  }

  override fun registerPanels(): List<PanelRegistration> =
      listOf(
          panel(R.id.panel_step, STEP_PANEL_W, STEP_PANEL_H, layer = true) {
            ComposeView(it).apply { setContent { StepPanel(stepPanel) } }
          },
          panel(R.id.panel_hud, 0.42f, 0.30f, layer = true) { ComposeView(it).apply { setContent { Hud(hud) } } },
          // The label sits on the object: mesh mode, depth-sorted with the 3D guidance (RESEARCH.md §2).
          panel(R.id.panel_label, LABEL_PANEL_W, LABEL_PANEL_H, layer = false) {
            ComposeView(it).apply { setContent { LabelPill(label) } }
          },
      )

  private fun panel(id: Int, w: Float, h: Float, layer: Boolean, view: (android.content.Context) -> ComposeView) =
      ComposeViewPanelRegistration(
          id,
          composeViewCreator = { _, ctx -> view(ctx) },
          settingsCreator = {
            UIPanelSettings(
                shape = QuadShapeOptions(width = w, height = h),
                style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                // 1 dp ≈ 1 mm; ×3 pixel density ≈ 40 px/° at 0.8 m (spec §3.1). UNVERIFIED: resolutionScale semantics.
                display = DpPerMeterDisplayOptions(DP_PER_M, 3f),
                rendering = UIPanelRenderOptions(if (layer) PanelRenderMode.Layer() else PanelRenderMode.Mesh()),
            )
          },
      )

  override fun onDestroy() {
    camera?.stop()
    voice.stop()
    scope.cancel()
    super.onDestroy()
  }

  /** One `execute` per render frame: animation, poses, window follow, and the render-fps counter. */
  private inner class FrameTick : SystemBase() {
    override fun execute() {
      try {
        frameTick()
      } catch (e: Exception) {
        Agq.log.error("frame", e.message)
      }
    }
  }

  companion object {
    private const val REQ_CAMERA = 1
    val CAMERA_PERMISSIONS =
        listOf("android.permission.CAMERA", "horizonos.permission.HEADSET_CAMERA", "com.oculus.permission.USE_SCENE")
  }
}
