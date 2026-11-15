import { createAssist, withoutCodes, type Assist, type AssistStatus, type GuidanceEvent } from '../assist';
import { WATCH } from '../guide/words';
import type { Lang } from './i18n';

// Live guidance in the headset, through src/assist (the perception socket, server/perception/README.md).
// This file only joins it to the experience in xrMain.ts:
//
//   Enter AR tap      start(step, action, stream)   inside the tap, so the voice may begin; the camera stream comes
//                                                  from the bar's camera button (media.ts), else none is sent
//   another action    setStep(step, action)         the manifest's step number and 0-based action
//   hand summary      sendHands(summary)            from handChecks.ts; the module passes on about ten a second
//   session end       stop()
//
// What comes back is reduced to what his experience already shows: a ghost state (wrong / correct / unsure) with the
// server's sentence, a cue on Next when the step looks finished, and one quiet word on whether anything is watching.
// The sentence is spoken by his own voice (speech.ts), without the six-digit codes. Nothing here presses Next unless
// `autoAdvance` is on. Without a server or a camera only the quiet word changes.

export interface LiveView {
  /** the ghost state guidance asks for, or null: leave the ghost as it is */
  ghost: 'wrong' | 'correct' | 'unsure' | null;
  /** the server's sentence for that state, in the page language */
  sentence: string | null;
  /** the step looks finished: Next is cued */
  cue: boolean;
  /** watching, simulated, paused, no camera, no connection ... or null when guidance is off */
  word: string | null;
  /** what the camera sees in the hand: the job's part id or hardware code, and whether it is what this action needs */
  holding: { id: string; right: boolean | null } | null;
}

const OFF: LiveView = { ghost: null, sentence: null, cue: false, word: null, holding: null };

export interface LiveOptions {
  job: string;
  key: string | null;
  /** where the socket is when it is not this page's origin (development only) */
  apiBase?: string;
  lang: Lang;
  autoAdvance: boolean;
  onView: (view: LiveView) => void;
  onSay: (text: string) => void;
  onAdvance: () => void;
}

const WORDS = {
  en: { watching: 'Live check: watching', paused: 'Live check: paused', noLink: 'Live check: no connection', noCamera: 'Live check: no camera', unavailable: 'Live check: unavailable', simulated: 'Live check: simulated' },
  el: { watching: 'Έλεγχος: βλέπω', paused: 'Έλεγχος: σε παύση', noLink: 'Έλεγχος: χωρίς σύνδεση', noCamera: 'Έλεγχος: χωρίς κάμερα', unavailable: 'Έλεγχος: δεν είναι διαθέσιμος', simulated: `Έλεγχος: ${WATCH.el.simulated}` },
} as const;

export class Live {
  assist: Assist | null = null;
  view: LiveView = OFF;
  private at: { step: number; action: number | null } = { step: 1, action: null };
  private advancedAt = '';

  constructor(private o: LiveOptions) {}

  start(step: number, action: number | null, stream: MediaStream | null): void {
    this.assist?.destroy();
    this.at = { step, action };
    const assist = createAssist({
      jobId: this.o.job,
      key: this.o.key,
      apiBase: this.o.apiBase,
      lang: this.o.lang,
      viewpoint: 'headset',
      camera: stream ?? 'none',
      device: 'headset',
      speaker: null, // his own voice says the sentence (speech.ts)
      speakCodes: false,
      pauseWhenHidden: false,
      onStatus: (s) => this.assist === assist && this.onStatus(s),
      onEvent: (e) => this.assist === assist && this.onEvent(e),
    });
    this.assist = assist;
    void assist.start(step, action);
  }

  setStep(step: number, action: number | null): void {
    this.at = { step, action };
    this.assist?.setStep(step, action);
    if (this.assist) this.onStatus(this.assist.status);
  }

  sendHands(s: { seq: number; hand_at_target: boolean; still: boolean; raised: boolean; ts: number; present?: boolean }): void {
    this.assist?.sendHands(s);
  }

  stop(): void {
    this.assist?.stop();
    this.set(OFF);
  }

  private set(view: LiveView): void {
    const before = JSON.stringify(this.view);
    this.view = view;
    if (JSON.stringify(view) !== before) this.o.onView(view);
  }

  /** an event or a status about the step and action in view (not one the wearer has already left) */
  private current(step: number | null, action: number | null): boolean {
    return (step === null || step === this.at.step) && (action === null || this.at.action === null || action === this.at.action);
  }

  private onEvent(e: GuidanceEvent): void {
    const text = e.say?.[this.o.lang] || e.say?.en;
    if (e.speak === true && text && this.current(e.step, e.action)) this.o.onSay(withoutCodes(text));
    if (e.event === 'step_complete' && this.o.autoAdvance && this.current(e.step, null)) {
      const key = `${this.at.step}:${this.at.action}`;
      if (key !== this.advancedAt) {
        this.advancedAt = key; // once per action
        this.o.onAdvance();
      }
    }
  }

  private onStatus(s: AssistStatus): void {
    if (!s.running) return this.set(OFF);
    const server = s.server;
    const seeing = (s.vision === 'live' || s.vision === 'simulated') && !!server && this.current(server.step, server.action);
    const e = s.say?.from === 'event' ? s.say.event : null;
    const sentence = e && this.current(e.step, e.action) ? s.say!.text : null;
    let ghost: LiveView['ghost'] = null;
    if (seeing && s.verdict === 'wrong') ghost = 'wrong';
    else if (seeing && s.verdict === 'right') ghost = 'correct';
    else if (seeing && (server!.show_part || e?.event === 'unsure_show_part')) ghost = 'unsure';
    // the sentence goes with the state only when it is about the same thing (a confirmation after a correction
    // is not explained by the correction's sentence)
    const about: Record<string, LiveView['ghost']> = {
      wrong_hardware: 'wrong',
      wrong_part: 'wrong',
      right_hardware: 'correct',
      right_part: 'correct',
      unsure_show_part: 'unsure',
      show_part_result: e?.match === true ? 'correct' : e?.match === false ? 'wrong' : 'unsure',
    };
    const fits = !!e && about[e.event] === ghost;
    const held = seeing && (s.holding.kind === 'part' || s.holding.kind === 'hardware') && s.holding.id ? s.holding : null;
    const holding = held ? { id: held.id!, right: s.verdict === 'right' ? true : s.verdict === 'wrong' ? false : null } : null;
    this.set({ ghost, sentence: ghost && fits ? sentence : null, cue: seeing && s.state === 'STEP_COMPLETE', word: this.word(s), holding });
  }

  private word(s: AssistStatus): string | null {
    const w = WORDS[this.o.lang];
    if (s.link === 'refused') return w.unavailable;
    if (s.link === 'reconnecting') return w.noLink;
    if (s.link !== 'open') return null; // the first connection is being made
    if (s.vision === 'unavailable') return s.why?.startsWith('camera') ? w.noCamera : w.unavailable;
    if (s.vision === 'paused') return w.paused;
    if (s.vision === 'simulated') return w.simulated;
    if (s.vision === 'live') return w.watching;
    if (s.camera === 'none') return w.noCamera; // connected; no frames from here or from another device
    return null;
  }
}
