import { BoxGeometry, CylinderGeometry, Group, Mesh, MeshStandardMaterial } from 'three'

/** from the tip to the end of the handle, metres */
export const TOOL_LENGTH = 0.177
/** widest radius of the handle, metres */
export const TOOL_RADIUS = 0.0135

/** A cabinetmaker's screwdriver: beech handle, brass ferrule. Tip at the origin, handle up +Y. */
export function buildScrewdriver(): Group {
  const steel = new MeshStandardMaterial({ color: 0xc9ccd1, metalness: 0.95, roughness: 0.26 })
  const beech = new MeshStandardMaterial({ color: 0x8a4f26, metalness: 0, roughness: 0.42 })
  const ferrule = new MeshStandardMaterial({ color: 0xd9a441, metalness: 0.95, roughness: 0.3 })
  const tool = new Group()
  const add = (mesh: Mesh, y: number) => {
    mesh.position.y = y
    mesh.castShadow = true
    tool.add(mesh)
  }
  add(new Mesh(new BoxGeometry(0.0052, 0.005, 0.0011), steel), 0.0025)
  add(new Mesh(new BoxGeometry(0.0011, 0.005, 0.0052), steel), 0.0025)
  add(new Mesh(new CylinderGeometry(0.0021, 0.0021, 0.07, 20), steel), 0.039)
  add(new Mesh(new CylinderGeometry(0.0072, 0.0052, 0.012, 28), ferrule), 0.08)
  add(new Mesh(new CylinderGeometry(0.0128, 0.0098, 0.05, 28), beech), 0.111)
  add(new Mesh(new CylinderGeometry(0.0118, 0.0128, 0.034, 28), beech), 0.153)
  add(new Mesh(new CylinderGeometry(0.0062, 0.0118, 0.007, 28), beech), 0.1735)
  // one flat on the handle, so its turning shows
  add(new Mesh(new BoxGeometry(0.0262, 0.03, 0.006), beech), 0.13)
  tool.visible = false
  return tool
}
