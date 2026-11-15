import * as THREE from 'three';

/** PLAN §6.0: compact hand summary sent to the perception loop at about 10 Hz. */
export interface HandSummary {
  type: 'hands';
  seq: number;
  hand_at_target: boolean;
  still: boolean;
  raised: boolean;
  ts: number;
}

const AT_TARGET_M = 0.05;
const STILL_M = 0.01;
const STILL_WINDOW_MS = 500;
const RAISED_M = 0.3;
const SEND_EVERY_MS = 100;

interface Sample {
  t: number;
  p: THREE.Vector3;
}

/**
 * XR-05. Free, instant checks on the headset, from the WebXR hand joints that three.js
 * exposes on `renderer.xr.getHand(i).joints`. The WebSocket to the perception loop
 * (LIV-01) does not exist yet, so `onSummary` is where it will be wired.
 */
export class HandChecker {
  onSummary?: (s: HandSummary) => void;
  last: HandSummary | null = null;

  private seq = 0;
  private lastSent = 0;
  private history = new Map<THREE.Object3D, Sample[]>();
  private v = new THREE.Vector3();

  constructor(
    private hands: THREE.XRHandSpace[],
    private camera: THREE.Camera,
  ) {}

  update(now: number, target: THREE.Box3 | null): void {
    if (now - this.lastSent < SEND_EVERY_MS) return;
    this.lastSent = now;

    const head = this.camera.getWorldPosition(new THREE.Vector3());
    let tracked = 0;
    let atTarget = false;
    let still = true;
    let raised = false;

    for (const hand of this.hands) {
      const wrist = hand.joints['wrist'];
      const tip = hand.joints['index-finger-tip'];
      if (!wrist || !wrist.visible) {
        this.history.delete(hand);
        continue;
      }
      tracked++;
      const w = wrist.getWorldPosition(new THREE.Vector3());

      if (target && !target.isEmpty()) {
        if (target.distanceToPoint(w) <= AT_TARGET_M) atTarget = true;
        if (tip?.visible && target.distanceToPoint(tip.getWorldPosition(this.v)) <= AT_TARGET_M) atTarget = true;
      }

      if (w.distanceTo(head) < RAISED_M) raised = true;

      const h = this.history.get(hand) ?? [];
      h.push({ t: now, p: w });
      while (h.length && now - h[0].t > STILL_WINDOW_MS) h.shift();
      this.history.set(hand, h);
      const span = now - h[0].t;
      const moved = h.reduce((m, s) => Math.max(m, s.p.distanceTo(w)), 0);
      if (span < STILL_WINDOW_MS * 0.8 || moved >= STILL_M) still = false;
    }

    if (!tracked) still = false;
    this.last = { type: 'hands', seq: this.seq++, hand_at_target: atTarget, still, raised, ts: Date.now() };
    this.onSummary?.(this.last);
  }
}
