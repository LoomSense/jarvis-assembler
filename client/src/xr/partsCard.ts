import * as THREE from 'three';
import { AG_XR, FONT, reducedMotion } from './palette';
import type { DemoPart, DemoStep } from './demoAssembly';
import type { AssemblyView } from './ghost';
import { partGeometry } from './ghost';
import { makeBackPlate } from './panels';
import { HARDWARE_NAMES, displayName } from './stepGuides';
import { partName, t } from './i18n';

// Glass card beside the step panel (same height, same tilt):
//   top    — "PICK UP NOW": a turning 3D copy of the part to find next, its name and code;
//   bottom — "PARTS": every part of the product with its code, how many are in, and ✓.
// Finding the right part in the pile is the hardest moment of flat-pack assembly, so the
// card shows it before the ghost says "put it here".

const CW = 560;
const CH = 920;
export const CARD_W = 0.17;
export const CARD_H = (CARD_W * CH) / CW;
const PAD = 36;
const WELL = { y: 84, h: 236 };
const ROW_H = 58;
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

interface Row {
  key: string;
  name: string;
  code?: string;
  kind: 'board' | 'screw';
  total: number;
  placed: number;
  current: boolean;
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
    private steps: DemoStep[],
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
    this.draw(this.rows(assembly, done), done ? '' : assembly.currentLabel, current[0], done);
  }

  private rows(assembly: AssemblyView, done: boolean): Row[] {
    const rows = new Map<string, Row>();
    const cur = new Set(assembly.currentParts);
    const row = (key: string, init: Omit<Row, 'total' | 'placed' | 'current'>): Row => {
      let r = rows.get(key);
      if (!r) rows.set(key, (r = { ...init, total: 0, placed: 0, current: false }));
      return r;
    };
    this.steps.forEach((step, s) => {
      const modelled = new Map<string, number>();
      for (const p of step.parts) {
        const code = p.kind === 'screw' ? p.name : undefined;
        const r = row(`${p.kind}:${p.name}`, { key: p.name, name: displayName(p), code, kind: p.kind });
        r.total++;
        if (done || assembly.statusOf(p) === 'placed') r.placed++;
        if (cur.has(p)) r.current = true;
        if (code) modelled.set(code, (modelled.get(code) ?? 0) + 1);
      }
      // Hardware the guide counts but the stand-in does not model one by one.
      const hw = step.hardware;
      if (hw) {
        const extra = hw.qty - (modelled.get(hw.code) ?? 0);
        if (extra > 0) {
          const r = row(`screw:${hw.code}`, { key: hw.code, name: partName(HARDWARE_NAMES[hw.code] ?? hw.code), code: hw.code, kind: 'screw' });
          r.total += extra;
          if (done || s < assembly.stepIndex) r.placed += extra;
        }
      }
    });
    return [...rows.values()];
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

  private draw(rows: Row[], currentLabel: string, current: DemoPart | undefined, done = false): void {
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

    // PICK UP NOW
    label(ctx, current ? t('pickNow') : turning ? currentLabel.toUpperCase() : t('allIn'), PAD, PAD + 26, done ? AG_XR.success : AG_XR.accent);
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
    let y = WELL.y + WELL.h + 52;
    if (current) {
      ctx.font = `600 40px ${FONT}`;
      ctx.fillStyle = '#ffffff';
      ctx.fillText(fit(ctx, currentLabel, textW), PAD, y);
      y += 38;
      ctx.font = `400 28px ${FONT}`;
      ctx.fillStyle = AG_XR.textMeta;
      if (current.kind === 'screw') ctx.fillText(current.name, PAD, y);
    }

    // Divider + PARTS
    y = WELL.y + WELL.h + 120;
    ctx.fillStyle = 'rgba(255,255,255,0.12)';
    ctx.fillRect(PAD, y, textW, 2);
    y += 46;
    label(ctx, t('parts'), PAD, y, AG_XR.textMeta);
    y += 18;

    const maxRows = Math.floor((CH - PAD - y) / ROW_H);
    for (const r of rows.slice(0, maxRows)) {
      if (r.current) {
        ctx.beginPath();
        ctx.roundRect(PAD - 14, y + 4, textW + 28, ROW_H - 8, 20);
        ctx.fillStyle = 'rgba(158,207,255,0.16)';
        ctx.fill();
      }
      const cy = y + ROW_H / 2;
      drawIcon(ctx, r.kind, PAD + 18, cy, r.current ? AG_XR.accent : 'rgba(255,255,255,0.7)');
      const complete = r.placed >= r.total;
      ctx.font = `${r.current ? 600 : 500} 28px ${FONT}`;
      ctx.fillStyle = r.current ? '#ffffff' : complete ? 'rgba(255,255,255,0.55)' : AG_XR.text;
      const right = complete ? 40 : 76;
      const codeW = r.code ? 96 : 0;
      ctx.fillText(fit(ctx, r.name, textW - 48 - right - codeW), PAD + 48, cy + 10);
      if (r.code) {
        ctx.font = `400 22px ${FONT}`;
        ctx.fillStyle = AG_XR.textMeta;
        ctx.textAlign = 'right';
        ctx.fillText(r.code, PAD + textW - right - 8, cy + 9);
        ctx.textAlign = 'left';
      }
      if (complete) {
        ctx.beginPath();
        ctx.arc(PAD + textW - 16, cy, 16, 0, Math.PI * 2);
        ctx.fillStyle = AG_XR.success;
        ctx.fill();
        ctx.font = `700 20px ${FONT}`;
        ctx.fillStyle = '#0b0b0d';
        ctx.textAlign = 'center';
        ctx.fillText('✓', PAD + textW - 16, cy + 7);
        ctx.textAlign = 'left';
      } else {
        ctx.font = `600 26px ${FONT}`;
        ctx.fillStyle = r.current ? AG_XR.accent : AG_XR.textMeta;
        ctx.textAlign = 'right';
        ctx.fillText(`${r.placed}/${r.total}`, PAD + textW, cy + 9);
        ctx.textAlign = 'left';
      }
      y += ROW_H;
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

function label(ctx: CanvasRenderingContext2D, text: string, x: number, y: number, color: string): void {
  ctx.font = `600 24px ${FONT}`;
  const c = ctx as CanvasRenderingContext2D & { letterSpacing?: string };
  if ('letterSpacing' in c) c.letterSpacing = '2px';
  ctx.fillStyle = color;
  ctx.fillText(text, x, y);
  if ('letterSpacing' in c) c.letterSpacing = '0px';
}

function fit(ctx: CanvasRenderingContext2D, text: string, maxW: number): string {
  if (ctx.measureText(text).width <= maxW) return text;
  let s = text;
  while (s && ctx.measureText(`${s}…`).width > maxW) s = s.slice(0, -1);
  return `${s.trimEnd()}…`;
}

/** Board: a small slab. Screw: a short rod with a head. */
function drawIcon(ctx: CanvasRenderingContext2D, kind: 'board' | 'screw', cx: number, cy: number, color: string): void {
  ctx.save();
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = 3;
  if (kind === 'board') {
    ctx.beginPath();
    ctx.roundRect(cx - 16, cy - 10, 32, 20, 4);
    ctx.stroke();
  } else {
    ctx.beginPath();
    ctx.roundRect(cx - 16, cy - 3, 28, 6, 3);
    ctx.fill();
    ctx.beginPath();
    ctx.roundRect(cx + 10, cy - 8, 6, 16, 2);
    ctx.fill();
  }
  ctx.restore();
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
