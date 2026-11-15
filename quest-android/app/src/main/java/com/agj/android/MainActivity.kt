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
import com.agj.android.core.Vec3
import com.agj.android.guide.ActionKind
import com.agj.android.guide.Assembly
import com.agj.android.guide.Edges
import com.agj.android.guide.GhostMaterial
import com.agj.android.guide.GhostMaterialKind
import com.agj.android.guide.GhostRenderer
import com.agj.android.guide.GhostState
import com.agj.android.guide.HttpJobRepository
import com.agj.android.guide.SpatialGhostRenderer
import com.agj.android.guide.StubGhostRenderer
import com.agj.android.guide.facingPose
import com.agj.android.guide.toPose3
import com.agj.android.guide.toSdk
import com.agj.android.net.HandSummary
import com.agj.android.net.HttpServerApi
import com.agj.android.net.OkHttpPerceptionSocket
import com.agj.android.net.PerceptionListener
import com.agj.android.net.ServerApi
import com.agj.android.ui.CameraPreviewFeed
import com.agj.android.ui.CoachFlow
import com.agj.android.ui.Images
import com.agj.android.ui.MicState
import com.agj.android.ui.PartCard
import com.agj.android.ui.PartWire
import com.agj.android.ui.PreviewBox
import com.agj.android.ui.SIDE_PANEL_H
import com.agj.android.ui.SIDE_PANEL_W
import com.agj.android.ui.SidePanel
import com.agj.android.ui.SidePanelModel
import com.agj.android.ui.DP_PER_M
import com.agj.android.ui.Hud
import com.agj.android.ui.HudModel
import com.agj.android.ui.LABEL_PANEL_H
import com.agj.android.ui.LABEL_PANEL_W
import com.agj.android.ui.LabelModel
import com.agj.android.ui.LabelPill
import com.agj.android.ui.Placement
import com.agj.android.ui.STEP_PANEL_H
import com.agj.android.ui.STEP_PANEL_W
import com.agj.android.ui.ServerVoice
import com.agj.android.ui.Sounds
import com.agj.android.ui.StatusKind
import com.agj.android.ui.StepPanel
import com.agj.android.ui.StepPanelModel
import com.agj.android.ui.StepPanelState
import com.agj.android.ui.Strings
import com.agj.android.ui.StubVoice
import com.agj.android.ui.Voice
import com.agj.android.ui.VoiceCommandClient
import com.agj.android.ui.VoiceController
import com.agj.android.ui.VoiceReply
import com.agj.android.ui.WindowRelocator
import com.agj.android.ui.downscaleJpeg
import com.agj.android.vision.Aabb
import com.agj.android.vision.CameraFrame
import com.agj.android.vision.TrackSnapshot
import com.agj.android.vision.VisionListener
import com.agj.android.vision.VisionModule
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.MeshCreationSystem
import com.agj.android.vision.DumpExtras
import com.agj.android.vision.FrameDump
import com.agj.android.vision.PanelParams
import com.agj.android.vision.PanelPoseTracker
import com.agj.android.vision.StepFootprints
import com.agj.android.world.PickMarkers
import com.agj.android.world.PlaceSource
import com.agj.android.world.WorldModule
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Wiring: launch parameters, passthrough, panels (step window + toolbar, label pill, HUD), [VisionModule] (camera,
 * head pose, marker tracking, filter, touch fallback, hands, the `track` log), the coach flow (look → identify →
 * build → locate → register → guide) against [ServerApi], the guide (GLB parsed into [com.agj.android.guide.Guide],
 * drawn by [SpatialGhostRenderer]), the perception socket (frames + hands out, guidance events in →
 * [onGuidanceEvent]), voice and the AGQ logs.
 *
 * Frames: everything here is right-handed WORLD (vision/Frames.kt): `vision.worldFromAssembly()`, the head pose via
 * `toPose3()`, window and label placement. Only guide/SpatialGhostRenderer.kt's `toSdk`/`toPose3`/`facingPose`
 * convert to the left-handed Spatial SDK.
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
  private lateinit var vision: VisionModule
  private var mruk: MRUKFeature? = null
  private lateinit var world: WorldModule
  private var pickMarkers: PickMarkers? = null
  private var panel: PanelPoseTracker? = null
  private var dump: FrameDump? = null
  private var socket: OkHttpPerceptionSocket? = null
  private var socketStarted = false
  private val follow = WindowRelocator()
  private val side = SidePanelModel()
  private var sideEntity: Entity? = null
  private var sideShown: Boolean? = null
  private var windowPos: Vec3? = null
  private var windowHead: Vec3? = null
  private var preview: CameraPreviewFeed? = null
  private var voiceCtl: VoiceController? = null
  private val drawings = HashMap<Int, ImageBitmap?>()
  private val wires = HashMap<String, FloatArray>()
  private var lastRelocations = -1 // −1: the first placement beside the object is logged too (`window`)
  private val sounds by lazy { Sounds() }
  private var lostShown = false
  private var advancing = false
  private var panelsPlaced = false
  private var hudEntity: Entity? = null
  private var assembly: Assembly? = null
  private var sceneReady = false
  private var stepEntity: Entity? = null

  // HELD-PART (ui/HeldPartGuide.kt): which part to take, and whether the one in the hand is right. Every lambda is
  // first called from a frame tick or a perception event, so the lateinit fields it reads are set by then.
  private val held =
      com.agj.android.ui.HeldPartGuide(
          lang = { params.lang },
          // CONFIRM and PROMPT never talk over the guide, the rest interrupt (a speech queue maps HeldSpeech here)
          say = { text, kind -> coachSay(text, kind) }, // ACTION-COACH: the phase decides when "Σωστό" and "Πάρε" are heard
          prefetch = { voice.prefetch(it, params.lang) },
          speaking = { voice.speaking },
          play = { sounds.play(it) },
          stepPanel = stepPanel,
          side = side,
          wrists = { vision.input.hands?.hands?.map { it.wrist } ?: emptyList() },
          pickTargets = { coachShown }, // ACTION-COACH: only the part to take now, and only while it is being taken
          window = { windowPos to windowHead },
          showPart = { expected -> socketStarted && socket?.showPart(expected) == true },
          nameOf = { id -> heldName(id) },
          cues =
              com.agj.android.guide.VerdictCues { uri, make ->
                systemManager.findSystem<MeshCreationSystem>().meshManager.registerMeshCreator(uri) { _, _ -> make() }
              },
      )

  private var curStep = 0
  private var curAction = 0
  private var finished = false
  private var tableFallback: Pose3? = null
  private var registerSince = 0L

  private val renderFrames = AtomicInteger()
  @Volatile private var lastJpeg: ByteArray? = null
  @Volatile private var lastJpegMs = 0L
  @Volatile private var busy = false
  private val lookQueued = AtomicBoolean(false)

  private val lang
    get() = params.lang

  private fun s(key: String, vararg v: Pair<String, Any?>) = Strings.t(lang, key, *v)

  private val coach =
      CoachFlow { from, to, flow ->
        Agq.log.event("coach", mapOf("from" to from, "to" to to, "job" to flow.jobId))
        hud.state = hud.state.copy(stage = to.name.lowercase())
        onStage(to)
      }

  override fun registerFeatures(): List<SpatialFeature> =
      listOf(VRFeature(this), ComposeFeature(), MRUKFeature(this, systemManager).also { mruk = it })

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    params = loadParams()
    Agq.log.start(BuildConfig.VERSION_NAME, params)
    hud.state = hud.state.copy(server = params.server)
    label.lang = params.lang
    vision = VisionModule(this, params.replay)
    systemManager.registerSystem(vision.input) // head pose + hands + render fps, every frame
    vision.listener = visionListener
    if (params.server.isNotEmpty()) {
      api = HttpServerApi(params.server, params.key).also { voice = ServerVoice(it, cacheDir) }
    }
    // table from the room scan, parts in the pile in 3D (world/)
    world =
        WorldModule(this, vision, mruk, { api }, params.lang) { uri, make ->
          systemManager.findSystem<MeshCreationSystem>().meshManager.registerMeshCreator(uri) { _, _ -> make() }
        }
    // the white front panel found in the camera picture, on the device, 3 Hz: the ghost follows the real part
    // FLAT-PARTS: the detector is built in any case: the same 3 Hz pass also finds every loose board on the surface
    // (world.flat says which part each one is and lights the one to pick up), with the same PanelParams (lightness
    // only). `markerless false` switches off only the ghost following the front panel, as before.
    panel =
        PanelPoseTracker(
                tableHeight = { world.surfaceY },
                ghostPose = { vision.worldFromAssembly() },
                onObjectPose = { pose, confidence, source -> world.onObjectPose(pose, confidence, source) },
                // Size gate tuned on the device: in the first real run (12:36) a clean panel measured 13-18 mm short
                // and 6-14 mm wide of its true size, and the render-tuned 14 / 12 mm rejected every frame.
                params =
                    PanelParams(
                        tolLongM = (intent?.getIntExtra("panel_tol_long_mm", 30) ?: 30) / 1000.0,
                        tolShortM = (intent?.getIntExtra("panel_tol_short_mm", 25) ?: 25) / 1000.0,
                        // Lightness only: the real drawer front is wood-coloured, and the colour penalty (1.5) lost
                        // it on the dark carpet. On 199 real frames: 2 trusted with 1.5, 80 with 0 (size error
                        // p50 +6 / -0.4 mm, consecutive poses 5 mm / 0.24 deg apart).
                        chromaWeight = (intent?.getFloatExtra("panel_chroma_w", 0f) ?: 0f).toDouble(),
                    ),
            )
            .also {
              it.enabled = params.markerless // FLAT-PARTS
              it.rectsWanted = { world.wantsRects } // FLAT-PARTS
              it.onRects = { r, worldFromTable -> world.onRects(r, worldFromTable) } // FLAT-PARTS
            }
    world.reducedMotion = params.reducedMotion // FLAT-PARTS: no pulse on the part outline
    dump = FrameDump(this, params) // `--ez dump_frames true` records camera frames with their pose (tools/markerless/eval_real.py)
    pickMarkers =
        PickMarkers { uri, make ->
              systemManager.findSystem<MeshCreationSystem>().meshManager.registerMeshCreator(uri) { _, _ -> make() }
            }
            .also { it.reducedMotion = params.reducedMotion }
    // `--es ghost_material unlit` forces the SDK unlit fallback (default: our fresnel shader, see GhostMaterial.kt)
    if (intent?.getStringExtra("ghost_material") == "unlit") GhostMaterial.preferred = GhostMaterialKind.UNLIT
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
    stepPanel.onAlign = { realign() }
    stepPanel.onPress = { sounds.play(Sounds.Cue.TICK) }
    ghost.reducedMotion = params.reducedMotion
    side.reducedMotion = params.reducedMotion
    side.showCamera = params.hud
    side.caption = s("cameraOff")
    // HELD-PART: the "Έλεγχος" button on the ΠΑΡΕ card checks the part in the hand now
    held.reducedMotion = params.reducedMotion
    side.onPress = { sounds.play(Sounds.Cue.TICK) }
    side.onCheck = { held.check("card") }
    if (params.hud) preview = CameraPreviewFeed(scope, side)
    if (params.server.isNotEmpty()) voiceCtl = VoiceController(VoiceCommandClient(params.server, params.key), scope, voiceHost)
    stepPanel.onMicDown = { voiceCtl?.down() }
    stepPanel.onMicUp = { voiceCtl?.up() }

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
    val wanted = if (params.replay) listOf(MIC_PERMISSION) else CAMERA_PERMISSIONS + MIC_PERMISSION
    val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (params.replay) startCamera()
    if (missing.isEmpty()) {
      if (!params.replay) startCamera()
    } else requestPermissions(missing.toTypedArray(), if (params.replay) REQ_MIC else REQ_CAMERA)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != REQ_CAMERA && requestCode != REQ_MIC) return
    val denied = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED }
    if (denied.isNotEmpty()) Agq.log.error("permission", "denied: ${denied.joinToString()}")
    if (requestCode == REQ_CAMERA) startCamera()
  }

  private fun startCamera() {
    hud.state = hud.state.copy(cameraKind = vision.cameraKind)
    vision.start()
    world.start()
  }

  /**
   * Camera thread. `frame.jpeg` is encoded lazily (live), so it is read only when needed: the LOOK frame for
   * identify, one frame a second for locate / "Where am I", and the perception frames the socket can take now.
   */
  private val visionListener =
      object : VisionListener {
        override fun onFrame(frame: CameraFrame, track: TrackSnapshot) {
          val stage = coach.stage
          val now = SystemClock.elapsedRealtime()
          dump?.onFrame(frame) {
            DumpExtras(surfaceY = world.surfaceY, anchor = world.anchor.pose, anchorSource = world.placeSource,
                ghost = vision.worldFromAssembly(), job = assembly?.jobId, step = curStep, action = curAction,
                track = track, panel = panel?.latest)
          }
          panel?.onFrame(frame)
          preview?.offer { frame.jpeg }
          if (stage == CoachFlow.Stage.LOOK) {
            if (!busy && lookQueued.compareAndSet(false, true)) {
              keepJpeg(frame, now)
              scope.launch {
                lookQueued.set(false)
                identify()
              }
            }
          } else if (now - lastJpegMs >= 1000) {
            keepJpeg(frame, now)
          }
          val sock = socket
          if (stage == CoachFlow.Stage.GUIDE && socketStarted && !finished && sock != null && sock.canSend()) {
            try {
              val lim = sock.limits
              val jpeg = downscaleJpeg(frame.jpeg, lim.longEdgePx, (lim.jpegQuality * 100).toInt().coerceIn(30, 95))
              sock.sendFrame(jpeg, curStep, System.currentTimeMillis())
            } catch (e: Exception) {
              Agq.log.error("perception_frame", e.message)
            }
          }
        }

        override fun onHands(h: HandSummary) {
          socket?.sendHands(h)
        }
      }

  private fun keepJpeg(frame: CameraFrame, now: Long) {
    val j = frame.jpeg
    if (j.isNotEmpty()) {
      lastJpeg = j
      lastJpegMs = now
    }
  }

  // ---- perception socket: frames + hands out, guidance events in (server/perception/README.md) ----

  private fun openSocket(job: String) {
    if (params.server.isEmpty()) return
    socket?.close()
    socketStarted = false
    socket = OkHttpPerceptionSocket(params.server, params.key, Agq.log).also { it.connect(job, perceptionListener) }
  }

  /** `say` is `{en, el}` (or a plain string); null when the event has none. */
  private fun sayOf(raw: JsonObject): String? {
    val s = raw["say"] ?: return null
    (s as? JsonPrimitive)?.let { return it.contentOrNull?.takeIf { t -> t.isNotBlank() } }
    val o = s as? JsonObject ?: return null
    fun get(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    return get(lang) ?: get("en")
  }

  /** OkHttp reader thread → main thread → [onGuidanceEvent]. */
  private val perceptionListener =
      object : PerceptionListener {
        private fun post(event: String, raw: JsonObject, step: Int? = null) {
          val say = sayOf(raw)
          runOnUiThread { if (step == null || step == curStep) onGuidanceEvent(event, say) }
        }

        override fun onStepComplete(step: Int?, raw: JsonObject) = post("step_complete", raw, step)

        override fun onFlipNeeded(raw: JsonObject) = post("wrong_flip", raw)

        override fun onEvent(event: String, raw: JsonObject) {
          // HELD-PART: right_part / right_hardware / wrong_part / wrong_hardware / unsure_show_part /
          // show_part_result are about what is in the hand: the badge at the hand, not the ghost at the target
          // ACTION-COACH: the same event also moves "take" → "put it here" (right) or holds the phase (wrong)
          if (event in com.agj.android.ui.HeldEvent.EVENTS) runOnUiThread { coachHeld(event, raw) }
          else if (event in PASS_THROUGH_EVENTS) post(event, raw)
        }
      }

  /** HELD-PART: the manifest's name for a part id or hardware code, in the app language. */
  private fun heldName(id: String): com.agj.android.guide.PartLabel? {
    val m = assembly?.manifest ?: return null
    fun pick(names: com.agj.android.guide.LocalizedText, short: String, name: String) =
        names.get(lang).ifEmpty { short.ifEmpty { name } }.replaceFirstChar { it.uppercase() }
    m.hardware.firstOrNull { it.code == id }?.let { return com.agj.android.guide.PartLabel(pick(it.names, it.short, it.name), it.code, null) }
    return m.parts.firstOrNull { it.id == id }?.let { com.agj.android.guide.PartLabel(pick(it.names, it.short, it.name), null, null) }
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
            compact = true,
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
        // the two-corner touch belongs to the marker flow and no card explains it: armed only with `--ez touch true`
        if (intent?.getBooleanExtra("touch", false) == true) armTouch()
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
        world.setAssembly(a)
        panel?.setJob(a.jobId)
        // the detector's target comes from the loaded guide from the first moment (its built-in default is the
        // old estimated 380 x 130 mm front)
        a.stepNumbers.firstOrNull()?.let { s0 -> panel?.setStep(s0, a.guide?.let { g -> StepFootprints.target(g, a.manifest, s0) }) }
        if (sceneReady) ghost.load(a)
        openSocket(a.jobId)
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

  /**
   * Two-corner touch fallback while waiting for the marker (vision/TouchTracker.kt): the front-bottom corners in the
   * start step's pose. The marker overrides a touch pose whenever it is seen. Corners UNVERIFIED (Assembly.frontCorners).
   */
  private fun armTouch() {
    val a = assembly ?: return
    val g = a.guide ?: return
    val start = coach.startStep?.let { g.step(it) } ?: g.steps.firstOrNull() ?: return
    val plateFromAssembly = start.tableFromAssembly
    val (l, r) = a.frontCorners()
    vision.startTouch(plateFromAssembly.transform(l) to plateFromAssembly.transform(r), plateFromAssembly)
  }

  // ---- guide ----

  private fun showStep(step: Int, action: Int, by: StepBy, speak: Boolean = true) {
    val g = assembly?.guide ?: return
    val st = g.step(step) ?: return
    val act = st.actions.getOrNull(action)
    val firstOfStep = step != curStep || finished
    val again = !firstOfStep && action == curAction // Repeat: the same action shown again
    val sock = socket
    if (sock != null) {
      when {
        !socketStarted -> sock.start(step, action).also { socketStarted = true }
        firstOfStep -> sock.step(step, action)
        action != curAction -> sock.action(action)
      }
    }
    curStep = step
    curAction = action
    finished = false
    val flat = g.flatten()
    val idx = flat.indexOf(step to action).coerceAtLeast(0)
    val body = act?.text?.get(lang) ?: ""
    val last = g.next(step, action) == null
    coachStart(step, action, act, by, again) // ACTION-COACH: every action starts at "take this part" (Back and Repeat too)
    stepPanel.state =
        StepPanelState(
            stepLabel = Strings.upper(lang, s("step", "n" to step, "total" to g.steps.size)),
            page = st.page?.let { Strings.upper(lang, s("page", "page" to it)) } ?: "",
            title = act?.label(lang)?.let { l -> listOfNotNull(l.name, l.count).joinToString(" ") }
                ?: if (act?.kind == ActionKind.TURN) s("turnIt") else st.title,
            body = body,
            hardware = st.hardware.joinToString("  ·  ") { "${it.qty}× ${it.code}" },
            progress = idx / flat.size.coerceAtLeast(1).toFloat(),
            backLabel = s("back"),
            whereLabel = s("whereAmI"),
            repeatLabel = s("repeat"),
            nextLabel = if (last) s("finish") else s("next"),
            micLabel = if (voiceCtl != null) s("speak") else null,
            drawing = drawings[step],
            pill = if (lostShown) s("lost") else null,
        )
    hud.state = hud.state.copy(step = step, action = action)
    held.onAction(step, action, act, st) // HELD-PART: a new pick; clears the badge and what was waiting
    ghost.setStep(step, action)
    world.setAction(assembly?.jobId, step, action, act)
    // the detector's target is the flat base part of this step, from the guide (off where none is safe to track)
    panel?.setStep(step, assembly?.let { a -> a.guide?.let { g -> StepFootprints.target(g, a.manifest, step) } })
    val coachNow = coachLine() // "Place … inside the blue outline.": first line of the body, spoken before the step line
    stepPanel.coach = coachNow ?: ""
    stepPanel.align = alignLabel()
    loadDrawing(step, st.crop)
    showPartCard(act)
    // ACTION-COACH: one instruction at a time. While the part is still to be taken the window and the voice say
    // "Πάρε: …"; the step's own line comes with "put it here" ([coachApply]).
    coachPaint()
    val picking = actionCoach.phase == com.agj.android.ui.CoachPhase.PICK
    val said = if (picking && speak) coachPickLine(act) ?: body else body
    if (picking) voice.prefetch(body, lang)
    val line =
        if (firstOfStep && st.page != null)
            (if (lang == "el") "Βήμα $step, σελίδα ${st.page}. " else "Step $step, page ${st.page}. ") + said
        else said
    // the coach line is said once per step entry (and on Repeat), not each time the step is drawn again
    val lead = listOfNotNull(stepDone, coachNow?.takeIf { (firstOfStep || again) && !picking })
    coachLead = if (picking && speak) coachNow?.takeIf { firstOfStep || again } else null
    // ACTION-COACH: a bare "Πάρε: …" the wearer did not ask for is a prompt: it waits for silence, it never cuts a sentence
    if (speak && picking && by == StepBy.AUTO && lead.isEmpty() && line === said) {
      if (!voice.speaking) voice.say(line, lang, interrupt = false)
    } else if (speak) voice.sayStep(lead + line, lang)
    stepDone = null
    // prefetch the next two lines
    var n = g.next(step, action)
    repeat(2) {
      val p = n ?: return@repeat
      g.step(p.first)?.actions?.getOrNull(p.second)?.text?.get(lang)?.let { voice.prefetch(it, lang) }
      n = g.next(p.first, p.second)
    }
    Agq.log.step(step, action, by)
  }

  /**
   * The no-marker instruction (`world.coachLine`: "Place the drawer front inside the blue outline.") while it applies:
   * in the guide, on the base part's own step (the outline is drawn only then), with a ghost that is placed, not followed.
   */
  private fun coachLine(): String? =
      if (coach.stage == CoachFlow.Stage.GUIDE && !finished && curStep == world.basePart?.step) world.coachLine(lang) else null

  /** The step's guide drawing (`/files/{job}/crops/step-NN.png`) as white line art in the window's right column. */
  private fun loadDrawing(step: Int, crop: String?) {
    if (drawings.containsKey(step)) return
    val api = api ?: return
    val job = assembly?.jobId ?: return
    drawings[step] = null
    val path = crop?.takeIf { it.isNotBlank() }?.removePrefix("/")?.removePrefix("files/$job/") ?: "crops/step-%02d.png".format(step)
    scope.launch {
      try {
        val bytes = api.file(job, path)
        val img = withContext(Dispatchers.Default) { Images.decodeLineArt(bytes) }
        drawings[step] = img
        if (img != null && curStep == step) stepPanel.state = stepPanel.state.copy(drawing = img)
        Agq.log.event("drawing", mapOf("step" to step, "path" to path, "bytes" to bytes.size, "ok" to (img != null)))
      } catch (e: Exception) {
        Agq.log.error("drawing", "$path: ${e.message}")
      }
    }
  }

  /** Parts ornament (§2.5): the part to pick for this action, as a turntable wireframe + name, code, count. */
  private fun showPartCard(act: com.agj.android.guide.GuideAction?) {
    val node = act?.node
    val geo = node?.let { assembly?.guide?.parts?.get(it) }
    val l = act?.label(lang)
    if (act == null || geo == null || l == null || act.kind == ActionKind.TURN) {
      side.part = null
      return
    }
    val wire = wires.getOrPut(node) { PartWire.of(Edges.featureEdges(geo.positions, geo.indices)) }
    side.part = PartCard(Strings.upper(lang, s("take")), l.name, l.code, l.count, wire)
  }

  /** For the world/perception side: a box on the camera preview (normalized 0..1), or null to clear. Main thread. */
  fun showCameraBox(box: PreviewBox?) {
    side.box = box
  }

  // ---- voice commands (push-to-talk on the toolbar mic, POST /api/voice/{job}) ----

  private val voiceHost =
      object : VoiceController.Host {
        override val job: String?
          get() = assembly?.jobId
        override val lang: String
          get() = params.lang
        override val step: Int
          get() = curStep
        override val action: Int
          get() = curAction

        override fun hasPermission() = checkSelfPermission(MIC_PERMISSION) == PackageManager.PERMISSION_GRANTED

        override fun requestPermission() = requestPermissions(arrayOf(MIC_PERMISSION), REQ_MIC)

        override fun onMicState(state: MicState) {
          stepPanel.mic = state
          if (state == MicState.LISTENING) voice.stop() // barge-in: the user talks, the guide stops
          val text =
              when (state) {
                MicState.LISTENING -> s("listening")
                MicState.THINKING -> s("thinking")
                MicState.IDLE -> null
              }
          if (text != null && coach.stage == CoachFlow.Stage.GUIDE)
              stepPanel.state = stepPanel.state.copy(status = StatusKind.INFO, statusText = Strings.upper(lang, text))
        }

        override fun onReply(reply: VoiceReply, roundTripMs: Long) = onVoiceReply(reply)

        override fun onVoiceError(message: String) {
          if (coach.stage == CoachFlow.Stage.GUIDE)
              stepPanel.state = stepPanel.state.copy(status = StatusKind.UNSURE, statusText = Strings.upper(lang, s("voiceFailed")))
        }
      }

  /** Apply `target` (logs `step` with `by: "voice"`), speak `answer` (the reply's WAV when present), show the transcript. */
  private fun onVoiceReply(r: VoiceReply) {
    val g = assembly?.guide
    val t = r.target
    // ACTION-COACH: "επόμενο" moves one phase (take → put → the next action), never past an action; "πίσω" and
    // "ξανά" start their action again at "take". The coach then speaks its own line instead of the reply's answer.
    val coached = coach.stage == CoachFlow.Stage.GUIDE && g != null && !finished && r.intent in setOf("next", "back", "repeat")
    val moved =
        if (coached && r.intent == "next") coachNext(voice = true)
        else if (coach.stage == CoachFlow.Stage.GUIDE && g != null && t != null && r.isCommand && g.step(t.step) != null &&
            (t.step != curStep || t.action != curAction || r.intent == "repeat")) {
          if (coached) coachWhy = r.intent
          showStep(t.step, t.action, StepBy.VOICE, speak = coached)
          true
        } else false
    if (r.intent == "where_am_i" && coach.stage == CoachFlow.Stage.GUIDE) world.realign() // as the "Where am I" button
    // HELD-PART: "είναι σωστό αυτό;" checks the part in the hand now; the verdict speaks instead of `answer`
    if (!moved && coach.stage == CoachFlow.Stage.GUIDE && held.onVoice(r.transcript, r.intent)) {
      Agq.log.event("voice_apply", mapOf("intent" to r.intent, "moved" to false, "check" to true, "step" to curStep, "action" to curAction))
      return
    }
    if (r.answer.isNotBlank() && !(coached && moved)) { // ACTION-COACH: see above
      val wav = r.ttsWav()
      if (wav != null) voice.play(wav, r.answer, lang) else voice.say(r.answer, lang)
    }
    if (coach.stage == CoachFlow.Stage.GUIDE && r.transcript.isNotBlank()) {
      val heard = s("heard", "t" to "«${r.transcript}»")
      stepPanel.state = stepPanel.state.copy(status = StatusKind.INFO, statusText = heard)
      val shown = stepPanel.state
      scope.launch {
        delay(3000)
        if (stepPanel.state === shown) stepPanel.state = stepPanel.state.copy(status = StatusKind.NONE, statusText = "")
      }
    }
    Agq.log.event("voice_apply", mapOf("intent" to r.intent, "moved" to moved, "step" to curStep, "action" to curAction))
  }

  private fun next() { coachNext(voice = false) } // ACTION-COACH: one phase per pinch (take → put → the next action)

  // ---- ACTION-COACH (ui/ActionCoach.kt): each action as "take this part" then "put it here", one at a time ----

  private val actionCoach = com.agj.android.ui.ActionCoach()
  /** Why the next `showStep` starts its action (`back`, `repeat`, or what ended the action before it). */
  private var coachWhy: String? = null
  /** Inside [coachHeld]: "Σωστό. <name>." is held back so it can lead the "put it here" line as one sentence group. */
  private var coachHolding = false
  private var coachHeldLine: Pair<String, com.agj.android.ui.HeldSpeech>? = null
  /** The entry line already said where the part lies: the located prompt would only repeat it. */
  private var coachSaidWhere = false
  /** The no-marker line ("… inside the blue outline") of this step entry, kept for the "put it here" moment. */
  private var coachLead: String? = null
  /** Part id / hardware code of the action shown now (empty: nothing to take). */
  private var coachIds: List<String> = emptyList()
  /** The pick targets of that item drawn this frame: while the part is being taken, or after a wrong verdict. */
  private var coachShown: List<com.agj.android.world.PickTarget> = emptyList()
  private var coachSpot: Vec3? = null
  /** Part id / hardware code of the action after this one (the hand going for it says this one is placed). */
  private var coachNextIds: List<String> = emptyList()
  /** Where the wrist was when the grip that is closed now began (`HeldState.sinceMs` names the grip). */
  private var coachGripAt: Vec3? = null
  private var coachGripSince = 0L

  private fun coachAct() = assembly?.guide?.step(curStep)?.actions?.getOrNull(curAction)

  private fun coachLog(c: com.agj.android.ui.CoachChange, source: String? = null) =
      Agq.log.event(
          "coach_phase",
          mapOf("step" to c.step, "action" to c.action, "phase" to c.to.wire, "why" to c.why, "from" to c.from?.wire, "source" to source,
              "how" to actionCoach.how.takeIf { source == "hands" }),
      )

  /** From `showStep`: the action starts at "take" when it has a part with a name, else (a turn) at "put it here". */
  private fun coachStart(step: Int, action: Int, act: com.agj.android.guide.GuideAction?, by: StepBy, again: Boolean) {
    val ids = if (act?.label(lang) != null) listOfNotNull(act.partId, act.code) else emptyList()
    val next = assembly?.guide?.next(step, action)
    val why = coachWhy ?: if (again) "repeat" else if (by == StepBy.VOICE) "voice" else if (by == StepBy.AUTO) "start" else "next"
    coachWhy = null
    coachHeldLine = null
    coachSaidWhere = false
    coachSpot = null
    val c = actionCoach.start(step, action, act?.kind, ids, next == null || next.first != step, SystemClock.elapsedRealtime(), why)
    coachIds = if (actionCoach.hasPick) ids else emptyList()
    coachNextIds = next?.let { n -> assembly?.guide?.step(n.first)?.actions?.getOrNull(n.second) }?.let { listOfNotNull(it.partId, it.code) } ?: emptyList()
    coachLog(c)
  }

  /** The window for the phase now: the two dots, one title, one body line, the primary button; the ghost dim or bright. */
  private fun coachPaint() {
    val act = coachAct()
    val l = act?.label(lang)
    val picking = actionCoach.phase == com.agj.android.ui.CoachPhase.PICK
    ghost.emphasis = if (picking) COACH_DIM else 1f
    if (act == null || l == null || !actionCoach.hasPick || actionCoach.phase == com.agj.android.ui.CoachPhase.DONE) return
    val v = com.agj.android.ui.CoachLines.view(lang, actionCoach.phase, l, act.text.get(lang), assembly?.guide?.next(curStep, curAction) == null)
    stepPanel.state =
        stepPanel.state.copy(phase = v.dot, phaseTake = s("take"), phasePut = s("coachPhasePut"), phaseCount = v.count,
            title = v.title, body = v.body, nextLabel = v.next)
    side.part = side.part?.copy(take = Strings.upper(lang, s(if (picking) "take" else "coachPhasePut")))
  }

  /** "Πάρε: Βίδα έκκεντρου, αριστερά σου." when the part is already found, else the plain "Πάρε: Βίδα έκκεντρου." */
  private fun coachPickLine(act: com.agj.android.guide.GuideAction?): String? {
    val l = act?.label(lang) ?: return null
    val head = runCatching { scene.getViewerPose().toPose3() }.getOrNull()?.takeIf { it.t.length() > 1e-3f }
    val best = com.agj.android.ui.PickPrompt.matches(world.pickTargets.filter { com.agj.android.world.PickCue.opacity(it) > 0f }, coachIds).firstOrNull()
    val where = if (best != null && head != null) s(com.agj.android.ui.PickPrompt.bearing(head, best.worldPos)) else null
    coachSaidWhere = where != null
    return com.agj.android.ui.CoachLines.pickSay(lang, l, where)
  }

  /** A phase change made by an event (not by `showStep`): log it, then "put it here" on screen and aloud, or the next action. */
  private fun coachApply(c: com.agj.android.ui.CoachChange?, lead: String? = null, source: String? = null) {
    c ?: return
    coachLog(c, source)
    when (c.to) {
      com.agj.android.ui.CoachPhase.PICK -> {}
      com.agj.android.ui.CoachPhase.PLACE -> {
        coachPaint()
        if (c.why == "part_moved" || c.why == "timer") sounds.play(Sounds.Cue.PLACE) // it moved by itself: one soft cue
        // the step's own line, at its normal priority; "Σωστό. <name>." leads it when the server's verdict brought us
        // here, and the no-marker line ("… inside the blue outline") of this step entry comes with it, once
        voice.sayStep(listOfNotNull(lead, coachLead, coachAct()?.text?.get(lang)), lang)
        coachLead = null
      }
      com.agj.android.ui.CoachPhase.DONE -> {
        coachWhy = c.why
        advance(if (c.why == "next") StepBy.BUTTON else if (c.why == "voice") StepBy.VOICE else StepBy.AUTO, wholeStep = c.why == "step_complete")
      }
    }
  }

  /** Next (the button, or said aloud): true when it moved a phase. */
  private fun coachNext(voice: Boolean): Boolean {
    if (coach.stage != CoachFlow.Stage.GUIDE || advancing || finished || assembly?.guide == null) return false
    val c = actionCoach.next(SystemClock.elapsedRealtime(), voice) ?: return false
    coachApply(c)
    return true
  }

  /** A held-part event: the badge as before ([held]), then the phase: right → "put it here", wrong → it stays. */
  private fun coachHeld(event: String, raw: JsonObject) {
    coachHeldLine = null
    coachHolding = true
    try {
      held.onServerEvent(event, raw)
    } finally {
      coachHolding = false
    }
    val line = coachHeldLine
    coachHeldLine = null
    val ev = com.agj.android.ui.HeldEvent.parse(event, raw, lang)
    var c: com.agj.android.ui.CoachChange? = null
    if (ev != null && coach.stage == CoachFlow.Stage.GUIDE && !finished && !advancing && (ev.step == null || ev.step == curStep)) {
      val now = SystemClock.elapsedRealtime()
      when (ev.verdict) {
        com.agj.android.ui.Verdict.RIGHT -> c = actionCoach.rightPart(now, ev.expectedId)
        com.agj.android.ui.Verdict.WRONG -> actionCoach.wrongPart(now)
        com.agj.android.ui.Verdict.UNSURE -> {}
      }
    }
    if (c != null) coachApply(c, lead = line?.first) else line?.let { coachSpeak(it.first, it.second) } // no change: as before
  }

  /** One place for the urgency of a held-part line: CONFIRM and PROMPT wait for silence, the rest interrupt. */
  private fun coachSpeak(text: String, kind: com.agj.android.ui.HeldSpeech) {
    if (!kind.quiet) voice.say(text, lang) else if (!voice.speaking) voice.say(text, lang, interrupt = false)
  }

  /** What [held] wants to say, timed by the phase (CONFIRM and PROMPT never talk over the guide, the rest interrupt). */
  private fun coachSay(text: String, kind: com.agj.android.ui.HeldSpeech) {
    val picking = actionCoach.phase == com.agj.android.ui.CoachPhase.PICK
    when {
      // the verdict that may end "take" (a confirmation, or the answer to a check): [coachHeld] says it, in front of
      // the "put it here" line when the phase moves, by itself when it does not
      coachHolding && picking && (kind == com.agj.android.ui.HeldSpeech.CONFIRM || kind == com.agj.android.ui.HeldSpeech.ANSWER) ->
          coachHeldLine = text to kind
      // "Πάρε: …, αριστερά σου." only while the part is still to be taken, and not when the entry line said where
      kind == com.agj.android.ui.HeldSpeech.PROMPT && (!picking || coachSaidWhere) -> {}
      else -> coachSpeak(text, kind)
    }
  }

  /**
   * ACTION-COACH hook for FLAT-PARTS: "the part to pick is no longer where it lay". Call it (any thread) when the lit
   * rectangle of the board to take has left its place; [partId] is the board's part id (null = the current action's).
   * While "take" is shown this moves the action to "put it here" (`coach_phase` with `why: part_moved`, [source]).
   */
  fun coachPartMoved(partId: String?, source: String = "rect") = runOnUiThread {
    if (coach.stage == CoachFlow.Stage.GUIDE && !finished && !advancing)
        coachApply(actionCoach.partMoved(SystemClock.elapsedRealtime(), partId), source = source)
  }

  /** Every frame: this frame's pick targets of the action's item, and the signals that end "take" by themselves. */
  private fun coachTick(head: Pose3?) {
    // where the wrist was when the grip that is closed now began: "the hand closed at the part and carried it off"
    val h = vision.input.held
    if (!h.holding) coachGripAt = null
    else if (h.sinceMs != coachGripSince) {
      coachGripSince = h.sinceMs
      coachGripAt = h.handWorldPos
    }
    if (coach.stage != CoachFlow.Stage.GUIDE || finished || coachIds.isEmpty()) {
      coachShown = emptyList()
      world.flatShow = false // FLAT-PARTS: nothing to take now
      return
    }
    val now = SystemClock.elapsedRealtime()
    val all = world.pickTargets
    val mine = all.filter { it.id in coachIds }
    val phase = actionCoach.phase
    if (phase != com.agj.android.ui.CoachPhase.DONE && !advancing) {
      mine.firstOrNull()?.let { coachSpot = it.worldPos }
      val spot = coachSpot
      // a part out of the camera's view is not "gone": the wearer must face where it lay (within 53° of the gaze)
      val looking =
          head == null || spot == null ||
              (spot - head.t).let { d -> d.length() < 1e-3f || d.dot(head.q.rotate(Vec3(0f, 0f, -1f))) / d.length() > 0.6f }
      // a hand at the target: the wrist or a fingertip within 6 cm of the ghost part's box (hands not older than 0.5 s)
      val hands = vision.input.hands?.takeIf { SystemClock.elapsedRealtimeNanos() - it.tNs < 500_000_000L }?.hands ?: emptyList()
      val box = vision.handTarget
      val atTarget = box != null && hands.any { hd -> listOfNotNull(hd.wrist, hd.indexTip, hd.thumbTip).any { box.distanceTo(it) <= 0.06f } }
      val sight =
          com.agj.android.ui.PickSight(
              // FLAT-PARTS: the rectangle's own target comes and goes with the camera's view (live for 2 s): it is not a
              // "part gone" signal; the coach follows the server's boxes, exactly as before
              spots = mine.filter { it.method != com.agj.android.world.FlatParts.METHOD }.map { com.agj.android.ui.PickSpot(it.key, it.worldPos) },
              hand = h.handWorldPos,
              holding = h.holding,
              holdingSinceMs = h.sinceMs,
              looking = looking,
              gripStart = coachGripAt,
              atTarget = atTarget,
              tracked = hands.isNotEmpty(),
              nextSpots = if (phase == com.agj.android.ui.CoachPhase.PLACE) all.filter { it.id in coachNextIds }.map { it.worldPos } else emptyList(),
          )
      coachApply(actionCoach.tick(now, sight), source = "hands")
    }
    coachShown = if (actionCoach.phase == com.agj.android.ui.CoachPhase.PICK || actionCoach.wrongFresh(now)) mine else emptyList()
    // FLAT-PARTS: the outline on the real board follows the same rule as the other pick cues
    world.flatShow = actionCoach.phase == com.agj.android.ui.CoachPhase.PICK || actionCoach.wrongFresh(now)
  }

  /**
   * The current action is done (Next, or a perception `step_complete`): correct sequence first (green check, ghost
   * fades over 600 ms, chime; three-note cue when the step ends), then the next action's ghost (spec §4.7).
   */
  private fun advance(by: StepBy, wholeStep: Boolean = false) {
    val g = assembly?.guide ?: return
    if (coach.stage != CoachFlow.Stage.GUIDE || advancing || finished) return
    val n =
        if (wholeStep) {
          // `step_complete`: the whole step is done, go to the first action of the next step
          val flat = g.flatten()
          val i = flat.indexOf(curStep to curAction)
          flat.drop(i + 1).firstOrNull { it.first != curStep }
        } else {
          g.next(curStep, curAction)
        }
    if (n == null) {
      finishGuide()
      return
    }
    advancing = true
    val stepEnds = n.first != curStep
    ghost.setState(GhostState.CORRECT)
    sounds.play(if (stepEnds) Sounds.Cue.STEP else Sounds.Cue.SUCCESS)
    stepPanel.state = stepPanel.state.copy(status = StatusKind.SUCCESS, statusText = Strings.upper(lang, s("correct")))
    // Said by the next step's entry, in front of its own line (one utterance). Spoken here, with the next
    // instruction appended, it was cut 1.4 s later by that same instruction.
    if (stepEnds) stepDone = if (lang == "el") "Το βήμα $curStep έγινε." else "Step $curStep done."
    scope.launch {
      delay(if (stepEnds) 1400 else 1000)
      advancing = false
      showStep(n.first, n.second, by)
      sounds.play(Sounds.Cue.PLACE)
    }
  }

  /**
   * Live guidance from perception (the socket owner calls this on the main thread): `step_complete` → correct and
   * advance; `wrong_hardware` / `wrong_part` / `wrong_place` → wrong (red, octagon ✕, the spoken reason [say]);
   * `unsure` → amber, asks to see the part; `good` → steady green.
   */
  fun onGuidanceEvent(event: String, say: String?) {
    if (coach.stage != CoachFlow.Stage.GUIDE) return
    when {
      // ACTION-COACH: the server's "done" ends the action through the coach: once, and `step_complete` only on the
      // step's last action (it cannot skip the actions still to be done)
      event == "step_complete" -> if (!advancing && !finished) coachApply(actionCoach.stepComplete(SystemClock.elapsedRealtime()))
      event == "action_complete" -> if (!advancing && !finished) coachApply(actionCoach.actionComplete(SystemClock.elapsedRealtime()))
      event.startsWith("wrong") -> {
        // red dashed outline + octagon ✕ (label) + the spoken reason (spec §4.8); a generic reason if the event has none
        ghost.setState(GhostState.WRONG)
        sounds.play(Sounds.Cue.WARNING)
        val reason = say ?: s("wrongSay")
        stepPanel.state = stepPanel.state.copy(status = StatusKind.ERROR, statusText = Strings.upper(lang, s("wrong")), body = reason)
        voice.say(reason, lang)
      }
      event == "unsure" -> {
        ghost.setState(GhostState.UNSURE)
        sounds.play(Sounds.Cue.UNSURE)
        stepPanel.state = stepPanel.state.copy(status = StatusKind.UNSURE, statusText = Strings.upper(lang, s("unsure")))
        voice.say(say ?: s("unsureSay"), lang)
      }
      event == "good" -> ghost.setState(GhostState.GOOD)
    }
    Agq.log.event("guidance", mapOf("event" to event, "step" to curStep, "action" to curAction))
  }

  /** Tracking lost → status pill above the window, one soft cue and one spoken line per loss (spec §4.12). */
  private fun trackLostPill() {
    val lostNow = coach.stage == CoachFlow.Stage.GUIDE && tableFallback == null && ghost.lost
    if (lostNow == lostShown) return
    lostShown = lostNow
    stepPanel.state = stepPanel.state.copy(pill = if (lostNow) s("lost") else null, pillKind = StatusKind.UNSURE)
    if (lostNow) {
      sounds.play(Sounds.Cue.LOST)
      voice.say(s("lostSay"), lang)
    }
    Agq.log.event("tracking", mapOf("lost" to lostNow))
  }

  private fun back() {
    val g = assembly?.guide ?: return
    if (coach.stage != CoachFlow.Stage.GUIDE) return
    val p = g.previous(curStep, curAction) ?: return
    coachWhy = "back" // ACTION-COACH
    showStep(p.first, p.second, StepBy.BUTTON)
  }

  private fun repeat() {
    if (coach.stage == CoachFlow.Stage.GUIDE && !finished) {
      // on the base part's step Repeat also asks the camera where the part is (the ghost may have started off it)
      if (curStep == world.basePart?.step) world.realign()
      coachWhy = "repeat" // ACTION-COACH
      showStep(curStep, curAction, StepBy.BUTTON)
    } else if (coach.stage == CoachFlow.Stage.LOOK) scope.launch { identify() }
  }

  private var realigning = false
  /** "Step N done.", waiting to be spoken at the head of the next step's entry ([advance] → [showStep]). */
  private var stepDone: String? = null

  /** "Re-align" is offered in the guide while the ghost is only placed (table, fixed spot, server box, nudge), not followed live. */
  private fun alignLabel(): String? {
    if (coach.stage != CoachFlow.Stage.GUIDE || finished) return null
    val src = world.anchor.source ?: return null
    val live = (src == PlaceSource.MARKERLESS && world.anchor.markerlessFresh()) || vision.snapshot().method.let { it == "marker" || it == "anchor" }
    return if (live) null else s("align")
  }

  /**
   * «Ευθυγράμμιση», the way out when the ghost sits in the wrong place: the wearer is asked to look at the part; 1.5 s
   * later the anchor is dropped (`anchor.reset()`: `WorldModule.tick` puts it back on the table, or the fixed spot,
   * in front of where the head points then) and the next camera answer for the base part moves it onto the real part.
   */
  private fun realign() {
    if (coach.stage != CoachFlow.Stage.GUIDE || finished || realigning) return
    realigning = true
    Agq.log.event("align", mapOf("by" to "button", "source" to world.placeSource, "step" to curStep))
    stepPanel.coach = s("alignLook")
    voice.say(s("alignLook"), lang)
    scope.launch {
      delay(1500)
      world.anchor.reset()
      world.realign()
      delay(500) // placed again on the next frame
      realigning = false
      stepPanel.coach = coachLine() ?: ""
    }
  }

  private fun whereAmI() {
    when (coach.stage) {
      CoachFlow.Stage.GUIDE -> {
        locate(auto = false)
        world.realign()
      }
      CoachFlow.Stage.LOOK -> scope.launch { identify() }
      else -> {}
    }
  }

  private fun finishGuide() {
    val g = assembly?.guide ?: return
    finished = true
    ghost.setState(GhostState.CORRECT)
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
    sounds.play(Sounds.Cue.COMPLETE)
    if (socketStarted) socket?.stop()
    socketStarted = false
    Agq.log.event("finish", mapOf("job" to assembly?.jobId, "steps" to g.steps.size))
  }

  // ---- per frame: tracker pose → ghost, window follows the object ----

  private var lastTickNs = 0L

  private fun frameTick() {
    renderFrames.incrementAndGet()
    val now = SystemClock.elapsedRealtimeNanos()
    val dt = if (lastTickNs == 0L) 1f / 72f else ((now - lastTickNs) / 1e9f).coerceIn(0f, 0.1f)
    lastTickNs = now
    // WORLD ← ASSEMBLY, filtered; held forever after the marker is hidden (vision/TrackingCore.kt); null = lost
    val pose = vision.worldFromAssembly()
    val head = runCatching { scene.getViewerPose().toPose3() }.getOrNull()?.takeIf { it.t.length() > 1e-3f }
    if (!panelsPlaced && head != null && renderFrames.get() > 30) placePanels(head)

    coachTick(head) // ACTION-COACH: the phase of the action, and which pick targets are drawn this frame
    world.flatGuiding = coach.stage == CoachFlow.Stage.GUIDE && !finished // FLAT-PARTS: loose boards are looked for while guiding
    if (assembly != null && sceneReady) {
      world.tick(dt) // no-marker registration: table, then the server's box for the base part
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
          card(5, s("coachLockTitle"), world.coachLine(lang) ?: s("coachLocked"))
          vision.cancelTouch()
          coach.registered()
        } else if (SystemClock.elapsedRealtime() - registerSince > 5000 && head != null) {
          val onTable = world.tablePose(head, assembly)
          tableFallback = onTable ?: fixedTableSpot(head)
          world.workSurfaceY = tableFallback!!.t.y
          Agq.log.event("coach", mapOf("register" to (if (onTable != null) "table" else "fixed_spot"), "pos_m" to tableFallback!!.t))
          ghost.setTablePose(tableFallback)
          coach.registered()
        }
      }
      ghost.tick(dt)
      pickMarkers?.update(coachShown, dt) // ACTION-COACH: bright while taking the part, hidden while placing it
      vision.handTarget = ghost.currentTargetBoxWorld()?.let { (lo, hi) -> Aabb(lo, hi) }
    }

    trackLostPill()
    held.tick(dt, head, coach.stage == CoachFlow.Stage.GUIDE && !finished) // HELD-PART: badge, cues, pick pill
    // The step window: beside the object once there is one, else where it was placed at start (world-locked).
    val e = stepEntity ?: return
    val objectTop = if (coach.stage == CoachFlow.Stage.GUIDE) ghost.assemblyCenterWorld() else null
    if (objectTop != null && head != null) {
      val target = follow.target(objectTop + Vec3(0f, 0.10f, 0f), 0.22f, head.t)
      // a turn step rotates the object: the window holds still (no orbit), §2.6 rule 5
      val turning = assembly?.guide?.step(curStep)?.actions?.getOrNull(curAction)?.kind == ActionKind.TURN
      val p = follow.update(target, dt, hold = turning)
      if (p != null) {
        // the facing is frozen while faded in/out so the window does not swing during a relocation
        if (follow.phase == WindowRelocator.Phase.FOLLOW || windowHead == null) windowHead = head.t
        windowPos = p
        e.setComponent(Transform(Placement.facing(p, windowHead!!).toSdk()))
      }
      if (stepPanel.alpha != follow.alpha) {
        stepPanel.alpha = follow.alpha
        side.alpha = follow.alpha
      }
      if (follow.relocations != lastRelocations) {
        lastRelocations = follow.relocations
        Agq.log.event("window", mapOf("relocate" to lastRelocations, "pos_m" to p, "head_m" to head.t, "object_m" to objectTop))
      }
    }
    placeSide()
  }

  /** Side column (parts card + camera preview) on the window's outer edge, same facing (§2.5). */
  private fun placeSide() {
    val se = sideEntity ?: return
    val show = side.part != null || side.showCamera
    if (sideShown != show) {
      sideShown = show
      se.setComponent(Visible(show))
    }
    val p = windowPos ?: return
    val h = windowHead ?: return
    val dx = p.x - h.x
    val dz = p.z - h.z
    val d = kotlin.math.hypot(dx, dz).coerceAtLeast(0.2f)
    val right = Vec3(-dz / d, 0f, dx / d)
    // outer edge = the side away from the object: the window sits right of the object, so further right
    val pos = p + right * (STEP_PANEL_W / 2 + 0.02f + SIDE_PANEL_W / 2) + Vec3(0f, -0.02f, 0f)
    se.setComponent(Transform(Placement.facing(pos, h).toSdk()))
  }

  /**
   * Coach cards 0.8 m ahead of the first real head pose, 15° below eye level, world-locked (spec §5.4); the HUD to
   * the left. Until then (onSceneReady) they sit at the same spot relative to the session origin.
   */
  private fun placePanels(head: Pose3) {
    panelsPlaced = true
    if (coach.stage == CoachFlow.Stage.GUIDE) return // the window already follows the object
    val step = Placement.ahead(head, 0.8f, 0.21f) // tan 15° × 0.8 m
    stepEntity?.setComponent(Transform(Placement.facing(step, head.t).toSdk()))
    windowPos = step
    windowHead = head.t
    val hudPos = Placement.ahead(head, 0.9f, 0.45f, right = -0.55f)
    hudEntity?.setComponent(Transform(facingPose(hudPos, head.t)))
    Agq.log.event("panels", mapOf("head_m" to head.t, "step_m" to step))
  }

  /** The demo spot when nothing is tracked ([Placement.fixedTableSpot], unit-tested in front of the wearer). */
  private fun fixedTableSpot(head: Pose3): Pose3 = Placement.fixedTableSpot(head)

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
        // `track` is logged by VisionModule (10 Hz live, every frame in replay); only the HUD here
        val snap = vision.snapshot()
        val camFps = (vision.hud()["cam_fps"] as? Double) ?: 0.0
        val src = if (snap.worldFromAssembly == null && tableFallback != null) "fixed_spot" else snap.method
        hud.state = hud.state.copy(cameraFps = camFps, tracked = snap.tracked, trackSource = src, reprojPx = snap.reprojPx)
        // the part the server found in the camera frame, drawn on the preview (box_2d = [ymin, xmin, ymax, xmax], 0-1000)
        world.pickTargets.maxByOrNull { it.confidence }?.takeIf { it.box.size == 4 }
            ?.let { t -> showCameraBox(PreviewBox((t.box[1] / 1000).toFloat(), (t.box[0] / 1000).toFloat(), (t.box[3] / 1000).toFloat(), (t.box[2] / 1000).toFloat(), t.label)) }
            ?: showCameraBox(null)
        if (side.showCamera) side.caption = if (camFps > 0.5) s("cameraLive", "fps" to camFps.toInt()) else s("cameraOff")
        if (!realigning) stepPanel.coach = coachLine() ?: "" // gone once the part is followed live, or the base part's step is over
        stepPanel.align = alignLabel()
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
    // Coach cards: 0.8 m ahead, 15° down (spec §5.4), WORLD (session start at the origin looking −Z); moved in front
    // of the real head pose once it is known ([placePanels]).
    val eye = Vec3(0f, 1.36f, 0f)
    stepEntity = Entity.createPanelEntity(R.id.panel_step, Transform(facingPose(Vec3(0f, 1.15f, -0.8f), eye)))
    if (params.hud) {
      hudEntity = Entity.createPanelEntity(R.id.panel_hud, Transform(facingPose(Vec3(-0.55f, 0.9f, -0.9f), eye)))
    }
    sideEntity = Entity.createPanelEntity(R.id.panel_side, Transform(facingPose(Vec3(0.38f, 1.15f, -0.8f), eye)))
    windowPos = Vec3(0f, 1.15f, -0.8f)
    windowHead = eye
    held.onSceneReady(R.id.panel_verdict, R.id.panel_pick_label) // HELD-PART
    sceneReady = true
    assembly?.let { ghost.load(it) }
  }

  override fun registerPanels(): List<PanelRegistration> =
      listOf(
          panel(R.id.panel_step, STEP_PANEL_W, STEP_PANEL_H, layer = true) {
            ComposeView(it).apply { setContent { StepPanel(stepPanel) } }
          },
          panel(R.id.panel_hud, 0.42f, 0.30f, layer = true) { ComposeView(it).apply { setContent { Hud(hud) } } },
          // Parts ornament + camera preview beside the window (§2.5; preview only with hud=true).
          panel(R.id.panel_side, SIDE_PANEL_W, SIDE_PANEL_H, layer = true) {
            ComposeView(it).apply { setContent { SidePanel(side) } }
          },
          // The label sits on the object: mesh mode, depth-sorted with the 3D guidance (RESEARCH.md §2).
          panel(R.id.panel_label, LABEL_PANEL_W, LABEL_PANEL_H, layer = false) {
            ComposeView(it).apply { setContent { LabelPill(label) } }
          },
          // HELD-PART: the verdict badge at the hand and the name pill above the pick marker (layers, like the window)
          panel(R.id.panel_verdict, com.agj.android.ui.VERDICT_PANEL_W, com.agj.android.ui.VERDICT_PANEL_H, layer = true) {
            ComposeView(it).apply { setContent { com.agj.android.ui.VerdictBadge(held.badge) } }
          },
          panel(R.id.panel_pick_label, LABEL_PANEL_W, LABEL_PANEL_H, layer = true) {
            ComposeView(it).apply { setContent { LabelPill(held.pickLabel) } }
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
    voiceCtl?.cancel()
    dump?.stop()
    vision.stop()
    world.stop()
    panel?.stop()
    socket?.close()
    socket = null
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
    private const val REQ_MIC = 2
    /** ACTION-COACH: the target ghost and its marks while the part is still being fetched (1 = bright). */
    private const val COACH_DIM = 0.3f
    const val MIC_PERMISSION = "android.permission.RECORD_AUDIO"
    /** Server events passed to [onGuidanceEvent] as they are (the typed ones are mapped in [perceptionListener]). */
    private val PASS_THROUGH_EVENTS = setOf("good", "action_complete", "wrong_part", "wrong_place")
    val CAMERA_PERMISSIONS =
        listOf("android.permission.CAMERA", "horizonos.permission.HEADSET_CAMERA", "com.oculus.permission.USE_SCENE")
  }
}
