<script setup lang="ts">
import { ref } from 'vue'
import { mdiOpenInNew } from '@mdi/js'
import { api } from '../services/api'
import { useAppTheme } from '../composables/useAppTheme'
import { STYLE_LABELS, PALETTES, type StyleId } from '../theme/palettes'
import { usePolling } from '../composables/usePolling'

const { style, mode, setStyle, toggleMode } = useAppTheme()
const snackbar = ref(false)
function pick(k: StyleId) { setStyle(k); snackbar.value = true }

const { data: sys } = usePolling(api.system, 30000)

const links = [
  { name: 'Incus', desc: 'Containers and virtual machines', path: '/incus' },
  { name: 'Mihomo', desc: 'Proxy control panel (MetaCubeXD)', path: '/mihomo' },
  { name: 'Git', desc: 'Forgejo, self-hosted Git', path: '/git' },
  { name: 'Hermes', desc: 'Hermes Agent web dashboard', path: '/hermes' },
  { name: 'Monitor', desc: 'Not set up yet', path: '/monitor', soon: true },
]
</script>

<template>
  <div class="page">
    <div class="page-title">Settings</div>
    <div class="page-sub">Appearance and shortcuts for this browser.</div>

    <div class="section-title">Appearance</div>
    <div class="group">
      <div class="row">
        <div class="grow">
          <div class="name">Mode</div>
          <div class="desc">Light or dark; also in the footer.</div>
        </div>
        <v-btn variant="tonal" color="primary" @click="toggleMode">{{ mode === 'dark' ? 'Switch to light' : 'Switch to dark' }}</v-btn>
      </div>
      <div class="row">
        <div class="grow">
          <div class="name">Style</div>
          <div class="desc">Retro warm paper, or two Material palettes.</div>
        </div>
        <div class="swatches">
          <button v-for="(label, k) in STYLE_LABELS" :key="k" type="button" class="sw" :class="{ on: style === k }" :style="{ background: PALETTES[k].light.accent }" :title="label" :aria-label="label" @click="pick(k)" />
        </div>
      </div>
    </div>

    <div class="section-title">Shortcuts</div>
    <div class="group">
      <component :is="l.soon ? 'div' : 'a'" v-for="l in links" :key="l.path" :href="l.soon ? undefined : l.path" class="row" :class="{ link: !l.soon, soon: l.soon }">
        <div class="grow">
          <div class="name">{{ l.name }} <span class="mono path">{{ l.path }}</span></div>
          <div class="desc">{{ l.desc }}</div>
        </div>
        <v-icon v-if="!l.soon" :icon="mdiOpenInNew" size="18" />
      </component>
    </div>

    <div class="section-title">About this server</div>
    <div class="group">
      <div class="row"><div class="grow name">Hostname</div><div class="val">{{ sys?.hostname ?? '—' }}</div></div>
      <div class="row"><div class="grow name">Operating system</div><div class="val">{{ sys?.os ?? '—' }}</div></div>
      <div class="row"><div class="grow name">Kernel</div><div class="val mono">{{ sys?.kernel ?? '—' }}</div></div>
      <div class="row"><div class="grow name">CPU threads</div><div class="val">{{ sys?.cpuCores ?? '—' }}</div></div>
    </div>

    <v-snackbar v-model="snackbar" :timeout="1800" location="bottom">Appearance updated</v-snackbar>
  </div>
</template>

<style scoped>
.top { margin-top: 14px; }
.swatches { display: flex; gap: 12px; }
.sw { width: 32px; height: 32px; border-radius: 50%; border: 0; cursor: pointer; outline-offset: 3px; transition: transform 0.15s; }
.sw:hover { transform: scale(1.1); }
.sw.on { outline: 2px solid rgb(var(--v-theme-on-surface)); }
.link { color: inherit; text-decoration: none; }
.link:hover { background: rgba(var(--v-theme-on-surface), 0.04); }
.soon { opacity: 0.6; }
.path { margin-left: 8px; color: rgb(var(--v-theme-surface-variant)); font-weight: 400; }
.val { color: rgb(var(--v-theme-surface-variant)); text-align: right; }
</style>
