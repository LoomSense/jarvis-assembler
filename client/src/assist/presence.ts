// What the person sees of the agent, in the workshop look (no panels, pills or labels): a small round live
// preview of the camera with a thin ring, and the current sentence as one line of text. The caller mounts
// the two elements where it wants. Every state has a shape as well as a colour (BRAND.md §3): the ring's
// stroke pattern and a small mark on its edge. Simulated answers hatch the picture and tag the sentence.
import './assist.css'
import type { AssistStatus } from './assist'
import type { Lang } from './protocol'
import { WORDS, type Tone } from './words'

export interface Presence {
  readonly preview: HTMLElement
  readonly line: HTMLElement
  setStream(stream: MediaStream | null): void
  update(status: AssistStatus, lang: Lang): void
  destroy(): void
}

// 24 x 24, one stroke family, drawn in the state's colour. The shapes are the brand's: circle with a
// check, octagon with a cross, triangle with a bar, diamond with a question mark.
const MARKS: Partial<Record<Tone, string>> = {
  right: '<circle cx="12" cy="12" r="9"/><path d="M8 12.4l2.7 2.7L16 9.6"/>',
  wrong: '<path d="M8.3 3h7.4L21 8.3v7.4L15.7 21H8.3L3 15.7V8.3z"/><path d="M9.2 9.2l5.6 5.6M14.8 9.2l-5.6 5.6"/>',
  check: '<path d="M12 3.6 21.4 20H2.6z"/><path d="M12 10v4.4M12 17.2v.1"/>',
  unsure: '<path d="M12 2.6 21.4 12 12 21.4 2.6 12z"/><path d="M10 10a2.1 2.1 0 1 1 3.1 1.9c-.7.4-1.1.9-1.1 1.7M12 16.3v.1"/>',
  paused: '<path d="M12 2.6 21.4 12 12 21.4 2.6 12z"/><path d="M10.3 9.6v4.8M13.7 9.6v4.8"/>',
  unavailable: '<circle cx="12" cy="12" r="9"/><path d="M5.7 5.7l12.6 12.6"/>',
}

export function createPresence(): Presence {
  const preview = document.createElement('div')
  preview.className = 'assist-eye'
  preview.setAttribute('role', 'img')
  preview.dataset.tone = 'off'
  preview.dataset.sim = 'false'
  preview.innerHTML =
    '<video></video><i class="assist-hatch"></i>' +
    '<svg class="assist-ring" viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="48.5" pathLength="100"/></svg>' +
    '<svg class="assist-mark" viewBox="0 0 24 24" aria-hidden="true"></svg>'
  const video = preview.querySelector('video') as HTMLVideoElement
  video.muted = true
  video.autoplay = true
  video.playsInline = true
  video.setAttribute('playsinline', '')
  video.setAttribute('aria-hidden', 'true')
  const mark = preview.querySelector('.assist-mark') as SVGElement

  const line = document.createElement('p')
  line.className = 'assist-line'
  line.setAttribute('role', 'status')
  const tag = document.createElement('span')
  tag.className = 'assist-tag'
  tag.hidden = true
  const words = document.createElement('span')
  words.className = 'assist-words'
  line.append(tag, words)

  const still = matchMedia('(prefers-reduced-motion: reduce)')
  let tone = ''
  let text = ''

  return {
    preview,
    line,

    setStream(stream) {
      if (video.srcObject === stream) return
      video.srcObject = stream
      if (stream) void video.play().catch(() => {})
    },

    update(status, lang) {
      const w = WORDS[lang]
      if (status.tone !== tone) {
        tone = status.tone
        preview.dataset.tone = tone
        mark.innerHTML = MARKS[status.tone] ?? ''
      }
      preview.dataset.sim = String(status.simulated)
      preview.setAttribute('aria-label', status.simulated ? `${w.tone[status.tone]} · ${w.simTag}` : w.tone[status.tone])

      const say = status.say
      const tagged = status.simulated && say?.from === 'event' // the notice already says "simulation" itself
      line.lang = lang
      line.dataset.from = say?.from ?? 'none'
      // when the sentence is spoken aloud, a screen reader must not say it a second time
      line.setAttribute('aria-live', status.speech === 'on' && say?.from === 'event' ? 'off' : 'polite')
      tag.textContent = tagged ? w.simTag : ''
      tag.hidden = !tagged
      const next = say?.text ?? ''
      if (next === text) return
      text = next
      words.textContent = next // text from the server is never parsed as HTML
      if (next && !still.matches && typeof line.animate === 'function') {
        line.animate([{ opacity: 0, transform: 'translateY(4px)' }, { opacity: 1, transform: 'none' }], { duration: 250, easing: 'cubic-bezier(0.16, 1, 0.3, 1)' })
      }
    },

    destroy() {
      video.srcObject = null
      preview.remove()
      line.remove()
    },
  }
}
