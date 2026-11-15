// The room: one warm lamp over a low disc, a cool light from behind, darkness everywhere else.
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
const DISC_HEIGHT = 0.014

const WARM = '#ffd9ae' // the lamp
const COOL = '#9ecfff' // the brand azure, used here only as light

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

  readonly key = new SpotLight(WARM, 46, 0, 0.3, 0.9, 2)
  private rim = new SpotLight(COOL, 16, 0, 0.3, 0.7, 2)
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
  private dustUniforms = { uTime: { value: 0 }, uPixel: { value: 1 } }
  width = 1
  height = 1
  /** how far below the middle of the screen the middle of the free area is, CSS pixels */
  shiftY = 0

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
    this.scene.environmentIntensity = 0.42
    this.scene.fog = new Fog(0x000000, 3.2, 9)
    this.trayScene.environment = env
    this.trayScene.environmentIntensity = 0.85

    // ---- workshop lights: one shadow-casting lamp, one cool rim, a breath of fill
    this.key.castShadow = true
    this.key.shadow.mapSize.set(2048, 2048)
    this.key.shadow.bias = -0.0004
    this.key.shadow.normalBias = 0.003
    this.key.shadow.radius = 3.5
    this.key.shadow.camera.near = 1.2
    this.key.shadow.camera.far = 6
    this.rig.add(this.key, this.key.target, this.rim, this.rim.target)
    this.scene.add(this.rig, new HemisphereLight('#ffe7cb', '#1b1209', 0.1))

    // ---- the ground: a low disc on a floor that falls off into the dark
    this.floor = new Mesh(new CircleGeometry(12, 72), new MeshStandardMaterial({ color: '#191512', roughness: 0.94, metalness: 0 }))
    this.floor.rotation.x = -Math.PI / 2
    this.floor.position.y = -DISC_HEIGHT
    this.floor.receiveShadow = true
    this.disc = new Mesh(
      new CylinderGeometry(1, 1, DISC_HEIGHT, 160, 1),
      new MeshStandardMaterial({ color: '#48423d', roughness: 0.8, metalness: 0 }),
    )
    this.disc.position.y = -DISC_HEIGHT / 2
    this.disc.receiveShadow = true
    this.disc.castShadow = true
    this.scene.add(this.floor, this.disc)

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
          varying float vGlow;
          void main() {
            vec3 p = position;
            float t = uTime * aSeed.y;
            p.x += sin(t * 0.37 + aSeed.x * 6.28) * 0.035;
            p.z += cos(t * 0.29 + aSeed.x * 9.42) * 0.035;
            p.y += sin(t * 0.21 + aSeed.x * 3.14) * 0.05;
            vec4 mv = modelViewMatrix * vec4(p, 1.0);
            gl_Position = projectionMatrix * mv;
            gl_PointSize = aSeed.z * uPixel / max(0.3, -mv.z);
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
    const trayKey = new DirectionalLight(WARM, 1.25)
    trayKey.position.set(-0.5, 1, 0.9)
    const trayRim = new DirectionalLight(COOL, 2.4)
    trayRim.position.set(0.7, 0.5, -1)
    this.trayScene.add(trayKey, trayRim, new HemisphereLight('#ffe7cb', '#2a1b0e', 0.3))
    this.crispLamp.penumbra = 1
    this.trayScene.add(this.crispLamp, this.crispLamp.target)

    this.scene.add(this.camera)
    this.camera.position.set(1.4, 1.0, 1.9)
    this.controls = new OrbitControls(this.camera, canvas)
    this.controls.enableDamping = true
    this.controls.dampingFactor = 0.09
    this.controls.rotateSpeed = 0.85
    this.controls.zoomSpeed = 0.8
    this.controls.enablePan = false
    this.controls.minDistance = 0.5
    this.controls.maxDistance = 4.5
    this.controls.maxPolarAngle = Math.PI / 2 - 0.035 // never under the floor

    this.setFocus(new Vector3(0.22, 0, -0.15), 0.46)
  }

  /** Put the disc, the lamp and the dust around the place where the assembly stands. */
  setFocus(centre: Vector3, radius: number): void {
    this.centre.copy(centre)
    this.disc.scale.set(radius, 1, radius)
    this.disc.position.set(centre.x, -DISC_HEIGHT / 2, centre.z)
    this.floor.position.set(centre.x, -DISC_HEIGHT, centre.z)
    this.rig.position.set(centre.x, 0, centre.z)

    // rig coordinates: +Z towards the camera, +X to its right
    const lamp = new Vector3(-1.55, 2.5, 1.3)
    const aim = new Vector3(0, 0.1, 0)
    this.key.position.copy(lamp)
    this.key.target.position.copy(aim)
    const reach = lamp.distanceTo(aim)
    this.key.angle = Math.atan((radius * 1.5) / reach)
    this.key.intensity = 3.3 * reach * reach
    this.rim.position.set(1.7, 1.2, -2.2)
    this.rim.target.position.copy(aim)
    const rimReach = this.rim.position.distanceTo(aim)
    this.rim.angle = Math.atan((radius * 1.6) / rimReach) // a spot, so the cool light stays off the far floor
    this.rim.intensity = 1.9 * rimReach * rimReach

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
    let s = 7
    const rnd = () => ((s = (s * 16807) % 2147483647) - 1) / 2147483646
    for (let i = 0; i < n; i++) {
      const along = reach * (0.76 + 0.2 * rnd())
      const rr = Math.pow(rnd(), 0.8) * spread * along * 0.55
      const a = rnd() * Math.PI * 2
      const p = lamp.clone().addScaledVector(axis, along).addScaledVector(side, Math.cos(a) * rr).addScaledVector(up, Math.sin(a) * rr)
      if (p.y < 0.08) p.y = 0.08 + rnd() * 0.5
      pos.set([p.x, p.y, p.z], i * 3)
      const edge = 1 - rr / (spread * along)
      seed.set([rnd(), 0.5 + rnd(), 9 + rnd() * 16, (0.022 + 0.05 * rnd()) * Math.min(1, edge * 2.2)], i * 4)
    }
    this.dust.geometry.dispose()
    const g = new BufferGeometry()
    g.setAttribute('position', new BufferAttribute(pos, 3))
    g.setAttribute('aSeed', new BufferAttribute(seed, 4))
    this.dust.geometry = g
  }

  /** Point the small lamp at the piece to pick up (tray coordinates). */
  aimCrisp(at: Vector3, reach: number): void {
    this.crispLamp.position.set(at.x - reach * 0.6, at.y + reach * 1.0, at.z + reach * 1.1)
    this.crispLamp.target.position.copy(at)
    const d = this.crispLamp.position.distanceTo(at)
    this.crispLamp.angle = Math.atan((reach * 1.25) / d)
    this.crispLamp.intensity = 5.4 * d * d
  }

  /** `insetTop` and `insetBottom` are the bands the tray covers; the assembly is centred between them. */
  resize(width: number, height: number, insetTop: number, insetBottom: number): void {
    this.width = width
    this.height = height
    this.shiftY = (insetTop - insetBottom) / 2
    const dpr = window.devicePixelRatio || 1
    // full device resolution up to 3x, within a budget that a phone GPU holds at 60 fps
    const ratio = Math.min(dpr, 3, Math.sqrt(6.2e6 / (width * height)))
    this.renderer.setPixelRatio(ratio)
    this.renderer.setSize(width, height, false)
    this.dustUniforms.uPixel.value = ratio * (height / 864)
    for (const cam of [this.camera, this.trayCamera]) {
      cam.aspect = width / height
      cam.setViewOffset(width, height, 0, -this.shiftY, width, height)
      cam.updateProjectionMatrix()
    }
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

  draw(time: number, drift: boolean): void {
    if (drift) this.dustUniforms.uTime.value = time
    this.rig.rotation.y = Math.atan2(this.camera.position.x - this.centre.x, this.camera.position.z - this.centre.z)
    const r = this.renderer
    r.clear()
    r.render(this.scene, this.camera)
    r.clearDepth()
    r.render(this.trayScene, this.trayCamera)
  }
}
