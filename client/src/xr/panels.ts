import * as THREE from 'three';
import { AG_XR, FONT, STATUS_COLOR, type Status } from './palette';

// BRAND.md §5 said 0.40 x 0.28 m; enlarged to 0.50 x 0.35 m (+25 %, user request Sun 15 Nov) at 0.8 m, same 1024 x 717 px canvas (1 px ≈ 0.49 mm), so text and buttons grow with it.
// Text: meta 28 px (0.7°), body 40 px (1.1°), title 56–64 px (≥ 1.6°). Max 2 lines of instruction.
// Look: a dark frosted-glass window (soft top sheen, light rim, large continuous corners),
// text column on the left, the guide drawing on the right as white line art on the glass,
// and capsule buttons along the bottom. No pure-white area larger than a button.
const CW = 1024;
const CH = 717;
const PAD = 44;
const RADIUS = 56;
const BTN_H = 100; // ≈ 3.9 cm
const BTN_GAP = 18; // ≈ 0.7 cm
const IMG_W = 330;
const COL_GAP = 32;

export interface PanelButton {
  id: string;
  label: string;
  primary?: boolean;
  /** live guidance thinks the step is done: a second ring around the button, the wearer decides */
  cue?: boolean;
}

export interface PanelContent {
  status: Status;
  meta?: string;
  title: string;
  body?: string;
  chips?: string[];
  /** The current action, shown first in the chip row as a filled pill ("Leg 2 of 4"). */
  focus?: string;
  foot?: string;
  /** Guide drawing for this step (URL). Drawn as white line art on the glass. */
  image?: string;
  /** Keep the drawing's well empty and dark: a live camera picture is laid over it (StepPanel.WELL). */
  video?: boolean;
  /** Page-control dots at the top right of the text column. */
  progress?: { index: number; total: number };
  /** Pill at the top right, e.g. "Vision paused · say resume". */
  badge?: { text: string; status: Status };
  buttons: PanelButton[];
}

interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

let fontsReady: Promise<void> | null = null;
export function loadPanelFonts(): Promise<void> {
  fontsReady ??= Promise.race([
    Promise.all([
      document.fonts.load(`600 64px ${FONT}`),
      document.fonts.load(`500 40px ${FONT}`),
      document.fonts.load(`400 40px ${FONT}`),
    ]).then(() => {}),
    new Promise<void>((r) => setTimeout(r, 2000)),
  ]).catch(() => {});
  return fontsReady;
}

/**
 * Guide crops are black line art on white. Turn each into white lines on a transparent
 * background (alpha = darkness) so it sits on the dark glass without a white block.
 */
const lineArt = new Map<string, HTMLCanvasElement | 'loading' | 'failed'>();
function getLineArt(url: string, onReady: () => void): HTMLCanvasElement | null {
  const cached = lineArt.get(url);
  if (cached instanceof HTMLCanvasElement) return cached;
  if (cached) return null;
  lineArt.set(url, 'loading');
  const img = new Image();
  img.crossOrigin = 'anonymous';
  img.onload = () => {
    const cv = document.createElement('canvas');
    cv.width = img.naturalWidth;
    cv.height = img.naturalHeight;
    const c = cv.getContext('2d', { willReadFrequently: true })!;
    c.drawImage(img, 0, 0);
    try {
      const d = c.getImageData(0, 0, cv.width, cv.height);
      const px = d.data;
      for (let i = 0; i < px.length; i += 4) {
        const lum = 0.2126 * px[i] + 0.7152 * px[i + 1] + 0.0722 * px[i + 2];
        const a = Math.min(255, Math.max(0, (255 - lum) * 1.35));
        px[i] = px[i + 1] = px[i + 2] = 255;
        px[i + 3] = (a * px[i + 3]) / 255;
      }
      c.putImageData(d, 0, 0);
    } catch {
      // Cross-origin image without CORS: keep the original pixels.
    }
    lineArt.set(url, cv);
    onReady();
  };
  img.onerror = () => {
    lineArt.set(url, 'failed');
    onReady();
  };
  img.src = url;
  return null;
}

/** Status shapes from BRAND.md §3: circle, check circle, triangle, octagon, diamond. */
export function drawStatusIcon(ctx: CanvasRenderingContext2D, status: Status, cx: number, cy: number, r: number): void {
  ctx.save();
  ctx.fillStyle = STATUS_COLOR[status];
  ctx.beginPath();
  if (status === 'warn') {
    ctx.moveTo(cx, cy - r);
    ctx.lineTo(cx + r * 1.05, cy + r * 0.85);
    ctx.lineTo(cx - r * 1.05, cy + r * 0.85);
  } else if (status === 'error') {
    for (let i = 0; i < 8; i++) {
      const a = Math.PI / 8 + (i * Math.PI) / 4;
      ctx.lineTo(cx + r * Math.cos(a), cy + r * Math.sin(a));
    }
  } else if (status === 'unsure') {
    ctx.moveTo(cx, cy - r);
    ctx.lineTo(cx + r, cy);
    ctx.lineTo(cx, cy + r);
    ctx.lineTo(cx - r, cy);
  } else {
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
  }
  ctx.closePath();
  ctx.fill();

  const glyph = { info: '', success: '✓', warn: '!', error: '✕', unsure: '?' }[status];
  if (glyph) {
    ctx.fillStyle = AG_XR.panelBg;
    ctx.font = `700 ${Math.round(r * 1.2)}px ${FONT}`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(glyph, cx, cy + (status === 'warn' ? r * 0.22 : r * 0.05));
  }
  ctx.restore();
}

function wrap(ctx: CanvasRenderingContext2D, text: string, maxW: number, maxLines: number): string[] {
  const words = text.split(/\s+/);
  const lines: string[] = [];
  let line = '';
  for (const w of words) {
    const next = line ? `${line} ${w}` : w;
    if (ctx.measureText(next).width <= maxW) {
      line = next;
      continue;
    }
    if (line) lines.push(line);
    line = w;
    if (lines.length === maxLines) break;
  }
  if (lines.length < maxLines && line) lines.push(line);
  if (lines.length > maxLines) lines.length = maxLines;
  const consumed = lines.join(' ').length;
  if (consumed < text.trim().length) {
    let last = lines[lines.length - 1] ?? '';
    while (last && ctx.measureText(`${last}…`).width > maxW) last = last.slice(0, -1);
    lines[lines.length - 1] = `${last.trimEnd()}…`;
  }
  return lines;
}

function tracking(ctx: CanvasRenderingContext2D, px: number): void {
  const c = ctx as CanvasRenderingContext2D & { letterSpacing?: string };
  if ('letterSpacing' in c) c.letterSpacing = `${px}px`;
}

/** A world-anchored canvas-texture panel with pinchable buttons on its bottom row. */
export class StepPanel extends THREE.Group {
  static readonly WIDTH = 0.5;
  static readonly HEIGHT = 0.35;
  /** The drawing's well in metres, centre and size, in the panel's frame (for the camera picture of the scan). */
  static readonly WELL = (() => {
    const k = StepPanel.WIDTH / CW;
    const x = CW - PAD - IMG_W;
    const h = CH - PAD - BTN_H - 24 - PAD;
    return { x: (x + IMG_W / 2 - CW / 2) * k, y: (CH / 2 - (PAD + h / 2)) * k, w: IMG_W * k, h: h * k };
  })();

  readonly mesh: THREE.Mesh;
  private canvas = document.createElement('canvas');
  private ctx: CanvasRenderingContext2D;
  private texture: THREE.CanvasTexture;
  private content: PanelContent = { status: 'info', title: '', buttons: [] };
  private rects = new Map<string, Rect>();
  private hovered: string | null = null;
  private pressed: string | null = null;

  constructor() {
    super();
    this.canvas.width = CW;
    this.canvas.height = CH;
    this.ctx = this.canvas.getContext('2d')!;
    this.texture = new THREE.CanvasTexture(this.canvas);
    this.texture.colorSpace = THREE.SRGBColorSpace;
    this.texture.anisotropy = 4;
    this.texture.generateMipmaps = true;
    this.texture.minFilter = THREE.LinearMipmapLinearFilter;
    this.mesh = new THREE.Mesh(
      new THREE.PlaneGeometry(StepPanel.WIDTH, StepPanel.HEIGHT),
      new THREE.MeshBasicMaterial({ map: this.texture, transparent: true, depthWrite: false, toneMapped: false }),
    );
    this.mesh.renderOrder = 10;
    this.add(this.mesh);
    this.add(makeBackPlate(StepPanel.WIDTH, StepPanel.HEIGHT));
    loadPanelFonts().then(() => this.draw());
  }

  setContent(c: PanelContent): void {
    this.content = c;
    if (this.hovered && !c.buttons.some((b) => b.id === this.hovered)) this.hovered = null;
    this.draw();
  }

  setHovered(id: string | null): void {
    if (id === this.hovered) return;
    this.hovered = id;
    this.draw();
  }

  /** Visual press feedback: scale 0.96 for 120 ms. */
  press(id: string): void {
    this.pressed = id;
    this.draw();
    setTimeout(() => {
      this.pressed = null;
      this.draw();
    }, 120);
  }

  /** Button id under a ray hit on this panel, if any. */
  buttonAt(hit: THREE.Intersection | undefined): string | null {
    if (!hit || hit.object !== this.mesh || !hit.uv) return null;
    const x = hit.uv.x * CW;
    const y = (1 - hit.uv.y) * CH;
    for (const [id, r] of this.rects) {
      if (x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + r.h) return id;
    }
    return null;
  }

  private drawGlass(): void {
    const ctx = this.ctx;
    ctx.save();
    ctx.beginPath();
    ctx.roundRect(2, 2, CW - 4, CH - 4, RADIUS);
    // Body: dark glass, slightly lighter at the top. Dark enough for 7:1 white text over
    // bright passthrough (WebXR cannot blur the camera feed, so we darken instead).
    const body = ctx.createLinearGradient(0, 0, 0, CH);
    body.addColorStop(0, 'rgba(46,46,52,0.86)');
    body.addColorStop(1, 'rgba(18,18,22,0.90)');
    ctx.fillStyle = body;
    ctx.fill();
    ctx.clip();
    // Soft sheen across the top third.
    const sheen = ctx.createLinearGradient(0, 0, 0, CH * 0.45);
    sheen.addColorStop(0, 'rgba(255,255,255,0.10)');
    sheen.addColorStop(1, 'rgba(255,255,255,0)');
    ctx.fillStyle = sheen;
    ctx.fillRect(0, 0, CW, CH * 0.45);
    ctx.restore();
    // Rim light: brighter on the top edge, fading towards the bottom.
    const rim = ctx.createLinearGradient(0, 0, 0, CH);
    rim.addColorStop(0, 'rgba(255,255,255,0.42)');
    rim.addColorStop(0.5, 'rgba(255,255,255,0.12)');
    rim.addColorStop(1, 'rgba(255,255,255,0.22)');
    ctx.beginPath();
    ctx.roundRect(2, 2, CW - 4, CH - 4, RADIUS);
    ctx.lineWidth = 3;
    ctx.strokeStyle = rim;
    ctx.stroke();
  }

  private drawImageWell(r: Rect, url: string): void {
    const ctx = this.ctx;
    ctx.save();
    ctx.beginPath();
    ctx.roundRect(r.x, r.y, r.w, r.h, 36);
    ctx.fillStyle = 'rgba(0,0,0,0.30)';
    ctx.fill();
    ctx.lineWidth = 2;
    ctx.strokeStyle = 'rgba(255,255,255,0.08)';
    ctx.stroke();
    ctx.clip();
    const art = getLineArt(url, () => this.draw());
    if (art) {
      const inset = 22;
      const s = Math.min((r.w - inset * 2) / art.width, (r.h - inset * 2) / art.height);
      const w = art.width * s;
      const h = art.height * s;
      ctx.globalAlpha = 0.94;
      ctx.drawImage(art, r.x + (r.w - w) / 2, r.y + (r.h - h) / 2, w, h);
    } else {
      ctx.font = `400 28px ${FONT}`;
      ctx.fillStyle = AG_XR.textMeta;
      ctx.textAlign = 'center';
      ctx.fillText(lineArt.get(url) === 'failed' ? 'No drawing' : 'Loading drawing', r.x + r.w / 2, r.y + r.h / 2);
    }
    ctx.restore();
  }

  private draw(): void {
    const ctx = this.ctx;
    const c = this.content;
    ctx.clearRect(0, 0, CW, CH);
    this.drawGlass();

    ctx.textBaseline = 'alphabetic';
    ctx.textAlign = 'left';
    const btnY = CH - PAD - BTN_H;
    // a drawing that could not be loaded leaves no empty well behind
    const image = c.image && lineArt.get(c.image) !== 'failed' ? c.image : undefined;
    const colW = CW - PAD * 2 - (image || c.video ? IMG_W + COL_GAP : 0);
    let y = PAD + 4;

    if (image) this.drawImageWell({ x: CW - PAD - IMG_W, y: PAD, w: IMG_W, h: btnY - PAD - 24 }, image);
    else if (c.video) {
      ctx.beginPath();
      ctx.roundRect(CW - PAD - IMG_W, PAD, IMG_W, btnY - PAD - 24, 36);
      ctx.fillStyle = 'rgba(0,0,0,0.45)';
      ctx.fill();
    }

    // Meta row: status shape + "Step 5 of 8 · Page 5", optional badge on the right.
    drawStatusIcon(ctx, c.status, PAD + 15, y + 17, 15);
    ctx.font = `500 28px ${FONT}`;
    tracking(ctx, 0.2);
    ctx.fillStyle = c.status === 'info' ? AG_XR.textMeta : STATUS_COLOR[c.status];
    let metaW = colW - 44;
    if (c.badge) {
      const w = ctx.measureText(c.badge.text).width + 76;
      const bx = PAD + colW - w;
      ctx.beginPath();
      ctx.roundRect(bx, y - 6, w, 48, 24);
      ctx.fillStyle = 'rgba(255,255,255,0.12)';
      ctx.fill();
      drawStatusIcon(ctx, c.badge.status, bx + 28, y + 18, 13);
      ctx.fillStyle = STATUS_COLOR[c.badge.status];
      ctx.fillText(c.badge.text, bx + 52, y + 28);
      ctx.fillStyle = c.status === 'info' ? AG_XR.textMeta : STATUS_COLOR[c.status];
      metaW -= w + 16;
    }
    if (c.progress && !c.badge) {
      const { index, total } = c.progress;
      const dot = 12;
      const active = 34;
      const gap = 10;
      const w = (total - 1) * (dot + gap) + active;
      let x = PAD + colW - w;
      for (let i = 0; i < total; i++) {
        const dw = i === index ? active : dot;
        ctx.beginPath();
        ctx.roundRect(x, y + 11, dw, dot, dot / 2);
        ctx.fillStyle = i === index ? '#ffffff' : i < index ? 'rgba(255,255,255,0.55)' : 'rgba(255,255,255,0.22)';
        ctx.fill();
        x += dw + gap;
      }
      ctx.fillStyle = c.status === 'info' ? AG_XR.textMeta : STATUS_COLOR[c.status];
      metaW -= w + 20;
    }
    if (c.meta) ctx.fillText(wrap(ctx, c.meta, metaW, 1)[0] ?? '', PAD + 44, y + 28);
    y += 52;

    // Title: up to two lines, tight tracking. Shrink to 56 px before wrapping to a 2nd line.
    ctx.fillStyle = AG_XR.textStrong;
    tracking(ctx, -1.2);
    let titleSize = 64;
    ctx.font = `600 ${titleSize}px ${FONT}`;
    if (ctx.measureText(c.title).width > colW) {
      titleSize = 56;
      ctx.font = `600 ${titleSize}px ${FONT}`;
    }
    for (const line of wrap(ctx, c.title, colW, 2)) {
      y += titleSize + 6;
      ctx.fillText(line, PAD, y);
    }
    y += 20;

    // Instruction, at most 2 lines.
    tracking(ctx, 0);
    if (c.body) {
      ctx.font = `400 40px ${FONT}`;
      ctx.fillStyle = AG_XR.text;
      for (const line of wrap(ctx, c.body, colW, 2)) {
        y += 50;
        ctx.fillText(line, PAD, y);
      }
      y += 22;
    }

    // Hardware chips.
    const footTop = c.foot ? btnY - 66 : btnY - 20;
    if ((c.chips?.length || c.focus) && y + 52 <= footTop) {
      let x = PAD;
      if (c.focus) {
        ctx.font = `600 30px ${FONT}`;
        const w = ctx.measureText(c.focus).width + 48;
        ctx.beginPath();
        ctx.roundRect(x, y, w, 52, 26);
        ctx.fillStyle = AG_XR.accent;
        ctx.fill();
        ctx.fillStyle = '#0b0b0d';
        ctx.fillText(c.focus, x + 24, y + 36);
        x += w + 12;
      }
      ctx.font = `500 30px ${FONT}`;
      for (const chip of c.chips ?? []) {
        const w = ctx.measureText(chip).width + 44;
        if (x + w > PAD + colW) {
          // Next row, if there is room above the footer.
          if (y + 52 + 12 + 52 > footTop || x === PAD) break;
          x = PAD;
          y += 64;
        }
        ctx.beginPath();
        ctx.roundRect(x, y, w, 52, 26);
        ctx.fillStyle = 'rgba(255,255,255,0.13)';
        ctx.fill();
        ctx.fillStyle = AG_XR.textStrong;
        ctx.fillText(chip, x + 22, y + 36);
        x += w + 12;
      }
    }

    // Footer meta line, one line, always clear of the buttons.
    if (c.foot) {
      ctx.font = `400 28px ${FONT}`;
      ctx.fillStyle = AG_XR.textMeta;
      ctx.fillText(wrap(ctx, c.foot, colW, 1)[0] ?? '', PAD, btnY - 26);
    }

    // Capsule buttons across the bottom row. Secondary: light glass. Primary: solid light pill.
    this.rects.clear();
    const n = c.buttons.length;
    if (n) {
      const rowW = CW - PAD * 2;
      const bw = (rowW - BTN_GAP * (n - 1)) / n;
      c.buttons.forEach((b, i) => {
        const r: Rect = { x: PAD + i * (bw + BTN_GAP), y: btnY, w: bw, h: BTN_H };
        this.rects.set(b.id, r);
        const hover = this.hovered === b.id;
        const s = this.pressed === b.id ? 0.96 : 1;
        const dx = (r.w * (1 - s)) / 2;
        const dy = (r.h * (1 - s)) / 2;
        ctx.beginPath();
        ctx.roundRect(r.x + dx, r.y + dy, r.w * s, r.h * s, BTN_H / 2);
        if (b.primary) {
          ctx.fillStyle = hover ? '#ffffff' : '#ececf0';
          ctx.fill();
          if (hover) {
            ctx.lineWidth = 4;
            ctx.strokeStyle = AG_XR.accent;
            ctx.stroke();
          }
        } else {
          ctx.fillStyle = hover ? 'rgba(255,255,255,0.30)' : 'rgba(255,255,255,0.14)';
          ctx.fill();
          ctx.lineWidth = 2;
          ctx.strokeStyle = hover ? 'rgba(255,255,255,0.6)' : 'rgba(255,255,255,0.10)';
          ctx.stroke();
        }
        if (b.cue) {
          ctx.beginPath();
          ctx.roundRect(r.x - 9, r.y - 9, r.w + 18, r.h + 18, BTN_H / 2 + 9);
          ctx.lineWidth = 4;
          ctx.strokeStyle = STATUS_COLOR.success;
          ctx.stroke();
        }
        ctx.font = `${b.primary ? 600 : 500} 36px ${FONT}`;
        // five buttons in a row: a long label shrinks to 30 px (BRAND minimum 28) before it is cut
        if (ctx.measureText(b.label).width > r.w - 24) ctx.font = `${b.primary ? 600 : 500} 30px ${FONT}`;
        ctx.fillStyle = b.primary ? '#0b0b0d' : AG_XR.textStrong;
        ctx.textAlign = 'center';
        ctx.fillText(wrap(ctx, b.label, r.w - 24, 1)[0] ?? '', r.x + r.w / 2, r.y + r.h / 2 + 13);
        ctx.textAlign = 'left';
      });
    }

    this.texture.needsUpdate = true;
  }
}

/**
 * Back side for a world-anchored canvas panel: the same dark glass, no text, so a panel seen
 * from behind still reads as a panel instead of vanishing (its front texture is single-sided).
 */
export function makeBackPlate(w: number, h: number, radiusPx = 56): THREE.Mesh {
  const W = 512;
  const H = Math.round((512 * h) / w);
  const cv = document.createElement('canvas');
  cv.width = W;
  cv.height = H;
  const ctx = cv.getContext('2d')!;
  const r = (radiusPx * W) / 1024;
  ctx.beginPath();
  ctx.roundRect(1, 1, W - 2, H - 2, r);
  const g = ctx.createLinearGradient(0, 0, 0, H);
  g.addColorStop(0, 'rgba(40,40,46,0.88)');
  g.addColorStop(1, 'rgba(16,16,20,0.92)');
  ctx.fillStyle = g;
  ctx.fill();
  ctx.lineWidth = 2;
  ctx.strokeStyle = 'rgba(255,255,255,0.22)';
  ctx.stroke();
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  const mesh = new THREE.Mesh(
    new THREE.PlaneGeometry(w, h),
    new THREE.MeshBasicMaterial({ map: tex, transparent: true, depthWrite: false, toneMapped: false }),
  );
  mesh.rotation.y = Math.PI;
  mesh.position.z = -0.001;
  mesh.renderOrder = 9;
  return mesh;
}

/** Small billboard badge (✓ / ✕ / ?) shown above the ghost part. */
export function makeBadge(status: Status): THREE.Sprite {
  const cv = document.createElement('canvas');
  cv.width = cv.height = 128;
  const ctx = cv.getContext('2d')!;
  drawStatusIcon(ctx, status, 64, 64, 56);
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, depthWrite: false, toneMapped: false }));
  sprite.scale.setScalar(0.045);
  sprite.renderOrder = 11;
  return sprite;
}
