import {
  Box3, DirectionalLight, HemisphereLight, Mesh, MeshStandardMaterial, Object3D, PerspectiveCamera, Scene, Vector3, WebGLRenderer,
} from 'three'
import { STLLoader } from 'three/addons/loaders/STLLoader.js'

// The part that has just been built, turning slowly in the build feed, so a person waiting sees what is being
// made and not only its name. One small canvas per feed row; parts that arrive together take turns, each shown
// for a moment. Nothing here can be touched: it is a picture of the work, not a viewer.

interface View { add(id: string, url: string): void }
const views = new WeakMap<HTMLElement, View>()
const HOLD_MS = 1300 // how long one part stays before the next one in the queue replaces it

/** Show part `id` (its STL at `url`) in `host`, after the parts already waiting there. */
export function showPart(host: HTMLElement, id: string, url: string): void {
  let v = views.get(host)
  if (!v) views.set(host, (v = make(host)))
  v.add(id, url)
}

function make(host: HTMLElement): View {
  const canvas = document.createElement('canvas')
  const name = document.createElement('code')
  host.append(canvas, name)
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches
  const renderer = new WebGLRenderer({ canvas, antialias: true, alpha: true })
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2))
  const scene = new Scene()
  const camera = new PerspectiveCamera(28, 1, 0.1, 50)
  camera.position.set(2.6, 1.9, 3.4)
  camera.lookAt(0, 0, 0)
  const sun = new DirectionalLight(0xffffff, 2.4)
  sun.position.set(2, 4, 3)
  scene.add(new HemisphereLight(0xffffff, 0x8a7a66, 1.6), sun)
  const material = new MeshStandardMaterial({ color: 0xd8c3a0, roughness: 0.62, metalness: 0.05 })
  const loader = new STLLoader()
  const queue: { id: string; url: string }[] = []
  let part: Object3D | null = null
  let since = 0
  let busy = false

  const next = async () => {
    const item = queue.shift()
    if (!item) return void (busy = false)
    busy = true
    try {
      const geometry = await loader.loadAsync(item.url)
      geometry.rotateX(-Math.PI / 2) // the parts are modelled with Z up
      geometry.computeVertexNormals()
      const box = new Box3().setFromBufferAttribute(geometry.getAttribute('position') as never)
      const size = box.getSize(new Vector3())
      const centre = box.getCenter(new Vector3())
      geometry.translate(-centre.x, -centre.y, -centre.z)
      const mesh = new Mesh(geometry, material)
      mesh.scale.setScalar(1.9 / Math.max(size.x, size.y, size.z, 1e-6))
      if (part) {
        scene.remove(part)
        ;(part as Mesh).geometry.dispose()
      }
      part = mesh
      scene.add(mesh)
      name.textContent = item.id
      since = performance.now()
    } catch {
      // a part whose file is not there yet: its name is already in the row, show the next one
    }
    window.setTimeout(() => void next(), part ? HOLD_MS : 0)
  }

  const frame = (now: number) => {
    if (!host.isConnected) return renderer.dispose()
    const w = canvas.clientWidth
    if (w && canvas.width !== Math.round(w * renderer.getPixelRatio())) renderer.setSize(w, w, false)
    if (part) {
      const k = reduce ? 1 : Math.min(1, (now - since) / 350)
      part.rotation.y = reduce ? 0.6 : now / 2600
      part.scale.setScalar((part.userData.s ??= part.scale.x) * (0.7 + 0.3 * (1 - Math.pow(1 - k, 3))))
    }
    renderer.render(scene, camera)
    requestAnimationFrame(frame)
  }
  requestAnimationFrame(frame)

  return {
    add(id, url) {
      queue.push({ id, url })
      if (!busy) void next()
    },
  }
}
