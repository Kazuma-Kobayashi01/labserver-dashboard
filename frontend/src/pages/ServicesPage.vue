<script setup lang="ts">
import { computed } from 'vue'
import { mdiOpenInNew, mdiCube, mdiViewDashboardOutline, mdiRouterNetwork, mdiCloudOutline, mdiShieldOutline, mdiFolderNetworkOutline, mdiDocker, mdiConsoleNetworkOutline, mdiPulse, mdiGit, mdiRobotOutline } from '@mdi/js'
import { api } from '../services/api'
import { usePolling } from '../composables/usePolling'
import ErrorState from '../components/ErrorState.vue'

const { data, error, loading, refresh } = usePolling(api.services, 10000)
const list = computed(() => data.value ?? [])
const up = computed(() => list.value.filter((s) => s.status === 'up').length)
const allUp = computed(() => list.value.length > 0 && up.value === list.value.length)

const icons: Record<string, string> = {
  'cube-outline': mdiCube,
  'view-dashboard-outline': mdiViewDashboardOutline,
  'router-network': mdiRouterNetwork,
  'cloud-outline': mdiCloudOutline,
  'shield-outline': mdiShieldOutline,
  'folder-network-outline': mdiFolderNetworkOutline,
  docker: mdiDocker,
  'console-network-outline': mdiConsoleNetworkOutline,
  git: mdiGit,
  robot: mdiRobotOutline,
}
const tones = ['tone-blue', 'tone-green', 'tone-slate', 'tone-warm']
const icon = (n?: string) => (n && icons[n]) || mdiPulse
</script>

<template>
  <div class="page">
    <div class="page-title">Services</div>
    <div class="page-sub">Live health checks, refreshed every 10 seconds.</div>

    <ErrorState v-if="error && !data" :message="error" @retry="refresh" />

    <template v-else>
      <section class="summary" :class="{ bad: data && !allUp }">
        <div>
          <div class="big tabular"><template v-if="data">{{ up }}<span>/ {{ list.length }}</span></template><template v-else>–</template></div>
          <div class="lbl">{{ !data ? 'Checking…' : allUp ? 'All services healthy' : `${list.length - up} need attention` }}</div>
        </div>
        <div class="segs" aria-hidden="true">
          <i v-for="s in list" :key="s.id" :class="s.status" :title="s.name" />
        </div>
      </section>

      <div v-if="loading" class="group top">
        <v-skeleton-loader v-for="n in 6" :key="n" type="list-item-two-line" class="sk" />
      </div>

      <div v-else class="group top">
        <div v-for="(s, idx) in list" :key="s.id" class="row svc" :class="{ down: s.status === 'down' }">
          <span class="avatar" :class="s.status === 'up' ? tones[idx % 4] : 'tone-warm'"><v-icon :icon="icon(s.icon)" size="20" /></span>
          <div class="grow">
            <div class="name">{{ s.name }}</div>
            <div class="desc">{{ s.description }}</div>
          </div>
          <span class="meta tabular">{{ s.status === 'up' ? `${s.latencyMs} ms · ${s.kind}` : (s.detail || 'unreachable') }}</span>
          <span class="pill" :class="s.status"><i />{{ s.status === 'up' ? 'Healthy' : 'Down' }}</span>
          <a v-if="s.url" :href="s.url" class="open" :aria-label="`Open ${s.name}`"><v-icon :icon="mdiOpenInNew" size="18" /></a>
          <span v-else class="open ghost" />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.summary {
  display: flex; align-items: center; justify-content: space-between; gap: 24px; flex-wrap: wrap;
  margin: 32px 0 16px; padding: 24px 28px; border-radius: var(--r-lg);
  background: rgb(var(--v-theme-surface)); border: 1px solid var(--border); animation: rise 0.5s ease both;
}
.summary.bad { border-color: rgba(var(--v-theme-error), 0.5); }
.big { font-family: var(--font-display); font-size: 3rem; font-weight: 500; line-height: 1; }
.big span { font-size: 1.25rem; opacity: 0.5; margin-left: 8px; }
.lbl { margin-top: 8px; color: rgb(var(--v-theme-ink-green)); font-weight: 600; }
.summary.bad .lbl { color: rgb(var(--v-theme-error)); }
.segs { display: flex; gap: 6px; flex: 1 1 200px; max-width: 420px; }
.segs i { flex: 1; height: 12px; border-radius: 4px; background: rgb(var(--v-theme-ink-green)); opacity: 0.85; }
.segs i.down { background: rgb(var(--v-theme-error)); }
.top { margin-top: 0; }
.sk { background: transparent; }
.svc.down { background: rgba(var(--v-theme-error), 0.06); }
.meta { color: var(--ink-muted); font-size: 0.875rem; min-width: 120px; text-align: right; }
.pill { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-width: 96px; font-size: 0.75rem; font-weight: 600; padding: 5px 12px; border-radius: var(--r); }
.pill i { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.pill.up { background: rgba(var(--v-theme-success), 0.16); color: rgb(var(--v-theme-success)); }
.pill.down { background: rgba(var(--v-theme-error), 0.16); color: rgb(var(--v-theme-error)); }
.open { width: 34px; height: 34px; border-radius: var(--r); display: inline-grid; place-items: center; flex: none; color: var(--ink); border: 1px solid var(--border); transition: all 0.2s; }
.open:hover { background: var(--accent); border-color: var(--accent); color: var(--accent-ink); }
.open.ghost { border-color: transparent; }
@media (max-width: 700px) { .meta { display: none; } }
</style>
