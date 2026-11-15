import { defineConfig, loadEnv, type Plugin } from 'vite';
import basicSsl from '@vitejs/plugin-basic-ssl';
import { fileURLToPath } from 'node:url';
import { createReadStream, statSync } from 'node:fs';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { extname, join, resolve, sep } from 'node:path';

// WebXR needs a secure context. Three ways to get one on the Quest:
//   1. USB: `npm run quest` (adb reverse) then open http://localhost:5173/?mode=xr  (localhost counts as secure)
//   2. Tunnel: `npm run tunnel` (cloudflared) and open the trycloudflare.com URL
//   3. LAN: `npm run dev:https` (self-signed cert, accept the warning in Quest Browser)

const REPO_ROOT = fileURLToPath(new URL('..', import.meta.url));

/**
 * POST /api/tts {text} → audio/wav, spoken by Gemini TTS (PLAN §3 fallback voice:
 * `gemini-3.8-flash-lite-tts`). Runs inside the dev server's Node process, so
 * GEMINI_API_KEY (read from the repo-root .env) never reaches the browser.
 * The tunnel URL is public, so: text ≤ 400 chars, 30 calls a minute in total, and
 * every answer is cached by text. Without a key it answers 503 and the headset falls
 * back to the browser's own voice.
 */
function geminiTts(): Plugin {
  const cache = new Map<string, Buffer>();
  const calls: number[] = [];
  return {
    name: 'ag-gemini-tts',
    configureServer(server) {
      const env = loadEnv(server.config.mode, REPO_ROOT, '');
      const key = process.env.GEMINI_API_KEY || env.GEMINI_API_KEY;
      const model = env.TTS_MODEL || 'gemini-3.8-flash-lite-tts';
      const voice = env.TTS_VOICE || 'Schedar';
      server.config.logger.info(`  ag-gemini-tts: ${key ? `${model}, voice ${voice}` : 'no GEMINI_API_KEY, browser voice only'}`);

      server.middlewares.use('/api/tts', async (req, res) => {
        const fail = (code: number, msg: string) => {
          res.statusCode = code;
          res.setHeader('Content-Type', 'text/plain');
          res.end(msg);
        };
        if (req.method !== 'POST') return fail(405, 'POST only');
        if (!key) return fail(503, 'No GEMINI_API_KEY on the server');
        let raw = '';
        for await (const chunk of req) {
          raw += chunk;
          if (raw.length > 4000) return fail(413, 'Too long');
        }
        let text = '';
        try {
          text = String((JSON.parse(raw) as { text?: unknown }).text ?? '').trim();
        } catch {
          return fail(400, 'Bad JSON');
        }
        if (!text || text.length > 400) return fail(400, 'text must be 1–400 characters');

        const hit = cache.get(text);
        if (!hit) {
          const now = Date.now();
          while (calls.length && now - calls[0] > 60_000) calls.shift();
          if (calls.length >= 30) return fail(429, 'Voice rate limit, try again in a minute');
          calls.push(now);
        }
        try {
          const wav = hit ?? (await synthesize(text, key, model, voice));
          if (!hit) {
            if (cache.size > 300) cache.delete(cache.keys().next().value!);
            cache.set(text, wav);
          }
          res.setHeader('Content-Type', 'audio/wav');
          res.setHeader('Cache-Control', 'no-store');
          res.end(wav);
        } catch (err) {
          server.config.logger.warn(`ag-gemini-tts: ${(err as Error).message}`);
          fail(502, 'Speech synthesis failed');
        }
      });
    },
  };
}

/**
 * GET /files/<job>/<path>: the server's job-file route (server/routes/jobs.py, PLAN §4.5), mirrored so that
 * `?job=<id>` works without the backend, on the dev server and under `vite preview`. In production FastAPI
 * serves the same URLs behind the demo key.
 *
 * It serves <repo>/jobs/<id>/ (or $JOBS_DIR) and never anything outside it. The dev server can be public
 * (the tunnel) and has no key, so only the kinds of file a page loads are served: a job folder also holds
 * the uploaded guide, logs and scripts, and those answer 404 like a file that is not there.
 * A path that leaves the folder answers 403, a path that cannot be decoded 400.
 */
const JOBS_DIR = resolve(process.env.JOBS_DIR ?? join(REPO_ROOT, 'jobs'));

const FILE_TYPES: Record<string, string> = {
  '.json': 'application/json; charset=utf-8',
  '.glb': 'model/gltf-binary',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.mp4': 'video/mp4',
};

function jobFiles(): Plugin {
  const handle = (req: IncomingMessage, res: ServerResponse, next: () => void) => {
    const path = (req.url ?? '/').split('?')[0];
    if (!path.startsWith('/files/')) return next();
    const answer = (code: number, msg: string) => {
      res.statusCode = code;
      res.setHeader('Content-Type', 'text/plain');
      res.end(msg);
    };
    let rel: string;
    try {
      rel = decodeURIComponent(path.slice('/files/'.length));
    } catch {
      return answer(400, 'Bad path');
    }
    const file = resolve(JOBS_DIR, rel);
    if (!file.startsWith(JOBS_DIR + sep)) return answer(403, 'Forbidden');
    const type = FILE_TYPES[extname(file).toLowerCase()];
    if (!type) return answer(404, 'No such file');
    try {
      const stat = statSync(file);
      if (!stat.isFile()) throw new Error('not a file');
      res.setHeader('Content-Type', type);
      res.setHeader('Content-Length', String(stat.size));
      res.setHeader('Cache-Control', 'no-cache');
      createReadStream(file).pipe(res);
    } catch {
      answer(404, 'No such file');
    }
  };
  return {
    name: 'ag-job-files',
    configureServer(server) {
      server.middlewares.use(handle);
    },
    configurePreviewServer(server) {
      server.middlewares.use(handle);
    },
  };
}

async function synthesize(text: string, key: string, model: string, voice: string): Promise<Buffer> {
  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
    body: JSON.stringify({
      contents: [{ parts: [{ text }] }],
      generationConfig: {
        responseModalities: ['AUDIO'],
        speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: voice } } },
      },
    }),
  });
  if (!res.ok) throw new Error(`Gemini TTS ${res.status}`);
  const body = (await res.json()) as {
    candidates?: { content?: { parts?: { inlineData?: { mimeType?: string; data?: string } }[] } }[];
  };
  const part = body.candidates?.[0]?.content?.parts?.find((p) => p.inlineData?.data)?.inlineData;
  if (!part?.data) throw new Error('Gemini TTS returned no audio');
  const bytes = Buffer.from(part.data, 'base64');
  // Pass a WAV through; wrap raw 16-bit PCM (audio/L16;rate=24000) in a WAV header.
  if (bytes.subarray(0, 4).toString('ascii') === 'RIFF') return bytes;
  const rate = Number(/rate=(\d+)/.exec(part.mimeType ?? '')?.[1] ?? 24000);
  return pcmToWav(bytes, rate);
}

function pcmToWav(pcm: Buffer, rate: number): Buffer {
  const h = Buffer.alloc(44);
  h.write('RIFF', 0);
  h.writeUInt32LE(36 + pcm.length, 4);
  h.write('WAVE', 8);
  h.write('fmt ', 12);
  h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20); // PCM
  h.writeUInt16LE(1, 22); // mono
  h.writeUInt32LE(rate, 24);
  h.writeUInt32LE(rate * 2, 28);
  h.writeUInt16LE(2, 32);
  h.writeUInt16LE(16, 34);
  h.write('data', 36);
  h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}

export default defineConfig({
  // The built page refers to its own assets relatively. /files, /api and /demo-job stay absolute in the code.
  base: './',
  plugins: [geminiTts(), jobFiles(), ...(process.env.HTTPS ? [basicSsl()] : [])],
  server: {
    host: true,
    port: 5173,
    strictPort: true,
    allowedHosts: ['.trycloudflare.com', '.boxd.sh'],
  },
  preview: { host: true },
  build: { outDir: 'dist', target: 'es2022', chunkSizeWarningLimit: 900 },
});
