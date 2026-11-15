// The room: one warm lamp over a low disc, a cool light from behind, a breath of fill, darkness everywhere else.
// Two scenes are drawn into one canvas each frame: the workshop (orbit camera) and, over it, the tray
// (the pieces still in the box at the top and the piece to pick up at the bottom), which never turns.
import {
  ACESFilmicToneMapping,
  AdditiveBlending,
  BufferAttribute,
  BufferGeometry,
  CircleGeometry,
  Color,
  ConeGeometry,
  CylinderGeometry,
  DirectionalLight,
  DoubleSide,
  Fog,
  Group,
  HemisphereLight,
  Mesh,
  MeshBasicMaterial,
  MeshStandardMaterial,
  PCFShadowMap,
  PerspectiveCamera,
  PlaneGeometry,
  PMREMGenerator,
  Points,
  Quaternion,
  Scene,
  ShaderMaterial,
  SpotLight,
  SRGBColorSpace,
  Vector3,
  WebGLRenderer,
  type Texture,
} from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'

export const FOV = 27
/** distance of the tray plane from its camera, metres */
export const TRAY_DEPTH = 0.25
/** the layer of the piece that is on its way between the tray and the assembly */
export const FLIGHT_LAYER = 1

const DISC_HEIGHT = 0.014
/** the radius the room was drawn for; `setFocus` scales the lamp rig, the fog and the floor from it */
const REFERENCE_RADIUS = 0.315

const WARM = '#ffd9ae' // the lamp
const COOL = '#9ecfff' // the brand azure, used here only as light

const ENV_ROOM = 0.42
const ENV_TRAY = 0.85
const HEMI_ROOM = 0.1
const HEMI_TRAY = 0.3
const TRAY_KEY = 1.25
const TRAY_RIM = 2.4
/** the fill, as irradiance at the disc; the lamp is 3.3 */
const FILL = 0.28

/** A night workshop to reflect: a warm softbox on the lamp side, a cool strip behind, a dim bench below. */
function buildEnvironment(renderer: WebGLRenderer): Texture {
  const env = new Scene()
  env.background = new Color(0x000000)
  const panel = (w: number, h: number, color: string, gain: number, at: [number, number, number]) => {
    const m = new Mesh(
      new PlaneGeometry(w, h),
      new MeshBasicMaterial({ color: new Color(color).multiplyScalar(gain), side: DoubleSide }),
    )
    m.position.set(...at)
    m.lookAt(0, 0, 0)
    env.add(m)
  }
  panel(3.4, 2.4, WARM, 10, [-3.2, 4.4, 3.0])
  panel(0.8, 5.5, COOL, 6, [4.6, 1.8, -3.6])
  panel(1.4, 1.4, '#fff0dc', 2.2, [2.2, 1.2, 5.0])
  panel(8, 8, '#3d2817', 0.45, [0, -3.2, 0])
  const pmrem = new PMREMGenerator(renderer)
  const texture = pmrem.fromScene(env, 0.03).texture
  pmrem.dispose()
  env.traverse((o) => {
    const m = o as Mesh
    if (m.isMesh) {
      m.geometry.dispose()
      ;(m.material as MeshBasicMaterial).dispose()
    }
  })
  return texture
}

export class Stage {
  readonly renderer: WebGLRenderer
  readonly scene = new Scene()
  readonly camera = new PerspectiveCamera(FOV, 1, 0.02, 30)
  readonly controls: OrbitControls
  /** drawn over the workshop with the same projection; its camera stays at the origin looking down -Z */
  readonly trayScene = new Scene()
  readonly trayCamera = new PerspectiveCamera(FOV, 1, 0.01, 4)
  /** lamp on the piece to pick up; `aimCrisp` moves it with the layout */
  readonly crispLamp = new SpotLight('#ffe6c6', 0.085, 0, 0.3, 1, 2)
  /** how bright that lamp is, 1 = as set: live guidance lifts it for a moment over the piece it is talking about */
  crispLevel = 1
  private crispBase = 0.085

  readonly key = new SpotLight(WARM, 46, 0, 0.3, 0.9, 2)
  private rim = new SpotLight(COOL, 16, 0, 0.3, 0.7, 2)
  /** a little warm light from the viewer's right, so a face turned away from the lamp is dark wood and not a hole */
  private fill = new SpotLight('#ffe9d2', 4, 0, 0.3, 1, 2)
  private hemi = new HemisphereLight('#ffe7cb', '#1b1209', HEMI_ROOM)
  /**
   * Lamp, rim light and dust hang on one rig that turns with the camera about the disc, as if the disc were a
   * turntable under fixed studio lights: from every side the lamp is up on the left and the rim behind on the right.
   */
  private rig = new Group()
  private centre = new Vector3()
  private disc: Mesh
  private floor: Mesh
  private dust: Points
  private beam: Mesh
  private dustUniforms = { uTime: { value: 0 }, uPixel: { value: 1 }, uReach: { value: 1 } }

  /**
   * A piece on its way between the tray and the assembly is drawn a second time, alone, with the tray's own light
   * added in this proportion (0..1). It leaves the tray looking exactly as it hung there and arrives under the lamp,
   * with no jump at either end. These two lights ride on the camera and are dark in the ordinary pass.
   */
  flight = 0
  private flightKey = new DirectionalLight(WARM, 0)
  private flightRim = new DirectionalLight(COOL, 0)
  private turnRig = new Quaternion()
  private turnEye = new Quaternion()

  width = 1
  height = 1
  /** how far below the middle of the screen the middle of the free area is, CSS pixels */
  shiftY = 0
  /** device pixels per CSS pixel the canvas is drawn at */
  ratio = 1
  private insets: [number, number] = [0, 0]
  private pixelBudget = 6.2e6
  private pace = { frames: 0, time: 0, strikes: 0 }

  constructor(readonly canvas: HTMLCanvasElement) {
    const r = (this.renderer = new WebGLRenderer({ canvas, antialias: true, alpha: false, powerPreference: 'high-performance' }))
    r.outputColorSpace = SRGBColorSpace
    r.toneMapping = ACESFilmicToneMapping
    r.toneMappingExposure = 1.0
    r.shadowMap.enabled = true
    r.shadowMap.type = PCFShadowMap
    r.autoClear = false
    r.setClearColor(0x000000, 1)

    const env = buildEnvironment(r)
    this.scene.environment = env
    this.scene.environmentIntensity = ENV_ROOM
    this.scene.fog = new Fog(0x000000, 3.2, 9)
    this.trayScene.environment = env
    this.trayScene.environmentIntensity = ENV_TRAY

    // ---- workshop lights: one shadow-casting lamp, one cool rim, a weak fill and a breath of sky
    this.key.castShadow = true
    this.key.shadow.mapSize.set(2048, 2048)
    this.key.shadow.bias = -0.0004
    this.key.shadow.normalBias = 0.003
    this.key.shadow.radius = 3.5
    this.rig.add(this.key, this.key.target, this.rim, this.rim.target, this.fill, this.fill.target)
    this.scene.add(this.rig, this.hemi)

    // ---- the ground: a low disc on a floor that falls off into the dark
    this.floor = new Mesh(new CircleGeometry(12, 72), new MeshStandardMaterial({ color: '#191512', roughness: 0.94, metalness: 0 }))
    this.floor.rotation.x = -Math.PI / 2
    this.floor.receiveShadow = true
    this.disc = new Mesh(
      new CylinderGeometry(1, 1, DISC_HEIGHT, 160, 1),
      new MeshStandardMaterial({ color: '#48423d', roughness: 0.8, metalness: 0 }),
    )
    this.disc.receiveShadow = true
    this.disc.castShadow = true
    this.scene.add(this.floor, this.disc)
    const daylight = new HemisphereLight(0xffffff, 0xdce5f0, 0)
    this.scene.add(daylight)
    const applyTheme = () => {
      const light = document.documentElement.dataset.theme === 'light'
      daylight.intensity = light ? 2.2 : 0
      r.setClearColor(light ? 0xf5f7fa : 0x000000, 1)
      ;(this.scene.fog as Fog).color.set(light ? 0xf5f7fa : 0x000000)
      ;(this.floor.material as MeshStandardMaterial).color.set(light ? '#e1e5eb' : '#191512')
      ;(this.disc.material as MeshStandardMaterial).color.set(light ? '#c5cbd3' : '#48423d')
    }
    applyTheme()
    new MutationObserver(applyTheme).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })


    // ---- dust in the beam
    this.dust = new Points(
      new BufferGeometry(),
      new ShaderMaterial({
        uniforms: this.dustUniforms,
        transparent: true,
        depthWrite: false,
        blending: AdditiveBlending,
        vertexShader: /* glsl */ `
          attribute vec4 aSeed; // phase, speed, size, brightness
          uniform float uTime;
          uniform float uPixel;
          uniform float uReach;
          varying float vGlow;
          void main() {
            vec3 p = position;
            float t = uTime * aSeed.y;
            p.x += sin(t * 0.37 + aSeed.x * 6.28) * 0.035 * uReach;
            p.z += cos(t * 0.29 + aSeed.x * 9.42) * 0.035 * uReach;
            p.y += sin(t * 0.21 + aSeed.x * 3.14) * 0.05 * uReach;
            vec4 mv = modelViewMatrix * vec4(p, 1.0);
            gl_Position = projectionMatrix * mv;
            gl_PointSize = aSeed.z * uPixel * uReach / max(0.3 * uReach, -mv.z);
            vGlow = aSeed.w * (0.65 + 0.35 * sin(t * 0.9 + aSeed.x * 20.0));
          }`,
        fragmentShader: /* glsl */ `
          varying float vGlow;
          void main() {
            float d = length(gl_PointCoord - 0.5) * 2.0;
            float a = exp(-d * d * 3.2) * (1.0 - smoothstep(0.8, 1.0, d)); // an out-of-focus mote, not a star
            gl_FragColor = vec4(vec3(1.0, 0.80, 0.56) * a * vGlow, 1.0);
          }`,
      }),
    )
    this.dust.frustumCulled = false
    this.rig.add(this.dust)

    // ---- the beam itself, barely there: what the dust hangs in
    this.beam = new Mesh(
      new ConeGeometry(1, 1, 48, 1, true),
      new ShaderMaterial({
        transparent: true,
        depthWrite: false,
        side: DoubleSide,
        blending: AdditiveBlending,
        vertexShader: /* glsl */ `
          varying vec3 vN;
          varying vec3 vV;
          varying float vAlong;
          void main() {
            vec4 mv = modelViewMatrix * vec4(position, 1.0);
            vN = normalize(normalMatrix * normal);
            vV = normalize(-mv.xyz);
            vAlong = 0.5 - position.y; // 0 at the lamp, 1 at the disc
            gl_Position = projectionMatrix * mv;
          }`,
        fragmentShader: /* glsl */ `
          varying vec3 vN;
          varying vec3 vV;
          varying float vAlong;
          void main() {
            float facing = pow(abs(dot(normalize(vN), normalize(vV))), 2.6);
            float a = facing * smoothstep(0.42, 0.98, vAlong) * 0.028;
            gl_FragColor = vec4(vec3(1.0, 0.82, 0.6) * a, 1.0);
          }`,
      }),
    )
    this.beam.frustumCulled = false
    this.rig.add(this.beam)

    // ---- tray lights: the stock waits in half-light, the piece to pick up sits under its own small lamp
    const trayKey = new DirectionalLight(WARM, TRAY_KEY)
    trayKey.position.set(-0.5, 1, 0.9)
    const trayRim = new DirectionalLight(COOL, TRAY_RIM)
    trayRim.position.set(0.7, 0.5, -1)
    this.trayScene.add(trayKey, trayRim, new HemisphereLight('#ffe7cb', '#2a1b0e', HEMI_TRAY))
    this.crispLamp.penumbra = 1
    this.trayScene.add(this.crispLamp, this.crispLamp.target)

    // ---- the same two tray lights, riding on the workshop camera, for the piece in flight
    this.flightKey.position.copy(trayKey.position)
    this.flightRim.position.copy(trayRim.position)
    this.camera.add(this.flightKey, this.flightKey.target, this.flightRim, this.flightRim.target)
    for (const light of [this.key, this.rim, this.fill, this.hemi, this.flightKey, this.flightRim]) light.layers.enable(FLIGHT_LAYER)

    this.scene.add(this.camera)
    this.camera.position.set(1.4, 1.0, 1.9)
    this.controls = new OrbitControls(this.camera, canvas)
    this.controls.enableDamping = true
    this.controls.dampingFactor = 0.09
    this.controls.rotateSpeed = 0.85
    this.controls.zoomSpeed = 0.8
    this.controls.enablePan = false
    this.controls.maxPolarAngle = Math.PI / 2 - 0.035 // never under the floor

    this.setFocus(new Vector3(0.22, 0, -0.15), REFERENCE_RADIUS)
  }

  /**
   * Put the disc, the lamp and the dust around the place where the assembly stands. `centre.y` is the floor.
   * The room is sized from `radius`, so a wardrobe gets a higher lamp and a wider floor than a crate.
   */
  setFocus(centre: Vector3, radius: number): void {
    const s = Math.min(12, Math.max(0.3, radius / REFERENCE_RADIUS))
    this.centre.copy(centre)
    this.disc.scale.set(radius, s, radius)
    this.disc.position.set(centre.x, centre.y - (DISC_HEIGHT * s) / 2, centre.z)
    this.floor.scale.setScalar(s)
    this.floor.position.set(centre.x, centre.y - DISC_HEIGHT * s, centre.z)
    this.rig.position.copy(centre)

    const fog = this.scene.fog as Fog
    fog.near = 3.2 * s
    fog.far = 9 * s
    this.camera.near = Math.min(0.1, 0.02 * Math.max(1, s)) // a piece leaving the tray is 0.25 m from the eye
    this.camera.far = 30 * s
    this.camera.updateProjectionMatrix()
    this.controls.minDistance = radius * 1.5
    this.controls.maxDistance = radius * 18

    // rig coordinates: +Z towards the camera, +X to its right
    const lamp = new Vector3(-1.55, 2.5, 1.3).multiplyScalar(s)
    const aim = new Vector3(0, 0.1 * s, 0)
    this.key.position.copy(lamp)
    this.key.target.position.copy(aim)
    const reach = lamp.distanceTo(aim)
    this.key.angle = Math.atan((radius * 1.5) / reach)
    this.key.intensity = 3.3 * reach * reach
    this.key.shadow.camera.near = 1.2 * s
    this.key.shadow.camera.far = 6 * s
    this.key.shadow.bias = -0.0004 * s
    this.key.shadow.normalBias = 0.003 * s
    this.rim.position.set(1.7, 1.2, -2.2).multiplyScalar(s)
    this.rim.target.position.copy(aim)
    const rimReach = this.rim.position.distanceTo(aim)
    this.rim.angle = Math.atan((radius * 1.6) / rimReach) // a spot, so the cool light stays off the far floor
    this.rim.intensity = 1.9 * rimReach * rimReach
    this.fill.position.set(2.0, 1.0, 1.9).multiplyScalar(s)
    this.fill.target.position.copy(aim)
    const fillReach = this.fill.position.distanceTo(aim)
    this.fill.angle = Math.atan((radius * 1.5) / fillReach) // a spot as well: the floor beyond the disc stays dark
    this.fill.intensity = FILL * fillReach * fillReach

    const beamRadius = Math.tan(this.key.angle) * reach * 0.86
    this.beam.scale.set(beamRadius, reach, beamRadius)
    this.beam.position.copy(lamp).add(aim).multiplyScalar(0.5)
    this.beam.quaternion.setFromUnitVectors(new Vector3(0, 1, 0), lamp.clone().sub(aim).normalize())

    // motes scattered inside the cone of the lamp, brighter near its axis
    const n = 70
    const pos = new Float32Array(n * 3)
    const seed = new Float32Array(n * 4)
    const axis = aim.clone().sub(lamp).normalize()
    const side = new Vector3().crossVectors(axis, new Vector3(0, 1, 0)).normalize()
    const up = new Vector3().crossVectors(side, axis)
    const spread = Math.tan(this.key.angle)
    let k = 7
    const rnd = () => ((k = (k * 16807) % 2147483647) - 1) / 2147483646
    for (let i = 0; i < n; i++) {
      const along = reach * (0.76 + 0.2 * rnd())
      const rr = Math.pow(rnd(), 0.8) * spread * along * 0.55
      const a = rnd() * Math.PI * 2
      const p = lamp.clone().addScaledVector(axis, along).addScaledVector(side, Math.cos(a) * rr).addScaledVector(up, Math.sin(a) * rr)
      if (p.y < 0.08 * s) p.y = (0.08 + rnd() * 0.5) * s
      pos.set([p.x, p.y, p.z], i * 3)
      const edge = 1 - rr / (spread * along)
      seed.set([rnd(), 0.5 + rnd(), 9 + rnd() * 16, (0.022 + 0.05 * rnd()) * Math.min(1, edge * 2.2)], i * 4)
    }
    this.dust.geometry.dispose()
    const g = new BufferGeometry()
    g.setAttribute('position', new BufferAttribute(pos, 3))
    g.setAttribute('aSeed', new BufferAttribute(seed, 4))
    this.dust.geometry = g
    this.dustUniforms.uReach.value = s
  }

  /** Point the small lamp at the piece to pick up (tray coordinates). */
  aimCrisp(at: Vector3, reach: number): void {
    this.crispLamp.position.set(at.x - reach * 0.6, at.y + reach * 1.0, at.z + reach * 1.1)
    this.crispLamp.target.position.copy(at)
    const d = this.crispLamp.position.distanceTo(at)
    this.crispLamp.angle = Math.atan((reach * 1.25) / d)
    this.crispBase = 5.4 * d * d
    this.crispLamp.intensity = this.crispBase * this.crispLevel
  }

  /** `insetTop` and `insetBottom` are the bands the tray covers; the assembly is centred between them. */
  resize(width: number, height: number, insetTop: number, insetBottom: number): void {
    this.width = width
    this.height = height
    this.insets = [insetTop, insetBottom]
    this.shiftY = (insetTop - insetBottom) / 2
    const dpr = window.devicePixelRatio || 1
    // full device resolution up to 3x, within a budget of pixels that a phone GPU holds at 60 fps
    this.ratio = Math.min(dpr, 3, Math.sqrt(this.pixelBudget / (width * height)))
    this.renderer.setPixelRatio(this.ratio)
    this.renderer.setSize(width, height, false)
    this.dustUniforms.uPixel.value = this.ratio * (height / 864)
    for (const cam of [this.camera, this.trayCamera]) {
      cam.aspect = width / height
      cam.setViewOffset(width, height, 0, -this.shiftY, width, height)
      cam.updateProjectionMatrix()
    }
  }

  /**
   * Watch the frame time. A device that stays under 27 frames a second at this resolution for two windows of sixty
   * frames gets fewer pixels, down to one device pixel per CSS pixel. It never goes back up. The line is under 30
   * on purpose: a phone that saves power draws 30 frames a second however little there is to draw.
   */
  watchPace(dt: number): void {
    if (dt <= 0 || dt > 0.25 || document.hidden) return // a hidden tab or a breakpoint is not a slow device
    const p = this.pace
    p.frames++
    p.time += dt
    if (p.frames < 60) return
    const slow = p.time / p.frames > 1 / 27
    p.frames = 0
    p.time = 0
    p.strikes = slow ? p.strikes + 1 : 0
    if (p.strikes < 2 || this.ratio <= 1) return
    p.strikes = 0
    this.pixelBudget = Math.max(this.width * this.height, this.ratio * this.ratio * this.width * this.height * 0.6)
    this.resize(this.width, this.height, this.insets[0], this.insets[1])
  }

  /** Metres at the tray plane per CSS pixel. */
  get trayUnit(): number {
    return (2 * TRAY_DEPTH * Math.tan((FOV * Math.PI) / 360)) / this.height
  }

  /** A point of the screen (CSS pixels) on the tray plane. */
  trayPoint(x: number, y: number, out = new Vector3()): Vector3 {
    const u = this.trayUnit
    return out.set((x - this.width / 2) * u, (this.height / 2 + this.shiftY - y) * u, -TRAY_DEPTH)
  }

  /** A point of the workshop on the screen, CSS pixels. `z` above 1 means it is behind the far plane or the eye. */
  toScreen(world: Vector3, out: { x: number; y: number; z: number }): void {
    const v = this.scratch.copy(world).project(this.camera)
    out.x = ((v.x + 1) / 2) * this.width
    out.y = ((1 - v.y) / 2) * this.height
    out.z = v.z
  }
  private scratch = new Vector3()

  /** Build every shader program now, hidden pieces included, so that nothing has to be compiled in the middle of a drag. */
  warm(): void {
    this.renderer.compile(this.scene, this.camera)
    this.renderer.compile(this.trayScene, this.trayCamera)
  }

  draw(time: number, drift: boolean): void {
    if (drift) this.dustUniforms.uTime.value = time
    this.rig.rotation.y = Math.atan2(this.camera.position.x - this.centre.x, this.camera.position.z - this.centre.z)
    this.scene.environmentRotation.y = this.rig.rotation.y // the reflected softbox stays on the lamp's side
    const r = this.renderer
    r.clear()
    r.render(this.scene, this.camera)

    const w = this.flight
    if (w > 0.004) {
      // the piece in flight once more, over itself, with the tray's light added; the shadow map is kept
      this.flightKey.intensity = TRAY_KEY * w
      this.flightRim.intensity = TRAY_RIM * w
      this.hemi.intensity = HEMI_ROOM + (HEMI_TRAY - HEMI_ROOM) * w
      this.scene.environmentIntensity = ENV_ROOM + (ENV_TRAY - ENV_ROOM) * w
      // in the tray the reflected room is fixed to the eye; in the workshop it turns with the lamp
      this.turnRig.setFromEuler(this.rig.rotation)
      this.scene.environmentRotation.setFromQuaternion(this.turnRig.slerp(this.camera.getWorldQuaternion(this.turnEye), w))
      r.shadowMap.autoUpdate = false
      this.camera.layers.set(FLIGHT_LAYER)
      r.render(this.scene, this.camera)
      this.camera.layers.set(0)
      r.shadowMap.autoUpdate = true
      this.flightKey.intensity = 0
      this.flightRim.intensity = 0
      this.hemi.intensity = HEMI_ROOM
      this.scene.environmentIntensity = ENV_ROOM
      this.scene.environmentRotation.set(0, this.rig.rotation.y, 0)
    }

    r.clearDepth()
    this.crispLamp.intensity = this.crispBase * this.crispLevel
    r.render(this.trayScene, this.trayCamera)
  }
}
