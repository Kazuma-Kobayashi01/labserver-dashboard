import { ref } from 'vue'
import vuetify, { readMode, readStyle } from '../theme'
import type { Mode, StyleId } from '../theme/palettes'
import { PALETTES } from '../theme/palettes'

// Module-level state so every component sees the same theme.
const style = ref<StyleId>(readStyle())
const mode = ref<Mode>(readMode())

function apply() {
  const root = document.documentElement
  root.dataset.style = style.value
  root.dataset.theme = mode.value
  vuetify.theme.change(`${style.value}-${mode.value}`)
  try {
    localStorage.setItem('ls-style', style.value)
    localStorage.setItem('ls-mode', mode.value)
  } catch { /* storage unavailable */ }
}
apply()

function setStyle(s: StyleId) {
  style.value = s
  apply()
}

/** Flips light/dark with the same left-to-right sweep NeoCloud uses. */
function toggleMode() {
  const next: Mode = mode.value === 'dark' ? 'light' : 'dark'
  const overlay = document.createElement('div')
  overlay.className = 'theme-sweep'
  overlay.style.background = PALETTES[style.value][next].bg
  document.body.appendChild(overlay)
  mode.value = next
  apply()
  void overlay.offsetHeight
  overlay.classList.add('theme-sweep--active')
  overlay.addEventListener('animationend', () => overlay.remove(), { once: true })
  setTimeout(() => overlay.remove(), 900)
}

export function useAppTheme() {
  return { style, mode, setStyle, toggleMode }
}
