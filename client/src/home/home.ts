import { appLink } from '../routes'
// The workbench: where the app starts (no mode, no job) and where a finished guide waits (`?mode=ready&job=<id>`).
// One lamp over a bench, and a shelf of flat-pack boxes, one per guide the server can open at once (GET /api/catalog).
// A box taken from the shelf, a code typed or scanned, or a guide found by name opens the ready screen: the real
// model on the bench and three ways on: the steps on this screen, building with the camera's help, or the Quest.
// A guide that does not exist yet is built by the build screen (`?mode=build`).
//
//   (no mode, no job)        the shelf
//   ?mode=ready&job=<id>     that guide, ready
//   &key=<demo key>  &lang=en  &quest=1 (show the Quest choice even where WebXR cannot be checked)
import '../styles/home.css'
import {
  ACESFilmicToneMapping, Box3, BoxGeometry, CanvasTexture, Color, CylinderGeometry, DirectionalLight, Euler, Fog, Group,
  HemisphereLight, MathUtils, Mesh, MeshStandardMaterial, Object3D, PCFShadowMap, PerspectiveCamera, PlaneGeometry, RepeatWrapping,
  AnimationMixer, LoopOnce, PointLight, Quaternion, Raycaster, SRGBColorSpace, Scene, SpotLight, Texture, Vector2, Vector3, WebGLRenderer,
} from 'three'
import { apiFetch, fileUrl } from '../api'
import type { Lang, Manifest } from '../types'
import { loadGltf } from '../viewer/model'
import { choose, sendGuide, type Chosen } from '../build/intake'
import { bw } from '../build/words'

interface Item {
  product: string
  guide_id?: string | null
  article?: string | null
  job_id?: string | null
  steps?: number | null
  ready?: boolean
  source?: string
}

const WORDS = {
  el: {
    title: 'Jarvis Assembler', pdf: 'Διάλεξε το PDF των οδηγιών', sending: 'Στέλνω τον οδηγό', wCam: 'Κάμερα', wCode: 'Κωδικός', wPdf: 'PDF', ways: 'Τρόπος εύρεσης',
    codeHint: 'Ο κωδικός είναι τυπωμένος στην ετικέτα του κουτιού', pdfHint: 'Ένα PDF ή φωτογραφίες των σελίδων', scan: 'Σκάναρε τον κωδικό', upload: 'Ανέβασε τον οδηγό', find: 'Όνομα ή κωδικός',
    findAria: 'Αναζήτηση', back: 'Πίσω', other: 'en', otherAria: 'English', go: 'Βρες',
    aim: 'Στόχευσε τον κωδικό στο κουτί', type: 'Δεν έχει κωδικό; Φωτογράφισε το κουτί', looking: 'Ψάχνω', noCamera: 'Φωτογράφισε το κουτί',
    photo: 'Φωτογράφισε το κουτί', unsure: 'Δεν αναγνώρισα το προϊόν. Δοκίμασε τον κωδικό ή το όνομα.',
    noKey: 'Λείπει ο κωδικός πρόσβασης από τη διεύθυνση (?key=).', fromIkea: 'Από την IKEA',
    notFound: 'Δεν βρήκα αυτόν τον οδηγό. Ανέβασέ τον.', several: 'Ταιριάζουν πολλά. Γράψε πιο συγκεκριμένα.', offline: 'Ο server δεν απαντά. Ξαναδοκίμασε.',
    steps: 'Δες τα βήματα', help: 'Συναρμολόγησε με βοήθεια', quest: 'Άνοιξε στο Quest',
    nsteps: (n: number) => (n === 1 ? '1 βήμα' : `${n} βήματα`), loading: 'Φέρνω το έπιπλο',
    flags: (n: number) => (n === 1 ? '1 σημείο να ελέγξεις' : `${n} σημεία να ελέγξεις`), empty: 'Κανένας οδηγός ακόμα',
  },
  en: {
    title: 'Jarvis Assembler', pdf: 'Choose the guide PDF', sending: 'Sending the guide', wCam: 'Camera', wCode: 'Code', wPdf: 'PDF', ways: 'How to find it',
    codeHint: 'The code is printed on the label of the box', pdfHint: 'A PDF, or photos of the pages', scan: 'Scan the code', upload: 'Upload the guide', find: 'Name or code',
    findAria: 'Search', back: 'Back', other: 'ελ', otherAria: 'Ελληνικά', go: 'Find',
    aim: 'Point at the code on the box', type: 'No code on it? Photograph the box', looking: 'Looking', noCamera: 'Photograph the box',
    photo: 'Photograph the box', unsure: 'I could not tell which product this is. Try the code or the name.',
    noKey: 'The access key is missing from the address (?key=).', fromIkea: 'From IKEA',
    notFound: 'I could not find that guide. Upload it.', several: 'Several match. Be more specific.', offline: 'The server does not answer. Try again.',
    steps: 'See the steps', help: 'Build with help', quest: 'Open in Quest',
    nsteps: (n: number) => (n === 1 ? '1 step' : `${n} steps`), loading: 'Bringing the furniture',
    flags: (n: number) => (n === 1 ? '1 thing to check' : `${n} things to check`), empty: 'No guides yet',
  },
}
type Words = (typeof WORDS)['el']

const ICON = {
  find: '<circle cx="10.5" cy="10.5" r="6"/><path d="m15 15 5 5"/>',
  back: '<path d="M14.5 5.5 8 12l6.5 6.5"/>',
  scan: '<path d="M4 8V5.5C4 4.7 4.7 4 5.5 4H8M16 4h2.5c.8 0 1.5.7 1.5 1.5V8M20 16v2.5c0 .8-.7 1.5-1.5 1.5H16M8 20H5.5C4.7 20 4 19.3 4 18.5V16M7 12h10"/>',
  cube: '<path d="M12 3 20 7.5v9L12 21l-8-4.5v-9z"/><path d="M12 12 20 7.5M12 12v9M12 12 4 7.5"/>',
  phone: '<rect x="7" y="2.5" width="10" height="15" rx="2"/><path d="M10 21.5h4M12 17.5v4"/>',
  quest: '<path d="M3.5 9.5c0-1.7 1.3-3 3-3h11c1.7 0 3 1.3 3 3v4.5c0 1.7-1.3 3-3 3h-2.6c-.8 0-1.4-.4-1.8-1.1l-.4-.8c-.4-.8-1.2-.8-1.6 0l-.4.8c-.4.7-1 1.1-1.8 1.1H6.5c-1.7 0-3-1.3-3-3z"/>',
  go: '<path d="M5 12h13M13 6.5 18.5 12 13 17.5"/>',
  pdf: '<path d="M6 3h8l4 4v14H6zM14 3v4h4"/>',
  shot: '<path d="M4 8.5h3.2l1.4-2h6.8l1.4 2H20v10H4z"/><circle cx="12" cy="13.2" r="3.2"/>',
  warn: '<path d="M12 3.5 21.5 20h-19z"/><path d="M12 10v4.5M12 17.2v.1"/>',
}
const svg = (p: string) => `<svg viewBox="0 0 24 24" aria-hidden="true">${p}</svg>`

/** "KNAGGLIG box" → "KNAGGLIG": the name printed on the box is the product's own word. */
const boxName = (product: string) => (product.split(/\s+/)[0] || product).toUpperCase()
/** IKEA article numbers are printed "902.891.72". */
const boxCode = (it: Item) =>
  it.article && /^\d{8}$/.test(it.article) ? `${it.article.slice(0, 3)}.${it.article.slice(3, 6)}.${it.article.slice(6)}` : (it.guide_id ?? '')

export function startHome(app: HTMLElement): void {
  const q = new URLSearchParams(location.search)
  const key = q.get('key')
  let lang: Lang = q.get('lang') === 'en' ? 'en' : 'el'
  const w = (): Words => WORDS[lang] as Words
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches

  // ------------------------------------------------------------------ the room's markup
  const root = document.createElement('main')
  root.id = 'home'
  root.innerHTML = `
    <canvas class="view" aria-hidden="true"></canvas>
    <div class="shade"></div>
    <div class="cam" aria-hidden="true"><video playsinline muted></video><div class="brackets"><i></i><i></i><i></i><i></i></div></div>
    <div class="shelf-list" role="list"></div>
    <button class="round find" type="button">${svg(ICON.find)}</button>
    <button class="round back hide" type="button">${svg(ICON.back)}</button>
    <div class="searchbar hide"><label class="sr" for="home-q"></label><input id="home-q" class="q" type="search" autocomplete="off" enterkeyhint="go" /></div>
    <button class="hit hide" type="button"></button>
    <ul class="results hide" role="listbox"></ul>
    <button class="lang" type="button"></button>
    <div class="foot" data-for="home">
      <p class="line empty" hidden></p>
      <button class="pill go-scan" type="button">${svg(ICON.scan)}<span></span></button>
      <button class="quiet go-upload" type="button"></button>
    </div>
    <div class="foot" data-for="scan" data-way="cam" hidden>
      <p class="line scanline" aria-live="polite"></p>
      <p class="meta scanhint"></p>
      <button class="round shot" type="button">${svg(ICON.shot)}</button>
      <form class="codefield"><label class="sr" for="home-code"></label><input id="home-code" autocomplete="off" enterkeyhint="go" />
        <button class="round" type="submit">${svg(ICON.go)}</button></form>
      <button class="pill go-pdf" type="button">${svg(ICON.pdf)}<span></span></button>
      <input class="shotfile" type="file" accept="image/*" capture="environment" hidden />
      <input class="pdffile" type="file" accept="application/pdf,image/*" multiple hidden />
      <div class="seg" role="group">
        <button type="button" data-way="cam" aria-pressed="true">${svg(ICON.scan)}<span></span></button>
        <button type="button" data-way="code" aria-pressed="false">${svg(ICON.find)}<span></span></button>
        <button type="button" data-way="pdf" aria-pressed="false">${svg(ICON.pdf)}<span></span></button>
      </div>
    </div>
    <div class="foot" data-for="ready" hidden>
      <p class="line readyname"></p>
      <p class="meta warn flagline" hidden></p>
      <a class="pill r-steps">${svg(ICON.cube)}<span></span></a>
      <a class="ghost r-help">${svg(ICON.phone)}<span></span></a>
      <a class="ghost xr r-quest" hidden>${svg(ICON.quest)}<span></span></a>
    </div>
    <p class="sr status" role="status"></p>`
  app.append(root)
  const $ = <T extends Element>(sel: string) => root.querySelector(sel) as T
  const canvas = $<HTMLCanvasElement>('canvas.view')
  const status = $<HTMLParagraphElement>('.status')

  // ------------------------------------------------------------------ the scene
  const renderer = new WebGLRenderer({ canvas, antialias: true })
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2))
  renderer.outputColorSpace = SRGBColorSpace
  renderer.toneMapping = ACESFilmicToneMapping
  renderer.toneMappingExposure = 0.92
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = PCFShadowMap
  const scene = new Scene()
  scene.background = new Color(0x000000)
  scene.fog = new Fog(0x000000, 3.2, 6.5)
  const applyTheme = () => {
    const color = document.documentElement.dataset.theme === 'light' ? 0xf5f7fa : 0x000000
    ;(scene.background as Color).set(color)
    ;(scene.fog as Fog).color.set(color)
  }
  applyTheme()
  new MutationObserver(applyTheme).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })

  const camera = new PerspectiveCamera(30, 1, 0.02, 30)

  const rng = (seed: number) => () => ((seed = (seed * 16807) % 2147483647) - 1) / 2147483646
  const woodTexture = (base: string, seed: number, wd = 1024, ht = 512, lines = 140): Texture => {
    const c = document.createElement('canvas')
    c.width = wd
    c.height = ht
    const g = c.getContext('2d')!
    const r = rng(seed)
    g.fillStyle = base
    g.fillRect(0, 0, wd, ht)
    for (let i = 0; i < lines; i++) {
      const y = r() * ht
      const a = 0.03 + r() * 0.09
      g.strokeStyle = r() > 0.4 ? `rgba(30,16,6,${a})` : `rgba(255,220,170,${a * 0.6})`
      g.lineWidth = 0.6 + r() * 2.4
      g.beginPath()
      g.moveTo(0, y)
      for (let x = 0; x <= wd; x += 32) g.lineTo(x, y + Math.sin(x * 0.004 + i) * 6 + (r() - 0.5) * 1.5)
      g.stroke()
    }
    const t = new CanvasTexture(c)
    t.colorSpace = SRGBColorSpace
    t.anisotropy = 4
    return t
  }

  const bench = new Mesh(new BoxGeometry(2.6, 0.06, 1.5), new MeshStandardMaterial({ map: woodTexture('#4a2f1a', 7), roughness: 0.72 }))
  bench.position.set(0, -0.03, 0.1)
  bench.receiveShadow = true
  const wall = new Mesh(new PlaneGeometry(6, 3), new MeshStandardMaterial({ color: 0x2a2019, roughness: 1 }))
  wall.position.set(0, 1.2, -0.62)
  wall.receiveShadow = true
  const shelf = new Mesh(new BoxGeometry(1.5, 0.03, 0.34), new MeshStandardMaterial({ map: woodTexture('#6b4828', 3, 512, 256, 60), roughness: 0.7 }))
  shelf.position.set(0, 0.34, -0.47)
  shelf.castShadow = shelf.receiveShadow = true
  scene.add(bench, wall, shelf)
  const daylight = new HemisphereLight(0xffffff, 0xdce5f0, 0)
  scene.add(daylight)
  const lightRoom = () => {
    const light = document.documentElement.dataset.theme === 'light'
    daylight.intensity = light ? 2.5 : 0
    wall.material.color.set(light ? 0xe1e5eb : 0x2a2019)
  }
  lightRoom()
  new MutationObserver(lightRoom).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })

  scene.add(new HemisphereLight(0x3a2a1c, 0x050403, 0.22))
  const lamp = new SpotLight(0xffd9ae, 26, 4, 0.34, 0.8, 1.4)
  lamp.position.set(-0.2, 1.7, 0.45)
  lamp.target.position.set(0.02, 0, 0.02)
  lamp.castShadow = true
  lamp.shadow.mapSize.set(1024, 1024)
  lamp.shadow.bias = -0.0004
  lamp.shadow.radius = 4
  const rim = new DirectionalLight(0x9ecfff, 0.5)
  rim.position.set(1.2, 0.9, -1.4)
  const fill = new PointLight(0xffe2c0, 0.25, 3, 2)
  fill.position.set(0.6, 0.5, 1.4)
  const spill = new SpotLight(0xffd2a0, 4, 3, 0.5, 1, 1.5) // the shelf gets the lamp's spill while you choose
  spill.position.set(0.3, 1.3, 0.3)
  spill.target.position.set(0, 0.42, -0.46)
  scene.add(lamp, lamp.target, rim, fill, spill, spill.target)

  { // a hex key and three screws on the bench
    const steel = new MeshStandardMaterial({ color: 0x8a8d92, roughness: 0.35, metalness: 0.8 })
    const hexKey = new Group()
    const a = new Mesh(new CylinderGeometry(0.0035, 0.0035, 0.09, 6), steel)
    a.rotation.z = Math.PI / 2
    a.position.set(0, 0.0035, 0)
    const b = new Mesh(new CylinderGeometry(0.0035, 0.0035, 0.03, 6), steel)
    b.rotation.x = Math.PI / 2
    b.position.set(0.045, 0.0035, 0.015)
    hexKey.add(a, b)
    hexKey.position.set(0.36, 0, -0.1)
    hexKey.rotation.y = 0.5
    hexKey.traverse((o) => (o.castShadow = true))
    scene.add(hexKey)
    const brass = new MeshStandardMaterial({ color: 0xefd176, roughness: 0.32, metalness: 0.62 })
    for (const [x, z, r] of [[0.42, -0.16, 0.2], [0.44, -0.13, 1.4], [0.39, -0.135, 2.6]]) {
      const sc = new Mesh(new CylinderGeometry(0.004, 0.004, 0.035, 8), brass)
      sc.rotation.set(Math.PI / 2, 0, r)
      sc.position.set(x, 0.004, z)
      sc.castShadow = true
      scene.add(sc)
    }
  }

  // ------------------------------------------------------------------ the ready screen: the spot where it was just unpacked
  // A wooden floor instead of the bench and its shelf, and around the furniture what unpacking leaves behind: the
  // opened carton with one flap torn off, foam blocks, scraps of cardboard, the bag of screws, the instruction sheet.
  // No product names here: the shelf of boxes belongs to the home screen.
  const floorMap = woodTexture('#5b3a20', 11, 1024, 1024, 220)
  floorMap.wrapS = floorMap.wrapT = RepeatWrapping
  floorMap.repeat.set(3, 3)
  const floor = new Mesh(new PlaneGeometry(9, 9), new MeshStandardMaterial({ map: floorMap, roughness: 0.78 }))
  floor.rotation.x = -Math.PI / 2
  floor.position.y = 0.0008
  floor.receiveShadow = true
  const unpacked = new Group()
  {
    const board = new MeshStandardMaterial({ color: 0xb08556, roughness: 0.93 })
    const inside = new MeshStandardMaterial({ color: 0x8a6540, roughness: 0.96 })
    const foam = new MeshStandardMaterial({ color: 0xf1efe9, roughness: 0.85 })
    const tape = new MeshStandardMaterial({ color: 0xe6d3ad, roughness: 0.5 })
    const piece = (parent: Object3D, mat: MeshStandardMaterial, size: [number, number, number], pos: [number, number, number], rot: [number, number, number] = [0, 0, 0]) => {
      const m = new Mesh(new BoxGeometry(...size), mat)
      m.position.set(...pos)
      m.rotation.set(...rot)
      m.castShadow = m.receiveShadow = true
      parent.add(m)
      return m
    }
    const W = 0.5, H = 0.11, D = 0.3, T = 0.006
    const carton = new Group()
    piece(carton, inside, [W, T, D], [0, T / 2, 0])
    piece(carton, board, [W, H, T], [0, H / 2, D / 2])
    piece(carton, board, [W, H, T], [0, H / 2, -D / 2])
    piece(carton, board, [T, H, D], [W / 2, H / 2, 0])
    piece(carton, board, [T, H, D], [-W / 2, H / 2, 0])
    piece(carton, tape, [0.05, 0.002, 0.012], [0.02, H * 0.55, D / 2 + T / 2], [Math.PI / 2, 0, 0.1]) // what is left of the tape
    const flap = (at: [number, number, number], size: [number, number, number], off: [number, number, number], rot: [number, number, number]) => {
      const hinge = new Group()
      hinge.position.set(...at)
      hinge.rotation.set(...rot)
      piece(hinge, board, size, off)
      carton.add(hinge)
    }
    flap([0, H, D / 2], [W, T, D / 2], [0, 0, D / 4], [0.5, 0, 0]) // the front flap hangs open
    flap([0, H, -D / 2], [W, T, D / 2], [0, 0, -D / 4], [1.05, 0, 0]) // the back one stands up
    flap([W / 2, H, 0], [0.12, T, D], [0.06, 0, 0], [0, 0, -0.65])
    flap([-W / 2, H, 0], [0.045, T, D * 0.8], [-0.022, 0, 0.02], [0, 0, 0.35]) // torn: only a stub is left
    carton.position.set(-0.31, 0, -0.27)
    carton.rotation.y = 0.42
    unpacked.add(carton)
    piece(unpacked, board, [0.085, T, D * 0.78], [-0.4, T / 2, 0.16], [0, 1.15, 0]) // the torn-off flap
    piece(unpacked, board, [0.07, 0.004, 0.035], [0.33, 0.002, 0.27], [0, 0.7, 0])
    piece(unpacked, board, [0.04, 0.004, 0.05], [-0.16, 0.002, 0.34], [0, -0.4, 0])
    piece(unpacked, tape, [0.11, 0.002, 0.014], [0.2, 0.001, 0.36], [0, -0.25, 0])
    piece(unpacked, foam, [0.13, 0.04, 0.05], [0.37, 0.02, 0.1], [0, 0.6, 0])
    piece(unpacked, foam, [0.13, 0.04, 0.05], [0.33, 0.06, 0.12], [0.02, 1.1, 0.03])
    piece(unpacked, foam, [0.06, 0.035, 0.06], [-0.3, 0.0175, 0.3], [0, 0.3, 0])
    { // the instruction sheet, face up
      const c = document.createElement('canvas')
      c.width = 256
      c.height = 360
      const g = c.getContext('2d')!
      g.fillStyle = '#f6f4ee'
      g.fillRect(0, 0, 256, 360)
      g.strokeStyle = '#4d4a44'
      g.lineWidth = 3
      g.strokeRect(54, 46, 148, 96)
      g.strokeRect(78, 142, 12, 70)
      g.strokeRect(166, 142, 12, 70)
      g.fillStyle = '#b9b5ab'
      for (let i = 0; i < 5; i++) g.fillRect(40, 244 + i * 20, i === 4 ? 96 : 176, 6)
      const t = new CanvasTexture(c)
      t.colorSpace = SRGBColorSpace
      const sheet = new Mesh(new PlaneGeometry(0.105, 0.148), new MeshStandardMaterial({ map: t, roughness: 0.9 }))
      sheet.rotation.set(-Math.PI / 2, 0, -0.35)
      sheet.position.set(0.02, 0.0016, 0.37)
      sheet.receiveShadow = true
      unpacked.add(sheet)
    }
    { // the bag of screws
      const bag = new Mesh(new BoxGeometry(0.085, 0.012, 0.06), new MeshStandardMaterial({ color: 0xffffff, roughness: 0.25, transparent: true, opacity: 0.32 }))
      bag.position.set(0.25, 0.006, 0.2)
      bag.rotation.y = -0.5
      unpacked.add(bag)
      const brass = new MeshStandardMaterial({ color: 0xefd176, roughness: 0.32, metalness: 0.62 })
      for (const [x, z, r] of [[0.24, 0.195, 0.3], [0.262, 0.205, 1.2], [0.25, 0.21, 2.2], [0.238, 0.21, 0.9]]) {
        const sc = new Mesh(new CylinderGeometry(0.003, 0.003, 0.028, 8), brass)
        sc.rotation.set(Math.PI / 2, 0, r)
        sc.position.set(x, 0.005, z)
        unpacked.add(sc)
      }
    }
  }
  floor.visible = unpacked.visible = false
  scene.add(floor, unpacked)

  // ------------------------------------------------------------------ boxes: one per guide that opens at once
  const BOX = { w: 0.46, h: 0.072, d: 0.3 }
  const cardboard = new MeshStandardMaterial({ color: 0xa87a4c, roughness: 0.92 })
  const boxTexture = (name: string, code: string): Texture => {
    const c = document.createElement('canvas')
    c.width = 1024
    c.height = 140
    const g = c.getContext('2d')!
    const grad = g.createLinearGradient(0, 0, 0, 140)
    grad.addColorStop(0, '#b78b5c')
    grad.addColorStop(1, '#a07549')
    g.fillStyle = grad
    g.fillRect(0, 0, 1024, 140)
    for (let x = 0; x < 1024; x += 6) {
      g.fillStyle = `rgba(0,0,0,${0.02 + (x % 18 === 0 ? 0.03 : 0)})`
      g.fillRect(x, 0, 2, 140)
    }
    g.fillStyle = '#1b130c'
    g.font = '700 78px "Google Sans", Arial, sans-serif'
    g.textBaseline = 'middle'
    g.fillText(name, 40, 74)
    g.font = '500 34px "Google Sans", Arial, sans-serif'
    g.textAlign = 'right'
    g.fillText(code, 984, 76)
    const t = new CanvasTexture(c)
    t.colorSpace = SRGBColorSpace
    t.anisotropy = 4
    return t
  }
  interface Box { item: Item; mesh: Mesh; button: HTMLButtonElement }
  const boxes: Box[] = []
  const stackPose = (i: number) => {
    const r = rng(i * 31 + 5)
    r()
    return { pos: new Vector3((r() - 0.5) * 0.05, 0.355 + BOX.h / 2 + i * (BOX.h + 0.001), -0.46 + (r() - 0.5) * 0.03), rotY: (r() - 0.5) * 0.09 }
  }
  const addBox = (item: Item) => {
    const front = new MeshStandardMaterial({ map: boxTexture(boxName(item.product), boxCode(item)), roughness: 0.9 })
    const mesh = new Mesh(new BoxGeometry(BOX.w, BOX.h, BOX.d), [cardboard, cardboard, cardboard, cardboard, front, cardboard])
    mesh.castShadow = mesh.receiveShadow = true
    const p = stackPose(boxes.length)
    mesh.position.copy(p.pos)
    mesh.rotation.set(0, p.rotY, 0)
    mesh.visible = screen !== 'ready'
    scene.add(mesh)
    const button = document.createElement('button') // the boxes, for a keyboard or a screen reader
    button.type = 'button'
    button.setAttribute('role', 'listitem')
    button.textContent = item.product
    button.addEventListener('click', () => void open(item))
    $('.shelf-list').append(button)
    boxes.push({ item, mesh, button })
  }

  // ------------------------------------------------------------------ motion and the camera
  const tweens: { t0: number; ms: number; fn: (k: number) => void; res: () => void }[] = []
  const ease = (k: number) => 1 - Math.pow(1 - k, 4)
  const tween = (ms: number, fn: (k: number) => void) =>
    new Promise<void>((res) => {
      if (reduce || ms <= 0) { fn(1); res(); return }
      tweens.push({ t0: performance.now(), ms, fn, res })
    })
  const moveObj = (o: Object3D, pos: Vector3, rot: Euler | null, ms: number) => {
    const p0 = o.position.clone()
    const q0 = o.quaternion.clone()
    const q1 = rot ? new Quaternion().setFromEuler(rot) : q0.clone()
    return tween(ms, (k) => { const e = ease(k); o.position.lerpVectors(p0, pos, e); o.quaternion.slerpQuaternions(q0, q1, e) })
  }
  type Pose = { t: number[]; d: number[]; w: number; h: number; lift?: number }
  const POSES: Record<string, Pose> = {
    home: { t: [0, 0.2, -0.2], d: [0, 0.62, 1], w: 0.74, h: 0.92 },
    ready: { t: [-0.06, 0.16, -0.06], d: [0.35, 0.5, 1], w: 0.74, h: 0.8, lift: 0.24 },
  }
  let pose = POSES.home
  const camT = new Vector3()
  const camP = new Vector3()
  const poseCam = (p: Pose) => {
    const d = new Vector3(...(p.d as [number, number, number])).normalize()
    const v = MathUtils.degToRad(camera.fov / 2)
    const a = camera.aspect
    const dist = Math.max(p.w / 2 / (Math.tan(v) * a), p.h / 2 / Math.tan(v)) * 1.08
    const t = new Vector3(...(p.t as [number, number, number]))
    if (a < 0.8) t.y -= p.h * (p.lift ?? 0.16) // on a phone the words take the lower part of the screen
    return { t, p: t.clone().addScaledVector(d, dist) }
  }
  const goPose = (name: string, ms = 1100) => {
    pose = POSES[name]
    const { t, p } = poseCam(pose)
    const t0 = camT.clone()
    const p0 = camP.clone()
    return tween(ms, (k) => { const e = ease(k); camT.lerpVectors(t0, t, e); camP.lerpVectors(p0, p, e) })
  }
  const resize = () => {
    renderer.setSize(innerWidth, innerHeight, false)
    camera.aspect = innerWidth / innerHeight
    camera.updateProjectionMatrix()
    const { t, p } = poseCam(pose)
    camT.copy(t)
    camP.copy(p)
  }
  addEventListener('resize', resize)
  resize()
  const frame = (now: number) => {
    for (let i = tweens.length - 1; i >= 0; i--) {
      const tw = tweens[i]
      const k = Math.min(1, (now - tw.t0) / tw.ms)
      tw.fn(k)
      if (k >= 1) { tweens.splice(i, 1); tw.res() }
    }
    if (model && !reduce && screen === 'ready') model.rotation.y = modelYaw + Math.sin(now / 4000) * 0.12
    camera.position.copy(camP)
    camera.lookAt(camT)
    renderer.render(scene, camera)
    requestAnimationFrame(frame)
  }
  requestAnimationFrame(frame)

  // ------------------------------------------------------------------ screens
  let screen: 'home' | 'scan' | 'ready' = 'home'
  let way: 'cam' | 'code' | 'pdf' = 'cam' // the scan screen shows one way in at a time: the camera, a typed code, or the guide's PDF
  let run = 0 // a newer action cancels the one still running
  const setScreen = (s: typeof screen) => {
    screen = s
    root.dataset.s = s
    floor.visible = unpacked.visible = s === 'ready' // the unpacking spot instead of the bench, its shelf and the boxes
    shelf.visible = s !== 'ready'
    for (const b of boxes) b.mesh.visible = s !== 'ready'
    for (const f of root.querySelectorAll<HTMLElement>('.foot')) f.hidden = f.dataset.for !== s
    $('.back').classList.toggle('hide', s === 'home')
    $('.find').classList.toggle('hide', s !== 'home')
    if (s !== 'home') closeSearch()
    if (s !== 'scan') stopCamera()
    const s0 = spill.intensity
    const s1 = s === 'ready' ? 1.2 : 4
    void tween(900, (k) => (spill.intensity = s0 + (s1 - s0) * k))
  }
  const link = (params: Record<string, string>) => {
    return appLink({ ...params, key, lang })
  }
  const say = (text: string) => (status.textContent = text)

  const words = () => {
    document.documentElement.lang = lang
    document.title = w().title
    const ln = $<HTMLButtonElement>('.lang')
    ln.textContent = w().other
    ln.setAttribute('aria-label', w().otherAria)
    $('.find').setAttribute('aria-label', w().findAria)
    $('.back').setAttribute('aria-label', w().back)
    $('.searchbar label').textContent = w().find
    $<HTMLInputElement>('.q').placeholder = w().find
    $('.go-scan span').textContent = w().scan
    $('.go-upload').textContent = w().upload
    $('.empty').textContent = w().empty
    $('.scanhint').textContent = way === 'code' ? w().codeHint : way === 'pdf' ? w().pdfHint : cameraOn ? w().type : w().noCamera
    $('.scanline').textContent = way === 'cam' && cameraOn ? w().aim : ''
    $('.seg').setAttribute('aria-label', w().ways)
    $('.seg [data-way="cam"] span').textContent = w().wCam
    $('.seg [data-way="code"] span').textContent = w().wCode
    $('.seg [data-way="pdf"] span').textContent = w().wPdf
    $('.codefield label').textContent = w().find
    $<HTMLInputElement>('#home-code').placeholder = w().find
    $('.codefield button[type="submit"]').setAttribute('aria-label', w().go)
    $('.shot').setAttribute('aria-label', w().photo)
    $('.go-pdf span').textContent = w().pdf
    $('.r-steps span').textContent = w().steps
    $('.r-help span').textContent = w().help
    $('.r-quest span').textContent = w().quest
    if (ready) readyWords()
  }
  $('.lang').addEventListener('click', () => {
    lang = lang === 'el' ? 'en' : 'el'
    const url = new URL(location.href)
    url.searchParams.set('lang', lang)
    history.replaceState(history.state, '', url)
    words()
    if (ready) setReadyLinks(ready.job)
  })

  // ------------------------------------------------------------------ search
  const closeSearch = () => {
    $('.results').classList.add('hide')
    $('.searchbar').classList.add('hide')
    $('.hit').classList.add('hide')
    $<HTMLInputElement>('.q').value = ''
    dim('')
  }
  const matches = (it: Item, text: string) => {
    const n = (s: string) => s.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase().replace(/[.\s-]/g, '')
    const t = n(text)
    return !!t && [it.product, it.guide_id ?? '', it.article ?? ''].some((v) => n(v).includes(t))
  }
  const dim = (text: string) => {
    const hits = boxes.filter((b) => matches(b.item, text))
    for (const b of boxes) {
      const m = (b.mesh.material as MeshStandardMaterial[])[4]
      m.emissive = new Color(text && hits.includes(b) ? 0x2a1a08 : 0x000000)
      for (const mm of b.mesh.material as MeshStandardMaterial[]) mm.opacity = text && !hits.includes(b) ? 0.35 : 1
      for (const mm of b.mesh.material as MeshStandardMaterial[]) mm.transparent = !!text && !hits.includes(b)
    }
    const hit = $<HTMLButtonElement>('.hit')
    hit.classList.toggle('hide', !hits.length)
    if (hits[0]) hit.innerHTML = `${hits[0].item.product} <span>· ${boxCode(hits[0].item)}</span>`
    hit.onclick = () => hits[0] && void open(hits[0].item)
  }
  $('.find').addEventListener('click', () => {
    $('.searchbar').classList.remove('hide')
    $<HTMLInputElement>('.q').focus()
  })
  let searchTimer = 0
  let searchSeq = 0
  const results = $<HTMLUListElement>('.results')
  const showIkea = async (text: string) => {
    const my = ++searchSeq
    if (text.trim().length < 2) { results.classList.add('hide'); return }
    try {
      const res = await apiFetch(`/api/catalog/ikea?q=${encodeURIComponent(text.trim())}`, key)
      if (my !== searchSeq || !res.ok) return
      const items = ((await res.json()) as { items: { name: string; type?: string; article: string; image?: string }[] }).items
      results.replaceChildren()
      for (const it of items.slice(0, 6)) {
        const li = document.createElement('li')
        const b = document.createElement('button')
        b.type = 'button'
        b.setAttribute('role', 'option')
        const code = /^\d{8}$/.test(it.article) ? `${it.article.slice(0, 3)}.${it.article.slice(3, 6)}.${it.article.slice(6)}` : it.article.toUpperCase()
        b.innerHTML = `${it.image ? `<img alt="" loading="lazy" src="${it.image}?f=xxs" />` : '<i></i>'}<span><b></b> <em></em><small></small></span>`
        b.querySelector('b')!.textContent = it.name
        b.querySelector('em')!.textContent = it.type ?? ''
        b.querySelector('small')!.textContent = code
        b.addEventListener('click', () => void openByText(it.article))
        li.append(b)
        results.append(li)
      }
      results.dataset.label = w().fromIkea
      results.classList.toggle('hide', !items.length)
    } catch {
      results.classList.add('hide')
    }
  }
  $<HTMLInputElement>('.q').addEventListener('input', (e) => {
    const text = (e.target as HTMLInputElement).value
    dim(text)
    clearTimeout(searchTimer)
    searchTimer = window.setTimeout(() => void showIkea(text), 350)
  })
  $<HTMLInputElement>('.q').addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closeSearch()
    if (e.key !== 'Enter') return
    const text = (e.target as HTMLInputElement).value.trim()
    const hit = boxes.find((b) => matches(b.item, text))
    void (hit ? open(hit.item) : openByText(text))
  })

  // ------------------------------------------------------------------ opening a guide
  /** True when a guide opens (or its build starts); false when the person stays on this screen with a message. */
  const openByText = async (text: string): Promise<boolean> => {
    if (!text) return false
    say(w().looking)
    $('.scanline').textContent = w().looking
    try {
      const res = await apiFetch('/api/catalog/open', key, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ q: text }),
      })
      if (res.status === 409) { $('.scanline').textContent = w().several; say(w().several); dim(text); return false }
      if (res.status === 404 || res.status === 422) { $('.scanline').textContent = w().notFound; say(w().notFound); return false }
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const body = (await res.json()) as { job_id: string; ready: boolean }
      if (body.ready) void showReady(body.job_id, null)
      else location.assign(link({ mode: 'build', job: body.job_id, auto: '1' })) // not built yet: watch it being built; a scanned guide needs no approval tap, and opens by itself
      return true
    } catch {
      $('.scanline').textContent = w().offline
      say(w().offline)
      return false
    }
  }
  const open = async (item: Item) => {
    if (item.job_id && item.ready) return showReady(item.job_id, boxes.find((b) => b.item === item) ?? null)
    return openByText(item.article || item.guide_id || item.product)
  }

  // ------------------------------------------------------------------ scan: the camera and a code field
  let cameraOn = false
  let stream: MediaStream | null = null
  type Detector = { detect(src: CanvasImageSource): Promise<{ rawValue: string }[]> }
  const BD = (window as unknown as { BarcodeDetector?: new (o: { formats: string[] }) => Detector }).BarcodeDetector
  // Chrome on Android has BarcodeDetector. Safari on an iPhone does not: there the QR code is read from the video
  // frame by jsQR (QR codes only, loaded when needed). Barcodes can still be typed.
  const makeDetector = async (): Promise<Detector | null> => {
    if (BD) return new BD({ formats: ['qr_code', 'code_128', 'ean_13', 'ean_8', 'upc_a'] })
    try {
      const { default: jsQR } = await import('jsqr')
      const canvas = document.createElement('canvas')
      const g = canvas.getContext('2d', { willReadFrequently: true })
      if (!g) return null
      return {
        detect: async (src) => {
          const v = src as HTMLVideoElement
          if (!v.videoWidth) return []
          const k = Math.min(1, 720 / Math.max(v.videoWidth, v.videoHeight)) // enough for a code held in front of the phone
          canvas.width = Math.round(v.videoWidth * k)
          canvas.height = Math.round(v.videoHeight * k)
          g.drawImage(v, 0, 0, canvas.width, canvas.height)
          const d = g.getImageData(0, 0, canvas.width, canvas.height)
          const hit = jsQR(d.data, d.width, d.height)
          return hit?.data ? [{ rawValue: hit.data }] : []
        },
      }
    } catch {
      return null
    }
  }
  const stopCamera = () => {
    stream?.getTracks().forEach((t) => t.stop())
    stream = null
    cameraOn = false
    $('.cam').classList.remove('found')
  }
  const startScan = async () => {
    const my = ++run
    setScreen('scan')
    cameraOn = false
    words()
    const detector = 'mediaDevices' in navigator && navigator.mediaDevices ? await makeDetector() : null
    if (my !== run) return
    if (!detector) { $<HTMLInputElement>('#home-code').focus(); return }
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false })
      if (my !== run) return stopCamera()
      let missed = ''
      const video = $<HTMLVideoElement>('.cam video')
      video.srcObject = stream
      await video.play()
      cameraOn = true
      words()
      while (my === run && cameraOn) {
        const found = await detector.detect(video).catch(() => [])
        const value = found[0]?.rawValue?.trim()
        if (value) {
          $('.cam').classList.add('found')
          const fromUrl = (() => { try { return new URL(value).searchParams.get('job') } catch { return null } })()
          if (fromUrl) return void showReady(fromUrl, null) // a QR of one of our own links
          if (value !== missed && (await openByText(value))) return
          // no guide for this code: keep the message on screen, then look again (the same code is not asked twice)
          missed = value
          await new Promise((r) => setTimeout(r, 2500))
          if (my !== run) return
          $('.cam').classList.remove('found')
        }
        await new Promise((r) => setTimeout(r, 250))
      }
    } catch {
      cameraOn = false
      words()
      $<HTMLInputElement>('#home-code').focus()
    }
  }
  $('.go-scan').addEventListener('click', () => void startScan())
  // `&scan=1` (the landing page's "Scan the box"): open on the scanner. Not after a reload that already has a guide.
  if (q.get('scan') === '1' && !q.get('job')) window.setTimeout(() => void startScan(), 300)

  // ------------------------------------------------------------------ a photo of the box (or of the label, or of the piece)
  // POST /api/identify reads the name and the numbers printed on it and answers with a guide of this server
  // (`match`) or with the words to look the guide up (`query`), which then go the same way as a scanned code.
  const toJpeg = (src: CanvasImageSource, width: number, height: number): string => {
    const k = Math.min(1, 1600 / Math.max(width, height)) // labels carry small print: keep it large
    const c = document.createElement('canvas')
    c.width = Math.round(width * k)
    c.height = Math.round(height * k)
    c.getContext('2d')?.drawImage(src, 0, 0, c.width, c.height)
    return c.toDataURL('image/jpeg', 0.85).split(',')[1] ?? ''
  }
  const identify = async (image: string) => {
    if (!image) return
    say(w().looking)
    $('.scanline').textContent = w().looking
    try {
      const res = await apiFetch('/api/identify', key, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ image, lang }),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const out = (await res.json()) as { match: { job_id: string; ready: boolean } | null; query: string | null; message?: string }
      if (out.match?.job_id) {
        if (out.match.ready) return void showReady(out.match.job_id, null)
        return location.assign(link({ mode: 'build', job: out.match.job_id, auto: '1' }))
      }
      if (out.query) return void (await openByText(out.query)) // not found: openByText says so
      $('.scanline').textContent = out.message || w().unsure
      say(out.message || w().unsure)
    } catch {
      $('.scanline').textContent = w().offline
      say(w().offline)
    }
  }
  $('.shot').addEventListener('click', () => {
    const video = $<HTMLVideoElement>('.cam video')
    if (cameraOn && video.videoWidth) return void identify(toJpeg(video, video.videoWidth, video.videoHeight))
    $<HTMLInputElement>('.shotfile').click() // no live camera here: the phone's own camera or a picture from its library
  })
  $<HTMLInputElement>('.shotfile').addEventListener('change', (e) => {
    const input = e.target as HTMLInputElement
    const file = input.files?.[0]
    input.value = ''
    if (!file) return
    const img = new Image()
    const url = URL.createObjectURL(file)
    img.onload = () => { URL.revokeObjectURL(url); void identify(toJpeg(img, img.naturalWidth, img.naturalHeight)) }
    img.onerror = () => { URL.revokeObjectURL(url); $('.scanline').textContent = w().unsure }
    img.src = url
  })
  $('.codefield').addEventListener('submit', (e) => {
    e.preventDefault()
    void openByText($<HTMLInputElement>('#home-code').value.trim())
  })
  $('.go-upload').addEventListener('click', () => location.assign(link({ mode: 'build' })))
  for (const b of root.querySelectorAll<HTMLButtonElement>('.seg button')) {
    b.addEventListener('click', () => {
      way = b.dataset.way as typeof way
      root.dataset.way = way
      $<HTMLElement>('.foot[data-for="scan"]').dataset.way = way
      for (const o of root.querySelectorAll('.seg button')) o.setAttribute('aria-pressed', String(o === b))
      words()
      if (way === 'code') $<HTMLInputElement>('#home-code').focus()
    })
  }
  // the third way in, next to the camera and the code field: the guide itself, as a PDF or as photos of its pages.
  // Like a scanned code it needs no approval tap: the build screen approves the plan and opens the guide by itself.
  $('.go-pdf').addEventListener('click', () => $<HTMLInputElement>('.pdffile').click())
  $<HTMLInputElement>('.pdffile').addEventListener('change', async (e) => {
    const input = e.target as HTMLInputElement
    const files = [...(input.files ?? [])]
    input.value = ''
    if (!files.length) return
    const picked = choose(null, files)
    if ('word' in picked) { $('.scanline').textContent = bw(lang, picked.word); return }
    $('.scanline').textContent = w().sending
    const out = await sendGuide(picked.chosen as Chosen, '', key)
    if (out.ok) location.assign(link({ mode: 'build', job: out.job, auto: '1' }))
    else $('.scanline').textContent = bw(lang, out.word)
  })

  // ------------------------------------------------------------------ boxes on the canvas
  const ray = new Raycaster()
  canvas.addEventListener('click', (e) => {
    if (screen !== 'home') return
    ray.setFromCamera(new Vector2((e.clientX / innerWidth) * 2 - 1, -(e.clientY / innerHeight) * 2 + 1), camera)
    const hit = ray.intersectObjects(boxes.map((b) => b.mesh))[0]
    const box = boxes.find((b) => b.mesh === hit?.object)
    if (box) void open(box.item)
  })
  canvas.addEventListener('pointermove', (e) => {
    if (screen !== 'home') return (canvas.style.cursor = '')
    ray.setFromCamera(new Vector2((e.clientX / innerWidth) * 2 - 1, -(e.clientY / innerHeight) * 2 + 1), camera)
    canvas.style.cursor = ray.intersectObjects(boxes.map((b) => b.mesh)).length ? 'pointer' : ''
  })

  // ------------------------------------------------------------------ ready: the real model on the bench
  let model: Object3D | null = null
  let modelYaw = -0.5
  let ready: { job: string; manifest: Manifest } | null = null
  const readyWords = () => {
    if (!ready) return
    const m = ready.manifest
    $('.readyname').innerHTML = `${boxName(m.title)} <span class="of">· ${w().nsteps(m.steps.length)}</span>`
    const flags = (m as Manifest & { flags?: unknown[] }).flags?.length ?? 0
    const fl = $<HTMLParagraphElement>('.flagline')
    fl.hidden = !flags
    fl.innerHTML = flags ? `${svg(ICON.warn)} ${w().flags(flags)}` : ''
  }
  const setReadyLinks = (job: string) => {
    $<HTMLAnchorElement>('.r-steps').href = link({ job })
    $<HTMLAnchorElement>('.r-help').href = link({ mode: 'live', job }) // the full-screen camera guide (src/live)
    $<HTMLAnchorElement>('.r-quest').href = link({ mode: 'xr', job })
  }
  const showReady = async (job: string, box: Box | null, push = true) => {
    const my = ++run
    setScreen('ready')
    setReadyLinks(job)
    if (push) history.pushState({ job }, '', link({ mode: 'ready', job }))
    $('.readyname').innerHTML = `<i class="spinner" aria-hidden="true"></i> ${w().loading}`
    say(w().loading)
    void goPose('ready')
    const shelfBox = box ?? boxes.find((b) => b.item.job_id === job) ?? null
    if (shelfBox) void moveObj(shelfBox.mesh, new Vector3(-0.3, BOX.h / 2, -0.24), new Euler(0, 0.42, 0), box ? 900 : 0)
    try {
      const res = await fetch(fileUrl(job, 'manifest.json', key), { cache: 'no-cache' })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const manifest = (await res.json()) as Manifest
      const gltf = await loadGltf(fileUrl(job, manifest.glb, key))
      if (my !== run) return
      ready = { job, manifest }
      readyWords()
      say(`${manifest.title}`)
      // The GLB rests at the first frame of the walkthrough, with most pieces still hidden: show its last frame,
      // the finished furniture the right way up.
      const clip = gltf.animations.find((a) => a.name === 'walkthrough') ?? gltf.animations[0]
      if (clip) {
        const mixer = new AnimationMixer(gltf.scene)
        const action = mixer.clipAction(clip)
        action.setLoop(LoopOnce, 1)
        action.clampWhenFinished = true // a repeating clip at its very end is back at its start
        action.play()
        mixer.setTime(Math.max(0, clip.duration - 1e-3))
      }
      placeModel(gltf.scene)
    } catch {
      if (my !== run) return
      $('.readyname').textContent = w().offline
      say(w().offline)
    }
  }
  const placeModel = (g: Object3D) => {
    if (model) scene.remove(model)
    const holder = new Group()
    const marker = g.getObjectByName('marker')
    if (marker) marker.visible = false
    g.traverse((o) => { if ((o as Mesh).isMesh) o.castShadow = o.receiveShadow = true })
    const box = new Box3().setFromObject(g)
    const size = box.getSize(new Vector3())
    const s = Math.min(1, 0.52 / Math.max(size.x, size.y, size.z)) // a chair is shown smaller than life, a box as it is
    g.scale.setScalar(s)
    const centre = box.getCenter(new Vector3()).multiplyScalar(s)
    g.position.set(-centre.x, -box.min.y * s, -centre.z)
    holder.add(g)
    holder.position.set(0.06, 0, 0.02)
    modelYaw = -0.5
    holder.rotation.y = modelYaw
    model = holder
    scene.add(holder)
    if (!reduce) { // it settles on the bench
      holder.position.y = 0.25
      void moveObj(holder, new Vector3(0.06, 0, 0.02), null, 900)
    }
  }
  const showQuest = async () => {
    const xr = (navigator as Navigator & { xr?: { isSessionSupported(m: string): Promise<boolean> } }).xr
    const yes = q.get('quest') === '1' || (xr ? await xr.isSessionSupported('immersive-ar').catch(() => false) : false)
    $<HTMLAnchorElement>('.r-quest').hidden = !yes
  }

  const goHome = (push = true) => {
    ++run
    if (model) { scene.remove(model); model = null }
    ready = null
    setScreen('home')
    boxes.forEach((b, i) => { const p = stackPose(i); void moveObj(b.mesh, p.pos, new Euler(0, p.rotY, 0), 700) })
    void goPose('home')
    if (push) history.pushState({}, '', link({}))
  }
  $('.back').addEventListener('click', () => goHome())
  addEventListener('popstate', () => {
    const p = new URLSearchParams(location.search)
    if (p.get('mode') === 'ready' && p.get('job')) void showReady(p.get('job')!, null, false)
    else goHome(false)
  })

  // ------------------------------------------------------------------ start
  words()
  void showQuest()
  const start = new URLSearchParams(location.search)
  const startJob = start.get('mode') === 'ready' ? start.get('job') : null
  setScreen(startJob ? 'ready' : 'home')
  resize()
  void (async () => {
    try {
      const res = await apiFetch('/api/catalog', key)
      if (res.status === 401) {
        const e = $<HTMLParagraphElement>('.empty')
        e.textContent = w().noKey
        e.hidden = false
        say(w().noKey)
        return
      }
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const items = ((await res.json()) as { items: Item[] }).items
      for (const it of items.filter((i) => i.ready && i.job_id).slice(0, 5)) addBox(it)
      $<HTMLParagraphElement>('.empty').hidden = boxes.length > 0
    } catch {
      say(w().offline)
    }
    if (startJob) void showReady(startJob, null, false)
  })()
}
