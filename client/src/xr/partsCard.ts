import * as THREE from 'three';
import { AG_XR, FONT, reducedMotion } from './palette';
import type { DemoPart, DemoStep } from './demoAssembly';
import type { AssemblyView } from './ghost';
import { partGeometry } from './ghost';
import { makeBackPlate } from './panels';
import { HARDWARE_NAMES, displayName } from './stepGuides';
import { LANG, partName } from './i18n';
import { displayName as pageName } from '../i18n';

// Small glass card beside the step panel (top-aligned, same tilt): a turning 3D copy of the part to find next,
// and under it its name (a board) or its code (a screw). A turn shows ↻, the end ✓.
// (The parts list it once had is gone: the panel and this card say only what to pick up now.)
// Finding the right part in the pile is the hardest moment of flat-pack assembly, so the
// card shows it before the ghost says "put it here".

const CW = 560;
const CH = 470;
export const CARD_W = 0.17;
export const CARD_H = (CARD_W * CH) / CW;
const PAD = 36;
const WELL = { y: 36, h: 300 };
const MODEL_M = 0.075;
const REV_PER_S = 0.18;

/** Every part of the product with its total count, in guide order (hardware by code). */
export function inventory(steps: DemoStep[]): { name: string; code?: string; total: number }[] {
  const rows = new Map<string, { name: string; code?: string; total: number }>();
  for (const step of steps) {
    const modelled = new Map<string, number>();
    for (const p of step.parts) {
      const code = p.kind === 'screw' ? p.name : undefined;
      const key = code ? `hw:${code}` : `part:${p.name}`;
      const r = rows.get(key) ?? { name: displayName(p), code, total: 0 };
      r.total++;
      rows.set(key, r);
      if (code) modelled.set(code, (modelled.get(code) ?? 0) + 1);
    }
    const hw = step.hardware;
    const extra = hw ? hw.qty - (modelled.get(hw.code) ?? 0) : 0;
    if (hw && extra > 0) {
      const key = `hw:${hw.code}`;
      const r = rows.get(key) ?? { name: partName(HARDWARE_NAMES[hw.code] ?? hw.code), code: hw.code, total: 0 };
      r.total += extra;
      rows.set(key, r);
    }
  }
  return [...rows.values()];
}


export class PartsCard extends THREE.Group {
  private canvas = document.createElement('canvas');
  private ctx: CanvasRenderingContext2D;
  private texture: THREE.CanvasTexture;
  private holder = new THREE.Group();
  private modelKey = '';
  private wood = new THREE.MeshStandardMaterial({ color: '#d8b88a', roughness: 0.8, emissive: '#3a3a3a', emissiveIntensity: 0.35 });
  private steel = new THREE.MeshStandardMaterial({ color: '#dfe3ea', roughness: 0.4, metalness: 0.25, emissive: '#3a3f48', emissiveIntensity: 0.45 });

  constructor(
    _steps: DemoStep[],
    finish?: string,
  ) {
    super();
    if (finish) this.wood.color.set(finish);
    this.canvas.width = CW;
    this.canvas.height = CH;
    this.ctx = this.canvas.getContext('2d')!;
    this.texture = new THREE.CanvasTexture(this.canvas);
    this.texture.colorSpace = THREE.SRGBColorSpace;
    this.texture.anisotropy = 4;
    const mesh = new THREE.Mesh(
      new THREE.PlaneGeometry(CARD_W, CARD_H),
      new THREE.MeshBasicMaterial({ map: this.texture, transparent: true, depthWrite: false, toneMapped: false }),
    );
    mesh.renderOrder = 10;
    this.add(mesh, makeBackPlate(CARD_W, CARD_H, 90));
    // The model floats 2.5 cm in front of the well's centre.
    this.holder.position.set(0, CARD_H / 2 - ((WELL.y + WELL.h / 2) / CH) * CARD_H, 0.025);
    this.add(this.holder);
  }

  /** Redraw for the current state of the build. `done` = every part is in. */
  refresh(assembly: AssemblyView, done: boolean): void {
    const current = done ? [] : assembly.currentParts;
    this.setModel(current[0]);
    const part = current[0];
    const name = !part ? '' : part.kind === 'screw' ? part.name : part.item ? pageName(LANG, part.item) : displayName(part);
    this.draw(name, part, done);
  }

  private setModel(part: DemoPart | undefined): void {
    const key = part?.id ?? '';
    if (key === this.modelKey) return;
    this.modelKey = key;
    this.holder.clear();
    if (!part) return;
    const geo = partGeometry(part);
    geo.computeBoundingBox();
    const dims = geo.boundingBox!.getSize(new THREE.Vector3());
    const mesh = new THREE.Mesh(geo, part.kind === 'screw' ? this.steel : this.wood);
    mesh.scale.setScalar(MODEL_M / Math.max(dims.x, dims.y, dims.z));
    // Long parts lean back a little so their length and section both read.
    if (dims.y >= dims.x && dims.y >= dims.z) mesh.rotation.z = Math.PI / 2.4;
    else if (dims.z >= dims.x) mesh.rotation.y = Math.PI / 2;
    mesh.rotation.x = 0.35;
    mesh.add(
      new THREE.LineSegments(
        new THREE.EdgesGeometry(geo, part.kind === 'screw' ? 40 : 1),
        new THREE.LineBasicMaterial({ color: '#000000', transparent: true, opacity: 0.25 }),
      ),
    );
    this.holder.add(mesh);
  }

  private draw(currentLabel: string, current: DemoPart | undefined, done = false): void {
    const turning = !current && !done; // a turn action: no part to pick up
    const ctx = this.ctx;
    ctx.clearRect(0, 0, CW, CH);

    // Glass, same recipe as the step panel.
    ctx.save();
    ctx.beginPath();
    ctx.roundRect(2, 2, CW - 4, CH - 4, 48);
    const body = ctx.createLinearGradient(0, 0, 0, CH);
    body.addColorStop(0, 'rgba(46,46,52,0.86)');
    body.addColorStop(1, 'rgba(18,18,22,0.90)');
    ctx.fillStyle = body;
    ctx.fill();
    ctx.clip();
    const sheen = ctx.createLinearGradient(0, 0, 0, CH * 0.4);
    sheen.addColorStop(0, 'rgba(255,255,255,0.10)');
    sheen.addColorStop(1, 'rgba(255,255,255,0)');
    ctx.fillStyle = sheen;
    ctx.fillRect(0, 0, CW, CH * 0.4);
    ctx.restore();
    const rim = ctx.createLinearGradient(0, 0, 0, CH);
    rim.addColorStop(0, 'rgba(255,255,255,0.42)');
    rim.addColorStop(0.5, 'rgba(255,255,255,0.12)');
    rim.addColorStop(1, 'rgba(255,255,255,0.22)');
    ctx.beginPath();
    ctx.roundRect(2, 2, CW - 4, CH - 4, 48);
    ctx.lineWidth = 3;
    ctx.strokeStyle = rim;
    ctx.stroke();

    const textW = CW - PAD * 2;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'alphabetic';

    ctx.beginPath();
    ctx.roundRect(PAD, WELL.y, textW, WELL.h, 32);
    ctx.fillStyle = 'rgba(0,0,0,0.28)';
    ctx.fill();
    if (!current) {
      ctx.font = `600 120px ${FONT}`;
      ctx.fillStyle = turning ? AG_XR.accent : AG_XR.success;
      ctx.textAlign = 'center';
      ctx.fillText(turning ? '↻' : '✓', CW / 2, WELL.y + WELL.h / 2 + 42);
      ctx.textAlign = 'left';
    }
    if (current) {
      ctx.font = `600 48px ${FONT}`;
      ctx.fillStyle = '#ffffff';
      ctx.textAlign = 'center';
      ctx.fillText(fit(ctx, currentLabel, textW), CW / 2, WELL.y + WELL.h + 74);
      ctx.textAlign = 'left';
    }
    this.texture.needsUpdate = true;
  }

  update(dt: number, camera: THREE.Camera): void {
    if (!reducedMotion()) this.holder.rotation.y -= 2 * Math.PI * REV_PER_S * dt;
    // Seen from behind, the card is a plain glass back: hide the model that floats in front.
    const eye = this.worldToLocal(camera.getWorldPosition(new THREE.Vector3()));
    this.holder.visible = eye.z > 0;
  }
}

function fit(ctx: CanvasRenderingContext2D, text: string, maxW: number): string {
  if (ctx.measureText(text).width <= maxW) return text;
  let s = text;
  while (s && ctx.measureText(`${s}…`).width > maxW) s = s.slice(0, -1);
  return `${s.trimEnd()}…`;
}

/** Glass name tag, sized to its text, for "what is this part?" when pointing at it. */
export function makeTag(): { sprite: THREE.Sprite; set(text: string): void } {
  const cv = document.createElement('canvas');
  const ctx = cv.getContext('2d')!;
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, depthWrite: false, depthTest: false, toneMapped: false }));
  sprite.renderOrder = 13;
  sprite.visible = false;
  let last = '';
  const H = 96;
  const heightM = 0.03;
  return {
    sprite,
    set(text: string) {
      if (text === last) return;
      last = text;
      ctx.font = `600 44px ${FONT}`;
      const W = Math.ceil(ctx.measureText(text).width + 56);
      cv.width = W;
      cv.height = H;
      ctx.beginPath();
      ctx.roundRect(2, 2, W - 4, H - 4, (H - 4) / 2);
      ctx.fillStyle = 'rgba(24,24,28,0.94)';
      ctx.fill();
      ctx.lineWidth = 3;
      ctx.strokeStyle = 'rgba(255,255,255,0.4)';
      ctx.stroke();
      ctx.font = `600 44px ${FONT}`;
      ctx.fillStyle = '#ffffff';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(text, W / 2, H / 2 + 2);
      tex.dispose();
      tex.image = cv;
      tex.needsUpdate = true;
      sprite.scale.set((heightM * W) / H, heightM, 1);
    },
  };
}
