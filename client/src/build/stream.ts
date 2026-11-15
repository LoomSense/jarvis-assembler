// The job's events as they happen: the SSE stream first, the long-poll route when the stream stays silent.
//
// A cloudflared quick tunnel (the demo host) buffers SSE entirely: the stream opens and then nothing arrives
// until it ends. Silence alone proves nothing (the agent can think for two minutes between two events), so
// whenever the stream has said nothing for a few seconds one quick poll asks the server whether it has events
// the stream did not bring. If it has, the stream is buffered: it is closed and the page long-polls from then on.
// `?transport=poll` starts with the long poll, `?transport=sse` never leaves the stream (both for tests).
import { apiFetch, apiUrl } from '../api'

export interface AgentEvent {
  seq: number
  ts: number
  jobId?: string
  stage: string
  type: string
  level?: string
  text?: string
  image_url?: string | null
  partId?: string | null
  stepN?: number | null
  progress?: number
  ms?: number
  tool?: string
}

export interface Follow {
  stop(): void
  /** 'sse' or 'poll': how the events are coming now */
  readonly transport: string
}

const CHECK_MS = 4000

export function follow(
  job: string,
  key: string | null,
  onEvents: (events: AgentEvent[]) => void,
  onTrouble: (lost: boolean) => void,
  forced: string | null = null,
): Follow {
  let last = 0
  let stopped = false
  let transport = forced === 'poll' ? 'poll' : 'sse'
  let source: EventSource | null = null
  let heardAt = performance.now()
  let checker = 0
  const base = `/api/jobs/${encodeURIComponent(job)}/events`
  const mark = () => (document.documentElement.dataset.transport = transport)

  const deliver = (events: AgentEvent[]) => {
    const fresh = events.filter((e) => e && typeof e.seq === 'number' && e.seq > last).sort((a, b) => a.seq - b.seq)
    if (!fresh.length) return
    last = fresh[fresh.length - 1].seq
    onEvents(fresh)
  }

  const poll = async (wait: number): Promise<AgentEvent[] | null> => {
    const res = await apiFetch(`${base}/poll?since=${last}&wait=${wait}`, key)
    if (!res.ok) throw new Error(`poll: HTTP ${res.status}`)
    return ((await res.json()) as { events?: AgentEvent[] }).events ?? []
  }

  const longPoll = async () => {
    transport = 'poll'
    mark()
    let pause = 1000
    while (!stopped) {
      try {
        const events = await poll(20)
        if (stopped) return
        onTrouble(false)
        pause = 1000
        if (events) deliver(events)
      } catch {
        if (stopped) return
        onTrouble(true)
        await new Promise((r) => setTimeout(r, pause))
        pause = Math.min(pause * 2, 8000)
      }
    }
  }

  const toPoll = () => {
    if (stopped || transport === 'poll') return
    source?.close()
    source = null
    clearInterval(checker)
    void longPoll()
  }

  if (transport === 'poll') {
    void longPoll()
  } else {
    mark()
    source = new EventSource(apiUrl(base, key, { since: 0 }))
    source.onmessage = (m) => {
      heardAt = performance.now()
      onTrouble(false)
      try {
        deliver([JSON.parse(m.data) as AgentEvent])
      } catch {
        /* a line that is not an event: nothing to show */
      }
    }
    source.onopen = () => onTrouble(false)
    source.onerror = () => {
      if (stopped) return
      // CONNECTING: the browser retries by itself and resumes with Last-Event-ID. CLOSED: it gave up (401, 404, a proxy).
      if (source?.readyState === EventSource.CLOSED) {
        if (forced === 'sse') onTrouble(true)
        else toPoll()
      }
    }
    if (forced !== 'sse') {
      checker = window.setInterval(async () => {
        if (stopped || transport !== 'sse' || performance.now() - heardAt < CHECK_MS) return
        try {
          const events = await poll(0)
          if (events?.length && events[events.length - 1].seq > last) {
            deliver(events)
            toPoll()
          }
        } catch {
          /* the next check asks again */
        }
      }, CHECK_MS)
    }
  }

  return {
    stop() {
      stopped = true
      source?.close()
      clearInterval(checker)
    },
    get transport() {
      return transport
    },
  }
}
