import { createVuetify, type ThemeDefinition } from 'vuetify'
import { aliases, mdi } from 'vuetify/iconsets/mdi-svg'
import 'vuetify/styles'
import { PALETTES, vuetifyColors, type Mode, type StyleId } from './palettes'

export function readStyle(): StyleId {
  try {
    const v = localStorage.getItem('ls-style')
    if (v === 'retro' || v === 'material-blue' || v === 'material-teal') return v
  } catch { /* storage unavailable */ }
  return 'retro'
}

export function readMode(): Mode {
  try {
    const v = localStorage.getItem('ls-mode')
    if (v === 'light' || v === 'dark') return v
  } catch { /* storage unavailable */ }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

const themes: Record<string, ThemeDefinition> = {}
for (const style of Object.keys(PALETTES) as StyleId[]) {
  for (const mode of ['light', 'dark'] as Mode[]) {
    themes[`${style}-${mode}`] = { dark: mode === 'dark', colors: vuetifyColors(style, mode) }
  }
}

export default createVuetify({
  icons: { defaultSet: 'mdi', aliases, sets: { mdi } },
  theme: { defaultTheme: `${readStyle()}-${readMode()}`, themes },
  defaults: {
    VBtn: { rounded: 'lg', elevation: 0, style: 'text-transform:none;letter-spacing:0' },
    VChip: { rounded: 'lg' },
    VSheet: { elevation: 0 },
    VSnackbar: { rounded: 'lg' },
  },
})
