import * as THREE from 'three';
import { AG_XR, FONT, reducedMotion } from './palette';
import type { DemoPart, DemoStep } from './demoAssembly';
import { LANG, partName, t, type Lang } from './i18n';

// In-world guidance for one action (one part), in the plate's frame (metres):
// - push parts: a rounded 3D arrow along the insertion path, ending just short of the ghost;
// - twist parts (screws, LACK legs): a circular arrow turning clockwise around the part,
//   like the turning arrows printed in the guide;
// - a pulsing ring on every screw hole;
// - a glass callout pill above the part with a leader line ("① Leg 2 of 4").
// Motion stays at or under 1 Hz (BRAND.md §5); reduced motion freezes it.

const PULSE_HZ = 0.8;
const TWIST_REV_PER_S = 0.5;

/** Plain names for hardware codes, so the user reads "Long screw", not only "108363". */
export const HARDWARE_NAMES: Record<string, string> = {
  '108363': 'Long screw',
  '108560': 'Short screw',
  '115980': 'Double screw',
};

export function hardwareLabel(code: string, qty: number, lang: Lang = LANG): string {
  const name = HARDWARE_NAMES[code];
  return name ? `${qty}× ${partName(name, lang)} · ${code}` : `${code} ×${qty}`;
}

/** Name the user knows a part by: screws by their hardware name, boards by their own. */
export function displayName(part: DemoPart, lang: Lang = LANG): string {
  return partName(part.kind === 'screw' ? (HARDWARE_NAMES[part.name] ?? part.name) : part.name, lang);
}

export function motionOf(part: DemoPart): 'push' | 'twist' {
  return part.motion ?? (part.kind === 'screw' ? 'twist' : 'push');
}

/** "Leg 2 of 4" for action `i` of a step, or just "Bottom" when the step adds one of it. */
export function actionLabel(step: DemoStep, i: number, lang: Lang = LANG): string {
  const moving = step.parts.filter((p) => !p.base);
  const part = moving[i];
  if (!part) return '';
  const name = displayName(part, lang);
  const same = moving.filter((p) => displayName(p, lang) === name);
  return same.length > 1 ? t('of', { name, i: same.indexOf(part) + 1, n: same.length }, lang) : name;
}

/** What the whole step needs, in plain words: "2× End board · 8 screws", "4× Double screw". */
export function calloutText(step: DemoStep, lang: Lang = LANG): string {
  const counts = new Map<string, number>();
  for (const p of step.parts) {
    if (p.kind !== 'board' || p.base) continue;
    const name = displayName(p, lang);
    counts.set(name, (counts.get(name) ?? 0) + 1);
  }
  const boards = [...counts].map(([name, k]) => (k > 1 ? `${k}× ${name}` : name)).join(' + ');
  if (!step.hardware) return boards;
  const hw = `${step.hardware.qty}× ${partName(HARDWARE_NAMES[step.hardware.code] ?? step.hardware.code, lang)}`;
  return boards ? `${boards} · ${t('screws', { n: step.hardware.qty }, lang)}` : hw;
}

function halfExtents(part: DemoPart): THREE.Vector3 {
  if (part.half) return new THREE.Vector3(...part.half);
  if (part.kind === 'board') return new THREE.Vector3(...part.size).multiplyScalar(0.5);
  const r = part.size[0] / 2;
  const h = part.size[1] / 2;
  return new THREE.Vector3(part.axis === 'x' ? h : r, part.axis === 'z' || part.axis === 'x' ? r : h, part.axis === 'z' ? h : r);
}

interface Arrow {
  root: THREE.Group;
  dir: THREE.Vector3;
  base: THREE.Vector3;
}

interface Twist {
  root: THREE.Group;
  base: THREE.Quaternion;
}

export class StepGuides extends THREE.Group {
  private arrows: Arrow[] = [];
  private twists: Twist[] = [];
  private rings: THREE.Mesh[] = [];
  private arrowMat = new THREE.MeshBasicMaterial({ color: '#e6f3ff', transparent: true, opacity: 0.95, depthWrite: false });
  private ringMat = new THREE.MeshBasicMaterial({ color: AG_XR.accent, transparent: true, opacity: 0.9, side: THREE.DoubleSide, depthWrite: false });
  private callout: THREE.Group;

  /** `code`: the part's printed code (screws), shown after the name in a lighter colour. */
  constructor(parts: DemoPart[], stepNumber: number, label: string, code?: string) {
    super();
    for (const part of parts) {
      if (part.base) continue;
      if (motionOf(part) === 'twist') this.addTwist(part);
      else this.addArrow(part);
      if (part.kind === 'screw') this.addRing(part);
    }
    this.callout = makeCallout(label, stepNumber, code);
    this.add(this.callout);
  }

  private addArrow(part: DemoPart): void {
    const u = new THREE.Vector3(...part.insertFrom);
    if (u.lengthSq() < 1e-8) return;
    u.normalize();
    const half = halfExtents(part);
    const extent = Math.abs(u.x) * half.x + Math.abs(u.y) * half.y + Math.abs(u.z) * half.z;
    const gap = 0.014;
    const len = THREE.MathUtils.clamp(new THREE.Vector3(...part.insertFrom).length() * 0.8, 0.07, 0.12);
    const r = 0.0065;
    const headLen = 0.032;

    // Built along +Y with the tip at the origin, then turned so +Y points along -u (towards the part).
    const root = new THREE.Group();
    const shaft = new THREE.Mesh(new THREE.CapsuleGeometry(r, len - headLen - 2 * r, 6, 16), this.arrowMat);
    shaft.position.y = -headLen - (len - headLen) / 2 + r * 0.5;
    const head = new THREE.Mesh(new THREE.ConeGeometry(r * 2.6, headLen, 24), this.arrowMat);
    head.position.y = -headLen / 2;
    root.add(shaft, head);
    root.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), u.clone().negate());
    const tip = new THREE.Vector3(...part.pos).addScaledVector(u, extent + gap);
    root.position.copy(tip);
    shaft.renderOrder = head.renderOrder = 6;
    this.add(root);
    this.arrows.push({ root, dir: u.clone().negate(), base: tip });
  }

  /** Circular arrow around the insertion axis, turning clockwise seen from where the part comes from. */
  private addTwist(part: DemoPart): void {
    const u = new THREE.Vector3(...part.insertFrom);
    if (u.lengthSq() < 1e-8) u.set(0, 1, 0);
    u.normalize();
    const half = halfExtents(part);
    const screw = part.kind === 'screw';
    // Radius: just outside the part's cross-section.
    const perp = new THREE.Vector3(half.x * (1 - Math.abs(u.x)), half.y * (1 - Math.abs(u.y)), half.z * (1 - Math.abs(u.z)));
    const R = screw ? 0.017 : perp.length() + 0.014;
    const tube = screw ? 0.0024 : 0.004;
    const along = Math.abs(u.x) * half.x + Math.abs(u.y) * half.y + Math.abs(u.z) * half.z;
    // Screws: just outside the hole. Twisted boards (legs): near the joint end.
    const center = new THREE.Vector3(...part.pos).addScaledVector(u, screw ? along + 0.01 : -along + 0.05);

    const root = new THREE.Group();
    const arc = new THREE.Mesh(new THREE.TorusGeometry(R, tube, 10, 56, Math.PI * 1.6), this.arrowMat);
    const head = new THREE.Mesh(new THREE.ConeGeometry(tube * 2.8, tube * 7, 20), this.arrowMat);
    head.position.set(R, -tube * 2.5, 0);
    head.rotation.z = Math.PI; // points along -Y: the clockwise end of the arc
    arc.renderOrder = head.renderOrder = 6;
    root.add(arc, head);
    root.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, 1), u);
    root.position.copy(center);
    this.add(root);
    this.twists.push({ root, base: root.quaternion.clone() });
  }

  private addRing(part: DemoPart): void {
    const u = new THREE.Vector3(...part.insertFrom).normalize();
    const ring = new THREE.Mesh(new THREE.RingGeometry(0.0055, 0.0085, 40), this.ringMat);
    ring.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, 1), u);
    ring.position.set(...part.pos).addScaledVector(u, part.size[1] / 2 + 0.0015);
    ring.renderOrder = 6;
    this.add(ring);
    this.rings.push(ring);
  }

  /**
   * Place the callout above the action's part. `box` is in the view's (unturned) frame;
   * `toLocal` and `unturn` map into this group's turned frame so the label stays upright.
   */
  placeCallout(box: THREE.Box3, toLocal?: THREE.Matrix4, unturn?: THREE.Quaternion): void {
    if (box.isEmpty()) return;
    const top = new THREE.Vector3((box.min.x + box.max.x) / 2, box.max.y, (box.min.z + box.max.z) / 2);
    if (toLocal) top.applyMatrix4(toLocal);
    this.callout.position.copy(top);
    if (unturn) this.callout.quaternion.copy(unturn);
  }

  update(time: number): void {
    if (reducedMotion()) {
      this.arrowMat.opacity = 0.95;
      for (const a of this.arrows) a.root.position.copy(a.base);
      for (const tw of this.twists) tw.root.quaternion.copy(tw.base);
      for (const r of this.rings) r.scale.setScalar(1);
      this.ringMat.opacity = 0.9;
      return;
    }
    // Straight arrows glide 3 cm towards the part and fade out at the end of each cycle.
    const k = (time * PULSE_HZ) % 1;
    const e = 1 - Math.pow(1 - Math.min(1, k / 0.75), 3);
    for (const a of this.arrows) a.root.position.copy(a.base).addScaledVector(a.dir, -0.03 * (1 - e));
    this.arrowMat.opacity = this.twists.length
      ? 0.95
      : 0.95 * (k < 0.8 ? 1 : 1 - (k - 0.8) / 0.2) * Math.min(1, k / 0.12 + 0.15);
    // Circular arrows turn clockwise, steadily.
    const spin = new THREE.Quaternion();
    const axis = new THREE.Vector3(0, 0, 1);
    for (const tw of this.twists) {
      spin.setFromAxisAngle(axis, -2 * Math.PI * TWIST_REV_PER_S * time);
      tw.root.quaternion.copy(tw.base).multiply(spin);
    }
    // Ring grows and fades, like a ripple on the hole.
    for (const r of this.rings) r.scale.setScalar(1 + 0.8 * k);
    this.ringMat.opacity = 0.9 * (1 - k);
  }
}

/** Glass pill with a numbered circle, floating 8 cm above the part, plus a leader line. */
function makeCallout(text: string, n: number, code?: string): THREE.Group {
  const H = 128;
  const cv = document.createElement('canvas');
  const ctx = cv.getContext('2d')!;
  ctx.font = `600 52px ${FONT}`;
  const textW = ctx.measureText(text).width;
  const sub = code ? ` · ${code}` : '';
  ctx.font = `500 44px ${FONT}`;
  const subW = sub ? ctx.measureText(sub).width : 0;
  const W = Math.ceil(Math.min(1200, textW + subW + 132 + 40));
  cv.width = W;
  cv.height = H;
  const draw = () => {
    ctx.clearRect(0, 0, W, H);
    ctx.beginPath();
    ctx.roundRect(3, 3, W - 6, H - 6, (H - 6) / 2);
    const g = ctx.createLinearGradient(0, 0, 0, H);
    g.addColorStop(0, 'rgba(50,50,56,0.92)');
    g.addColorStop(1, 'rgba(20,20,24,0.92)');
    ctx.fillStyle = g;
    ctx.fill();
    ctx.lineWidth = 3;
    ctx.strokeStyle = 'rgba(255,255,255,0.32)';
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(H / 2, H / 2, 40, 0, Math.PI * 2);
    ctx.fillStyle = AG_XR.accent;
    ctx.fill();
    ctx.fillStyle = '#0b0b0d';
    ctx.font = `700 46px ${FONT}`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(String(n), H / 2, H / 2 + 2);
    ctx.textAlign = 'left';
    ctx.fillStyle = '#ffffff';
    ctx.font = `600 52px ${FONT}`;
    ctx.fillText(text, H + 10, H / 2 + 2);
    if (sub) {
      ctx.font = `500 44px ${FONT}`;
      ctx.fillStyle = 'rgba(255,255,255,0.62)';
      ctx.fillText(sub, H + 10 + textW, H / 2 + 3);
    }
    tex.needsUpdate = true;
  };
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  draw();
  document.fonts?.ready.then(draw);

  const group = new THREE.Group();
  const heightM = 0.036;
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, depthWrite: false, depthTest: false, toneMapped: false }));
  sprite.scale.set((heightM * W) / H, heightM, 1);
  sprite.position.y = 0.085;
  sprite.renderOrder = 12;
  const line = new THREE.Line(
    new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(0, 0.006, 0), new THREE.Vector3(0, 0.085 - heightM / 2, 0)]),
    new THREE.LineBasicMaterial({ color: '#ffffff', transparent: true, opacity: 0.55, depthWrite: false }),
  );
  const dot = new THREE.Mesh(new THREE.SphereGeometry(0.004, 16, 12), new THREE.MeshBasicMaterial({ color: '#ffffff' }));
  dot.position.y = 0.006;
  group.add(line, dot, sprite);
  return group;
}
