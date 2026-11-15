import type { Lang } from './i18n';
import { apiFetch } from '../api';

const DEMO_KEY = new URLSearchParams(location.search).get('key'); // the real server wants it on every /api route

// Reads each instruction aloud so the user can keep their eyes on the part.
// 1. Gemini TTS through POST /api/tts (the real server, or the dev server's in vite.config.ts; the key stays on
//    the server). Clips are fetched ahead with `prefetch` and kept, so steps speak at once.
// 2. If that endpoint is missing or fails, the browser's own voice (Web Speech API).
// `?voice=0` turns speech off. Gemini Live (PLAN §6) replaces this once it lands.
// Greek browser-voice availability in Quest Browser is UNVERIFIED.

const ENABLED = new URLSearchParams(location.search).get('voice') !== '0';
const clips = new Map<string, Promise<string | null>>(); // text → object URL of the WAV, or null
let gemini: boolean | null = null; // null = not tried yet
let last = '';
let player: HTMLAudioElement | null = null;
let ticket = 0;

function fetchClip(text: string): Promise<string | null> {
  let p = clips.get(text);
  if (!p) {
    p = apiFetch('/api/tts', DEMO_KEY, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text }) })
      .then(async (r) => {
        if (!r.ok) {
          if (r.status === 503 || r.status === 404 || r.status === 401) gemini = false; // no key, no endpoint or wrong demo key: stop trying
          clips.delete(text);
          return null;
        }
        gemini = true;
        return URL.createObjectURL(await r.blob());
      })
      .catch(() => {
        clips.delete(text);
        return null;
      });
    clips.set(text, p);
  }
  return p;
}

/** Fetch clips in the background, two at a time, so later steps speak without a wait. */
export async function prefetch(texts: string[]): Promise<void> {
  if (!ENABLED || gemini === false) return;
  const queue = [...new Set(texts.filter(Boolean))];
  const worker = async () => {
    while (queue.length && gemini !== false) await fetchClip(queue.shift()!);
  };
  await Promise.all([worker(), worker()]);
}

function browserSay(text: string, lang: Lang): void {
  if (!('speechSynthesis' in window)) return;
  const synth = window.speechSynthesis;
  synth.cancel();
  const u = new SpeechSynthesisUtterance(text);
  u.lang = lang === 'el' ? 'el-GR' : 'en-US';
  const want = lang === 'el' ? 'el' : 'en';
  const voices = synth.getVoices();
  const v = voices.find((x) => x.lang.toLowerCase().startsWith(want) && x.localService) ?? voices.find((x) => x.lang.toLowerCase().startsWith(want));
  if (v) u.voice = v;
  synth.speak(u);
}

function stop(): void {
  player?.pause();
  if ('speechSynthesis' in window) window.speechSynthesis.cancel();
}

/** Speak `text` unless it was the last thing said. Interrupts anything still speaking. */
export async function say(text: string, lang: Lang): Promise<void> {
  if (!ENABLED || !text || text === last) return;
  last = text;
  const mine = ++ticket;
  stop();
  if (gemini !== false) {
    const url = await fetchClip(text);
    if (mine !== ticket) return; // something newer was asked for meanwhile
    if (url) {
      player ??= new Audio();
      player.src = url;
      try {
        await player.play();
        return;
      } catch {
        /* autoplay blocked before the first tap: fall through to the browser voice */
      }
    }
  }
  if (mine === ticket) browserSay(text, lang);
}

/** Forget the last line, so the same instruction can be repeated on purpose. */
export function resetSpeech(): void {
  last = '';
}
