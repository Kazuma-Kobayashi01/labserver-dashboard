// Palettes ported from NeoCloud tokens.css: three styles, each with light and dark.
export type StyleId = 'retro' | 'material-blue' | 'material-teal'
export type Mode = 'light' | 'dark'

interface Pal { bg: string; surface: string; surface2: string; ink: string; soft: string; muted: string; accent: string; accentInk: string; border: string; ok: string; warn: string; bad: string }

export const PALETTES: Record<StyleId, Record<Mode, Pal>> = {
  retro: {
    light: { bg: '#f0eee6', surface: '#faf9f5', surface2: '#f5f2e9', ink: '#2b2620', soft: '#514a40', muted: '#6b6357', accent: '#d97757', accentInk: '#fff7f2', border: '#ddd8cc', ok: '#4a7c59', warn: '#b98a2f', bad: '#b4553f' },
    dark: { bg: '#1f1d1a', surface: '#262320', surface2: '#2c2825', ink: '#ede9e1', soft: '#d6cfc3', muted: '#a39b8e', accent: '#e08a6a', accentInk: '#1f1d1a', border: '#3a352e', ok: '#7fb08a', warn: '#d8ae5f', bad: '#d98070' },
  },
  'material-blue': {
    light: { bg: '#fafafa', surface: '#ffffff', surface2: '#f5f5f5', ink: '#1c1b1f', soft: '#49454f', muted: '#79747e', accent: '#1976d2', accentInk: '#ffffff', border: '#e0e0e0', ok: '#2e7d32', warn: '#ed6c02', bad: '#d32f2f' },
    dark: { bg: '#0d1117', surface: '#161b22', surface2: '#1c2430', ink: '#e6edf3', soft: '#b0bac5', muted: '#7d8590', accent: '#58a6ff', accentInk: '#0d1117', border: '#30363d', ok: '#3fb950', warn: '#d29922', bad: '#f85149' },
  },
  'material-teal': {
    light: { bg: '#fafafa', surface: '#ffffff', surface2: '#f0f7f7', ink: '#1c1b1f', soft: '#49454f', muted: '#79747e', accent: '#00897b', accentInk: '#ffffff', border: '#e0e0e0', ok: '#2e7d32', warn: '#ed6c02', bad: '#d32f2f' },
    dark: { bg: '#0d1412', surface: '#141e1b', surface2: '#1a2e28', ink: '#e0f2ef', soft: '#a8c9c2', muted: '#6f9a91', accent: '#4db6ac', accentInk: '#0d1412', border: '#2a3d37', ok: '#3fb950', warn: '#d29922', bad: '#f85149' },
  },
}

export const STYLE_LABELS: Record<StyleId, string> = { retro: 'Retro', 'material-blue': 'Material Blue', 'material-teal': 'Material Teal' }

function hex(n: number) { return Math.round(n).toString(16).padStart(2, '0') }
function mix(a: string, b: string, t: number): string {
  const p = (h: string, i: number) => parseInt(h.slice(1 + i * 2, 3 + i * 2), 16)
  return '#' + [0, 1, 2].map((i) => hex(p(a, i) * t + p(b, i) * (1 - t))).join('')
}

/** Vuetify color map for one style/mode, including the dashboard's tone/ink tokens. */
export function vuetifyColors(style: StyleId, mode: Mode): Record<string, string> {
  const p = PALETTES[style][mode]
  const t = mode === 'dark' ? 0.2 : 0.14
  const ink = { blue: p.accent, warm: p.warn, green: p.ok, slate: p.muted }
  const c: Record<string, string> = {
    background: p.bg, surface: p.surface, 'surface-bright': p.surface, 'surface-light': p.surface2, 'surface-variant': p.muted,
    primary: p.accent, 'on-primary': p.accentInk, secondary: p.soft, accent: p.accent,
    error: p.bad, warning: p.warn, success: p.ok, info: p.accent,
    'on-background': p.ink, 'on-surface': p.ink, border: p.border,
  }
  for (const [k, v] of Object.entries(ink)) {
    c[`ink-${k}`] = v
    c[`tone-${k}`] = mix(v, p.surface, t)
  }
  return c
}
