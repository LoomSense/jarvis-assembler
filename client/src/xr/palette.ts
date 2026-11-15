// XR palette, copied from docs/design/tokens.css ("XR canvas-texture palette").
// Panels sit over passthrough: never fill a large area with white.

export const AG_XR = {
  panelBg: '#0c0c0c',
  panelBgAlpha: 0.82,
  panelBorder: 'rgba(255,255,255,0.16)',
  textStrong: '#ffffff',
  text: 'rgba(255,255,255,0.86)',
  textMeta: 'rgba(255,255,255,0.7)',
  accent: '#9ecfff',
  accentDeep: '#274a6b', // button fill (white text on top)
  chipBg: '#161618',
  info: '#9ecfff',
  success: '#6fdc9b',
  warn: '#ffa95c',
  error: '#ff8a7a',
  unsure: '#e8c14b',
  ghostNext: { color: '#9ecfff', emissive: '#9ecfff', emissiveIntensity: 0.6, opacity: 0.4, pulseHz: 0.42 },
  ghostCorrect: { color: '#4ade80', emissive: '#4ade80', emissiveIntensity: 0.5, opacity: 0.45, pulseHz: 0 },
  ghostWrong: { color: '#ff6b6b', emissive: '#ff6b6b', emissiveIntensity: 0.7, opacity: 0.5, pulseHz: 0.83 },
  ghostUnsure: { color: '#e8c14b', emissive: '#e8c14b', emissiveIntensity: 0.5, opacity: 0.4, pulseHz: 0.42 },
  ghostEdge: '#ffffff',
} as const;

export type Status = 'info' | 'success' | 'warn' | 'error' | 'unsure';

export const STATUS_COLOR: Record<Status, string> = {
  info: AG_XR.info,
  success: AG_XR.success,
  warn: AG_XR.warn,
  error: AG_XR.error,
  unsure: AG_XR.unsure,
};

export const FONT = '"Google Sans", Arial, system-ui, sans-serif';

/** BRAND.md: reduced motion freezes ghost pulses to a steady glow and stops every loop. */
export function reducedMotion(): boolean {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}
