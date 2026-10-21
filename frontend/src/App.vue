<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import Logo from './components/Logo.vue'
import { useAppTheme } from './composables/useAppTheme'
import { STYLE_LABELS, type StyleId } from './theme/palettes'

const route = useRoute()
const { style, mode, setStyle, toggleMode } = useAppTheme()

const links = [
  { to: '/', label: 'Home' },
  { to: '/overview', label: 'Overview' },
  { to: '/instances', label: 'Instances' },
  { to: '/services', label: 'Services' },
  { to: '/settings', label: 'Settings' },
]
const external = [
  { href: '/incus', label: 'Incus', desc: 'Containers and VMs' },
  { href: '/mihomo', label: 'Mihomo', desc: 'Proxy control panel' },
  { href: 'http://nextcloud.labserver.internal', label: 'Nextcloud', desc: 'Files and sync' },
  { href: '/git', label: 'Git', desc: 'Forgejo repositories' },
  { href: '/hermes', label: 'Hermes', desc: 'AI agent dashboard' },
]

const menuOpen = ref(false)
const menuBox = ref<HTMLElement>()
function onDocClick(e: MouseEvent) {
  if (menuOpen.value && menuBox.value && !menuBox.value.contains(e.target as Node)) menuOpen.value = false
}
function onKey(e: KeyboardEvent) { if (e.key === 'Escape') menuOpen.value = false }
onMounted(() => { document.addEventListener('click', onDocClick); document.addEventListener('keydown', onKey) })
onUnmounted(() => { document.removeEventListener('click', onDocClick); document.removeEventListener('keydown', onKey) })
watch(() => route.path, () => { menuOpen.value = false })

watch(() => route.meta.title, (t) => { document.title = t && t !== 'Home' ? `${t} · Labserver` : 'Labserver' }, { immediate: true })
</script>

<template>
  <v-app>
    <div class="shell" :class="{ 'is-home': route.path === '/' }">
      <header class="topbar">
        <router-link to="/" class="brand"><Logo /></router-link>
        <nav class="topnav" aria-label="Main">
          <router-link v-for="l in links" :key="l.to" :to="l.to" class="chip-link" :class="{ active: route.path === l.to }">{{ l.label }}</router-link>
          <div ref="menuBox" class="appmenu">
            <button type="button" class="chip-link ext" aria-haspopup="menu" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">
              Apps
              <svg class="caret" :class="{ open: menuOpen }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
            </button>
            <transition name="pop">
              <div v-if="menuOpen" class="menu" role="menu">
                <a v-for="e in external" :key="e.href" :href="e.href" class="mi" role="menuitem">
                  <span class="mn">{{ e.label }}</span>
                  <span class="md">{{ e.desc }}</span>
                  <span class="ma">↗</span>
                </a>
              </div>
            </transition>
          </div>
        </nav>
      </header>

      <main class="shell-main">
        <router-view v-slot="{ Component }">
          <div :key="route.path" class="page-enter"><component :is="Component" /></div>
        </router-view>
      </main>

      <footer class="footer">
        <span class="footer-powered text-muted">Powered by Jackson Hao</span>
        <div class="footer-controls">
          <label class="lang-box">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
              <circle cx="13.5" cy="6.5" r="2" /><circle cx="17.5" cy="10.5" r="2" /><circle cx="8.5" cy="7.5" r="2" /><circle cx="6.5" cy="12.5" r="2" />
              <path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 0 1 1.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z" />
            </svg>
            <select aria-label="Theme style" :value="style" @change="setStyle(($event.target as HTMLSelectElement).value as StyleId)">
              <option v-for="(label, id) in STYLE_LABELS" :key="id" :value="id">{{ label }}</option>
            </select>
          </label>
          <button class="theme-toggle" type="button" title="Toggle theme" aria-label="Toggle theme" @click="toggleMode">
            <svg v-if="mode === 'dark'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" /></svg>
            <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
              <circle cx="12" cy="12" r="5" /><line x1="12" y1="1" x2="12" y2="3" /><line x1="12" y1="21" x2="12" y2="23" />
              <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" /><line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
              <line x1="1" y1="12" x2="3" y2="12" /><line x1="21" y1="12" x2="23" y2="12" />
              <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" /><line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
            </svg>
          </button>
        </div>
      </footer>
    </div>
  </v-app>
</template>

<style scoped>
.appmenu { position: relative; }
button.chip-link { border: 0; font: inherit; font-size: 15px; cursor: pointer; }
.caret { width: 14px; height: 14px; transition: transform 0.2s ease; }
.caret.open { transform: rotate(180deg); }
.menu { position: absolute; right: 0; top: calc(100% + 8px); min-width: 260px; padding: 6px; background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-lg); box-shadow: 0 12px 32px -12px rgba(0, 0, 0, 0.35); z-index: 20; }
.mi { display: grid; grid-template-columns: 1fr auto; grid-template-areas: 'n a' 'd a'; align-items: center; column-gap: 12px; padding: 10px 14px; border-radius: var(--r); color: var(--ink); text-decoration: none; transition: background-color 0.15s; }
.mi:hover, .mi:focus-visible { background: var(--surface-2); outline: none; }
.mn { grid-area: n; font-weight: 600; }
.md { grid-area: d; font-size: 0.8125rem; color: var(--ink-muted); }
.ma { grid-area: a; color: var(--accent); font-size: 1.1rem; }
.pop-enter-active, .pop-leave-active { transition: opacity 0.15s ease, transform 0.15s ease; transform-origin: top right; }
.pop-enter-from, .pop-leave-to { opacity: 0; transform: translateY(-6px) scale(0.98); }
@media (max-width: 767px) { .menu { right: auto; left: 0; } }
</style>
