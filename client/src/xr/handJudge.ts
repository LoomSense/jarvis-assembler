import * as THREE from 'three';

// What the wearer is about to do, judged on the headset from hand tracking alone (no camera, no server):
//
//   good    a hand is at the ghost: the part is going where the guide puts it        → the ghost turns green
//   wrong   a hand stays at a place where a *different* part goes (another screw's
//           hole, a later board's place) and no hand is at the ghost                 → the ghost turns red
//   none    neither
//
// It knows where the hands are, not what they hold: which part is in the hand is the camera's job (live.ts).
// Both feed the same ghost in xrMain.ts. `?judge=0` turns this off.

export type HandVerdict = 'none' | 'good' | 'wrong';

/** A place where some other part goes, in world space. */
export interface Spot<T = unknown> {
  box: THREE.Box3;
  what: T;
}

const GOOD_M = 0.06; // a probe this close to the ghost's box is "at the ghost"
const WRONG_M = 0.035; // a probe this close to another part's place is "at that place"
const CLEAR_M = 0.09; // ...and it only counts when the hand is this far from the ghost
const GOOD_AFTER_MS = 150;
const GOOD_HOLD_MS = 450;
const WRONG_AFTER_MS = 650; // passing a hand over a place is not a mistake; staying there is
const WRONG_HOLD_MS = 600;

export class HandJudge<T = unknown> {
  verdict: HandVerdict = 'none';
  /** the place the hand is wrongly at, while `verdict` is `wrong` */
  wrongSpot: Spot<T> | null = null;
  /** where that hand is, in world space */
  readonly wrongAt = new THREE.Vector3();

  private goodSince = 0;
  private goodSeen = 0;
  private wrongSince = 0;
  private wrongSeen = 0;
  private candidate: Spot<T> | null = null;

  reset(): void {
    this.verdict = 'none';
    this.wrongSpot = this.candidate = null;
    this.goodSince = this.goodSeen = this.wrongSince = this.wrongSeen = 0;
  }

  /**
   * @param now     milliseconds
   * @param hands   per tracked hand, its probe points in world space (finger tips, wrist)
   * @param target  the ghost's box in world space, or null when the action has no place (a turn)
   * @param others  where the parts that are not due now go
   * @returns true when the verdict changed
   */
  update(now: number, hands: THREE.Vector3[][], target: THREE.Box3 | null, others: Spot<T>[]): boolean {
    const before = this.verdict;
    const beforeSpot = this.wrongSpot;
    if (!target || target.isEmpty()) {
      this.reset();
      return before !== 'none';
    }

    let atGhost = false;
    let at: Spot<T> | null = null;
    let atDist = Infinity;
    let atSize = Infinity;
    const size = new THREE.Vector3();
    for (const probes of hands) {
      if (!probes.length) continue;
      const toGhost = Math.min(...probes.map((p) => target.distanceToPoint(p)));
      if (toGhost <= GOOD_M) atGhost = true;
      if (toGhost < CLEAR_M) continue;
      for (const spot of others) {
        let d = Infinity;
        let probe = probes[0];
        for (const p of probes) {
          const dp = spot.box.distanceToPoint(p);
          if (dp < d) {
            d = dp;
            probe = p;
          }
        }
        if (d > WRONG_M) continue;
        // the nearest place; inside several at once (a screw hole in a board's place), the smallest
        const vol = spot.box.getSize(size).x * size.y * size.z;
        if (d < atDist - 1e-4 || (Math.abs(d - atDist) <= 1e-4 && vol < atSize)) {
          at = spot;
          atDist = d;
          atSize = vol;
          this.wrongAt.copy(probe);
        }
      }
    }

    if (atGhost) {
      this.goodSince ||= now;
      this.goodSeen = now;
    } else if (now - this.goodSeen > GOOD_HOLD_MS) {
      this.goodSince = 0;
    }
    // staying at the same wrong place; moving to another one starts the count again
    if (at && !atGhost) {
      if (at.what !== this.candidate?.what) {
        this.candidate = at;
        this.wrongSince = now;
      }
      this.wrongSeen = now;
    } else if (atGhost || now - this.wrongSeen > WRONG_HOLD_MS) {
      this.candidate = null;
      this.wrongSince = 0;
    }

    if (this.goodSince && now - this.goodSince >= GOOD_AFTER_MS) {
      this.verdict = 'good';
      this.wrongSpot = null;
    } else if (this.candidate && now - this.wrongSince >= WRONG_AFTER_MS) {
      this.verdict = 'wrong';
      this.wrongSpot = this.candidate;
    } else {
      this.verdict = 'none';
      this.wrongSpot = null;
    }
    return this.verdict !== before || this.wrongSpot?.what !== beforeSpot?.what;
  }
}
