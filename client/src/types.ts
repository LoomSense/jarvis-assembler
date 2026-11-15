// Shapes of manifest.json as the pipeline writes it today (PLAN §4.3 plus the fields seen in real jobs).
// Everything optional here has been missing in at least one real manifest.

export type Lang = 'el' | 'en'

/** A text in both languages. The manifest uses it for action texts; names and titles are plain English strings today. */
export interface Loc {
  en?: string
  el?: string
}

export interface Cite {
  page?: number
  label?: string | null
  note?: string
}

export interface ManifestPart {
  id: string
  name: string | Loc
  short?: string | Loc
  count: number
  nodes: string[]
  thumb?: string
  cite?: Cite
  dimsEstimated?: boolean
}

export interface ManifestHardware {
  code: string
  name?: string | Loc
  short?: string | Loc
  kind?: string
  count: number
  nodes: string[]
  /** linear RGB, 0..1, the same numbers as the GLB material */
  color?: [number, number, number]
  thumb?: string
  cite?: Cite
}

export interface ManifestAction {
  type: 'turn' | 'place' | 'fasten' | (string & {})
  text?: Loc
  t0_s: number
  t1_s: number
  node?: string
  partId?: string
  code?: string
  target_m?: number[]
  from_dir?: number[]
  rotation_deg?: number[]
}

export interface ManifestStep {
  n: number
  clip: string | null
  title: string | Loc
  pose?: string
  visible: string[]
  moving?: string[]
  pickParts: string[]
  hardware: { code: string; qty: number }[]
  camera?: { node?: string; pos?: number[]; target?: number[]; ortho_height?: number }
  duration_s?: number
  cite?: Cite
  crop?: string | null
  refRender?: string
  flags?: unknown[]
  actions?: ManifestAction[]
}

export interface ManifestFlag {
  ref: string
  kind: string
  message: string
}

export interface Manifest {
  jobId?: string
  title: string
  glb: string
  glbPacked?: string
  units?: string
  rootNode?: string
  markerNode?: string
  parts: ManifestPart[]
  hardware: ManifestHardware[]
  steps: ManifestStep[]
  walkthrough?: {
    clip: string
    duration_s: number
    steps: { n: number | string; start_s: number; end_s: number; text?: Loc }[]
  }
  flags?: ManifestFlag[]
}
