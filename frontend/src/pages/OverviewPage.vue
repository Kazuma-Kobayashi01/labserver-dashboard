<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  mdiServerOutline, mdiPulse, mdiLanConnect, mdiChevronRight, mdiCpu64Bit, mdiMemory, mdiHarddisk, mdiSwapVertical,
  mdiShieldOutline, mdiOpenInNew, mdiUbuntu, mdiDebian, mdiFedora, mdiArch, mdiLinux, mdiBattery, mdiBatteryCharging, mdiPowerPlug,
} from '@mdi/js'
import { api } from '../services/api'
import { bytes, duration, pct, rate } from '../services/format'
import { usePolling } from '../composables/usePolling'
import MetricTile from '../components/MetricTile.vue'
import StatusDot from '../components/StatusDot.vue'
import ErrorState from '../components/ErrorState.vue'
import AreaChart from '../components/AreaChart.vue'
import DeepSeekPanel from '../components/DeepSeekPanel.vue'

const system = usePolling(api.system, 4000)
const details = usePolling(api.details, 4000)
const history = usePolling(api.history, 4000)
const instances = usePolling(api.instances, 8000)
const services = usePolling(api.services, 15000)
const mihomo = usePolling(api.mihomo, 8000)

const sys = computed(() => system.data.value)
const det = computed(() => details.data.value)
const running = computed(() => instances.data.value?.filter((i) => i.running).length ?? 0)
const stopped = computed(() => (instances.data.value?.length ?? 0) - running.value)
const healthy = computed(() => services.data.value?.filter((s) => s.status === 'up').length ?? 0)
const down = computed(() => services.data.value?.filter((s) => s.status === 'down') ?? [])

const headline = computed(() => {
  if (system.error.value && !sys.value) return 'Labserver is not responding.'
  if (down.value.length === 1) return `${down.value[0].name} needs attention.`
  if (down.value.length > 1) return `${down.value.length} services need attention.`
  return 'Labserver is operating normally.'
})
const ok = computed(() => down.value.length === 0 && !system.error.value)

const distroLogo = computed(() => {
  const os = (sys.value?.os ?? '').toLowerCase()
  if (os.includes('ubuntu')) return mdiUbuntu
  if (os.includes('debian')) return mdiDebian
  if (os.includes('fedora')) return mdiFedora
  if (os.includes('arch')) return mdiArch
  return mdiLinux
})

const facts = computed(() => {
  const s = sys.value
  const d = det.value
  const list: { k: string; v: string; sub?: string }[] = []
  list.push({ k: 'Uptime', v: s ? duration(s.uptime) : '–' })
  list.push({ k: 'Kernel', v: s?.kernel ?? '–' })
  list.push({ k: 'Processor', v: d?.cpuModel.replace(/\((R|TM)\)/g, '').replace(/\s+/g, ' ').replace(/^\d+(th|st|nd|rd) Gen /, '') ?? '–', sub: d ? `${s?.cpuCores ?? '–'} threads · ${(d.cpuMHz / 1000).toFixed(2)} GHz avg` : '' })
  list.push({ k: 'Load average', v: s ? s.load.map((n) => n.toFixed(2)).join('  ') : '–', sub: '1 / 5 / 15 min' })
  list.push({ k: 'Memory', v: s ? `${bytes(s.memory.used)} / ${bytes(s.memory.total, 0)}` : '–', sub: d ? `Swap ${bytes(d.swap.used)} / ${bytes(d.swap.total, 0)}` : '' })
  list.push({ k: 'Temperature', v: s?.temperature != null ? `${s.temperature.toFixed(0)} °C` : '–', sub: 'CPU package' })
  return list
})

const view = ref<'system' | 'network'>('system')
const h = computed(() => history.data.value)
const series = computed(() => {
  if (!h.value) return []
  return view.value === 'system'
    ? [{ name: 'CPU', color: 'ink-blue', values: h.value.cpu }, { name: 'Memory', color: 'ink-warm', values: h.value.memory }]
    : [{ name: 'Download', color: 'ink-slate', values: h.value.rxRate }, { name: 'Upload', color: 'ink-green', values: h.value.txRate }]
})
const fmt = computed(() => (view.value === 'system' ? (n: number) => `${n.toFixed(0)}%` : (n: number) => rate(n)))

const procView = ref<'cpu' | 'mem'>('cpu')
const procs = computed(() => (procView.value === 'cpu' ? det.value?.topCpu : det.value?.topMem) ?? [])
const procMax = computed(() => Math.max(1, ...procs.value.map((p) => (procView.value === 'cpu' ? p.cpu : p.mem))))
const procVal = (p: { cpu: number; mem: number }) => (procView.value === 'cpu' ? `${p.cpu.toFixed(1)}%` : bytes(p.mem))
const procPct = (p: { cpu: number; mem: number }) => ((procView.value === 'cpu' ? p.cpu : p.mem) / procMax.value) * 100

const heat = (t: number) => (t >= 80 ? 'hot' : t >= 65 ? 'warm' : 'cool')
const coreLevel = (v: number) => (v >= 85 ? 'hot' : v >= 60 ? 'warm' : '')
const ifaceLabel = (n: string) => (n.startsWith('enx') || n.startsWith('en') || n.startsWith('eth') ? 'Ethernet' : n.startsWith('wl') ? 'Wi-Fi' : n.startsWith('docker') ? 'Docker' : n.startsWith('incus') ? 'Incus bridge' : n.startsWith('utun') || n.startsWith('tun') ? 'TUN' : '')
</script>

<template>
  <div class="page wide">
    <header class="head">
      <div class="page-title">Overview</div>
      <div class="headline" :class="{ bad: !ok }">
        <StatusDot :state="ok ? 'up' : 'down'" />
        <span>{{ headline }}</span>
      </div>
    </header>

    <ErrorState v-if="system.error.value && !sys" :message="system.error.value" @retry="system.refresh" />

    <template v-else>
      <!-- System card -->
      <section class="syscard">
        <div class="logo-tile"><v-icon :icon="distroLogo" size="92" /></div>
        <div class="sys-main">
          <div class="sys-host">{{ sys?.hostname ?? '…' }}</div>
          <div class="sys-os">{{ sys?.os ?? '' }}</div>
          <div class="sys-badges">
            <span v-if="instances.data.value" class="badge"><v-icon :icon="mdiServerOutline" size="15" />{{ running }} running · {{ stopped }} stopped</span>
            <span v-if="services.data.value" class="badge" :class="{ bad: down.length }"><v-icon :icon="mdiPulse" size="15" />{{ healthy }}/{{ services.data.value.length }} services</span>
            <span v-if="det?.battery" class="badge">
              <v-icon :icon="det.battery.acOnline ? mdiBatteryCharging : mdiBattery" size="15" />{{ det.battery.percent }}%
              <v-icon v-if="det.battery.acOnline" :icon="mdiPowerPlug" size="14" />
              <span class="bs">{{ det.battery.status }}</span>
            </span>
          </div>
        </div>
        <dl class="facts">
          <div v-for="f in facts" :key="f.k">
            <dt>{{ f.k }}</dt>
            <dd class="tabular">{{ f.v }}</dd>
            <div v-if="f.sub" class="sub">{{ f.sub }}</div>
          </div>
        </dl>
      </section>

      <div class="tiles">
        <MetricTile label="CPU" tone="blue" :icon="mdiCpu64Bit" style="--d: 0ms"
          :value="sys ? sys.cpuUsage.toFixed(0) : ''" unit="%"
          :caption="sys ? `${sys.cpuCores} threads · load ${sys.load[0]?.toFixed(2)}` : ''"
          :history="history.data.value?.cpu" :max="100" :loading="!sys" />
        <MetricTile label="Memory" tone="warm" :icon="mdiMemory" style="--d: 70ms"
          :value="sys ? bytes(sys.memory.used) : ''" :caption="sys ? `of ${bytes(sys.memory.total, 0)}` : ''"
          :progress="sys ? pct(sys.memory.used, sys.memory.total) : 0" :loading="!sys" />
        <MetricTile label="Storage" tone="green" :icon="mdiHarddisk" style="--d: 140ms"
          :value="sys ? bytes(sys.storage.used, 0) : ''"
          :caption="sys ? `of ${bytes(sys.storage.total, 0)} · ${pct(sys.storage.used, sys.storage.total).toFixed(0)}% used` : ''"
          :progress="sys ? pct(sys.storage.used, sys.storage.total) : 0" :loading="!sys" />
        <MetricTile label="Network" tone="slate" :icon="mdiSwapVertical" style="--d: 210ms"
          :value="sys ? rate(sys.network.rxRate) : ''"
          :caption="sys ? `↑ ${rate(sys.network.txRate)} · ${sys.network.interface}` : ''"
          :history="history.data.value?.rxRate" :loading="!sys" />
      </div>

      <!-- Activity -->
      <div class="section-head">
        <div class="section-title">Activity</div>
        <v-btn-toggle v-model="view" mandatory density="compact" divided variant="tonal" color="primary">
          <v-btn value="system" size="small">System</v-btn>
          <v-btn value="network" size="small">Network</v-btn>
        </v-btn-toggle>
      </div>
      <div class="group chart-wrap">
        <AreaChart :series="series" :format="fmt" :max="view === 'system' ? 100 : undefined" :height="230" :step-seconds="2" />
      </div>

      <DeepSeekPanel />

      <!-- Processor -->
      <div class="duo">
        <div>
          <div class="dhead"><div class="section-title">CPU cores</div></div>
          <div class="group stretch cores">
            <div v-for="(c, i) in det?.cores ?? []" :key="i" class="core" :title="`Thread ${i}: ${c.toFixed(0)}%`">
              <div class="col"><div class="fill" :class="coreLevel(c)" :style="{ height: Math.max(c, 2) + '%' }" /></div>
              <div class="ci tabular">{{ i }}</div>
            </div>
            <div v-if="!det" class="empty-note">Loading…</div>
          </div>
        </div>
        <div>
          <div class="dhead">
            <div class="section-title">Top processes</div>
            <v-btn-toggle v-model="procView" mandatory density="compact" divided variant="tonal" color="primary">
              <v-btn value="cpu" size="small">CPU</v-btn>
              <v-btn value="mem" size="small">Memory</v-btn>
            </v-btn-toggle>
          </div>
          <div class="group stretch">
            <div v-for="p in procs" :key="p.pid" class="row proc">
              <div class="grow">
                <div class="name">{{ p.name }} <span class="pid mono">{{ p.pid }}</span></div>
                <div class="pbar"><div :style="{ width: procPct(p) + '%' }" /></div>
              </div>
              <div class="pval tabular">{{ procVal(p) }}</div>
            </div>
            <div v-if="!procs.length" class="empty-note">Loading…</div>
          </div>
        </div>
      </div>

      <!-- Storage & network -->
      <div class="duo">
        <div>
          <div class="dhead"><div class="section-title">Disks</div></div>
          <div class="group stretch">
            <div v-for="d in det?.disks ?? []" :key="d.device" class="row disk">
              <span class="avatar tone-green"><v-icon :icon="mdiHarddisk" size="20" /></span>
              <div class="grow">
                <div class="name">{{ d.mount }} <span class="pid mono">{{ d.device }} · {{ d.fs }}</span></div>
                <div class="pbar green"><div :style="{ width: pct(d.used, d.total) + '%' }" /></div>
              </div>
              <div class="pval tabular">{{ bytes(d.used, 0) }} <span class="of">/ {{ bytes(d.total, 0) }}</span></div>
            </div>
            <div v-if="!det" class="empty-note">Loading…</div>
          </div>
        </div>
        <div>
          <div class="dhead"><div class="section-title">Network interfaces</div></div>
          <div class="group stretch">
            <div v-for="n in det?.interfaces ?? []" :key="n.name" class="row nif">
              <span class="avatar tone-slate"><v-icon :icon="mdiLanConnect" size="20" /></span>
              <div class="grow">
                <div class="name">{{ n.name }} <span v-if="ifaceLabel(n.name)" class="pid">{{ ifaceLabel(n.name) }}</span></div>
                <div class="desc mono">{{ n.ipv4.join(', ') || 'no IPv4' }}</div>
              </div>
              <div class="pval tabular rates">↓ {{ rate(n.rxRate) }}<br />↑ {{ rate(n.txRate) }}</div>
            </div>
            <div v-if="!det" class="empty-note">Loading…</div>
          </div>
        </div>
      </div>

      <!-- Sensors -->
      <template v-if="det?.sensors.length">
        <div class="section-title">Sensors</div>
        <div class="sensors">
          <div v-for="s in det.sensors" :key="s.name + s.label" class="sensor" :class="heat(s.value)">
            <div class="sv tabular">{{ s.value.toFixed(0) }}<small>°C</small></div>
            <div class="sl">{{ s.name }} · {{ s.label }}</div>
          </div>
        </div>
      </template>

      <!-- Infrastructure -->
      <div class="section-title">Infrastructure</div>
      <div class="group">
        <router-link to="/instances" class="row link">
          <span class="avatar tone-blue"><v-icon :icon="mdiServerOutline" size="20" /></span>
          <div class="grow">
            <div class="name">Incus instances</div>
            <div class="desc">
              <template v-if="instances.data.value">{{ running }} running · {{ stopped }} stopped</template>
              <template v-else-if="instances.error.value">Incus is unreachable</template>
              <template v-else>Loading…</template>
            </div>
          </div>
          <StatusDot v-if="instances.data.value" :state="instances.error.value ? 'down' : 'up'" />
          <v-icon :icon="mdiChevronRight" size="20" class="chev" />
        </router-link>
        <router-link to="/services" class="row link">
          <span class="avatar tone-green"><v-icon :icon="mdiPulse" size="20" /></span>
          <div class="grow">
            <div class="name">Services</div>
            <div class="desc">
              <template v-if="services.data.value">{{ healthy }} of {{ services.data.value.length }} healthy<template v-if="down.length"> · {{ down.map((d) => d.name).join(', ') }} down</template></template>
              <template v-else-if="services.error.value">Could not check services</template>
              <template v-else>Loading…</template>
            </div>
          </div>
          <StatusDot v-if="services.data.value" :state="down.length ? 'down' : 'up'" />
          <v-icon :icon="mdiChevronRight" size="20" class="chev" />
        </router-link>
        <a class="row link" href="/mihomo">
          <span class="avatar tone-warm"><v-icon :icon="mdiShieldOutline" size="20" /></span>
          <div class="grow">
            <div class="name">Mihomo <span v-if="mihomo.data.value" class="ver">{{ mihomo.data.value.version }}</span></div>
            <div class="desc tabular">
              <template v-if="mihomo.data.value">{{ mihomo.data.value.mode }} mode · {{ mihomo.data.value.tun ? 'TUN on' : 'TUN off' }} · {{ mihomo.data.value.connections }} connections · ↓ {{ bytes(mihomo.data.value.downloadTotal) }} ↑ {{ bytes(mihomo.data.value.uploadTotal) }}</template>
              <template v-else-if="mihomo.error.value">Controller is unreachable</template>
              <template v-else>Loading…</template>
            </div>
          </div>
          <StatusDot v-if="mihomo.data.value || mihomo.error.value" :state="mihomo.error.value ? 'down' : 'up'" />
          <v-icon :icon="mdiOpenInNew" size="18" class="chev" />
        </a>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page.wide { max-width: 1240px; }
.head { margin-bottom: 28px; animation: rise 0.5s ease both; }
.headline { display: flex; align-items: center; gap: 12px; font-size: 1.25rem; margin-top: 14px; }
.headline.bad { color: rgb(var(--v-theme-error)); }

.syscard { display: flex; align-items: center; gap: 36px; flex-wrap: wrap; padding: 32px 36px; margin-bottom: 18px; background: rgb(var(--v-theme-surface)); border: 1px solid var(--border); border-radius: var(--r-lg); animation: rise 0.5s ease both; }
.logo-tile { width: 148px; height: 148px; flex: none; border-radius: calc(var(--r-lg) * 1.4); display: grid; place-items: center; background: rgb(var(--v-theme-tone-blue)); color: rgb(var(--v-theme-ink-blue)); }
.sys-main { flex: 0 1 260px; min-width: 200px; }
.sys-host { font-family: var(--font-display); font-size: 2rem; font-weight: 600; line-height: 1.15; word-break: break-all; }
.sys-os { color: var(--ink-muted); font-size: 1.0625rem; margin-top: 4px; }
.sys-badges { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 16px; }
.badge { display: inline-flex; align-items: center; gap: 6px; padding: 5px 12px; border-radius: var(--r); font-size: 0.875rem; background: var(--surface-2); border: 1px solid var(--border); }
.badge.bad { color: rgb(var(--v-theme-error)); border-color: rgba(var(--v-theme-error), 0.5); }
.bs { opacity: 0.6; margin-left: 2px; }
.facts { flex: 1 1 420px; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 22px 28px; margin: 0; padding-left: 36px; border-left: 1px solid var(--border); }
.facts dt { font-size: 0.75rem; letter-spacing: 0.08em; text-transform: uppercase; color: var(--ink-muted); margin-bottom: 4px; }
.facts dd { margin: 0; font-size: 1.0625rem; font-weight: 500; overflow-wrap: anywhere; }
.facts .sub { font-size: 0.8125rem; color: var(--ink-muted); margin-top: 2px; }

.tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; }
.section-head { display: flex; align-items: flex-end; justify-content: space-between; margin-top: 32px; }
.section-head .section-title { margin: 0 4px 12px; }
.section-head .v-btn-toggle { margin-bottom: 10px; }
.section-head.tight { margin-top: 44px; }
.chart-wrap { padding: 28px 28px 22px; }
.duo { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; margin-top: 32px; }
.duo > div { display: flex; flex-direction: column; min-width: 0; }
.dhead { display: flex; align-items: flex-end; justify-content: space-between; height: 48px; }
.dhead .section-title { margin: 0 4px 12px; }
.dhead .v-btn-toggle { margin-bottom: 10px; }
.group.stretch { flex: 1; display: flex; flex-direction: column; }
.group.stretch > .row { flex: 1; }
.duo + .duo { margin-top: 14px; }

.cores { display: grid !important; grid-template-columns: repeat(10, 1fr); grid-template-rows: repeat(2, 1fr); gap: 12px 8px; padding: 22px; }
.core { display: flex; flex-direction: column; align-items: stretch; gap: 6px; min-height: 0; }
.col { width: 100%; flex: 1; min-height: 70px; border-radius: 6px; background: rgba(var(--v-theme-ink-blue), 0.12); display: flex; align-items: flex-end; overflow: hidden; }
.fill { width: 100%; background: rgb(var(--v-theme-ink-blue)); border-radius: 6px; transition: height 0.5s ease; }
.fill.warm { background: rgb(var(--v-theme-ink-warm)); }
.fill.hot { background: rgb(var(--v-theme-error)); }
.ci { font-size: 0.6875rem; color: var(--ink-muted); text-align: center; }

.proc, .disk, .nif { padding: 14px 22px; min-height: 0; }
.pid { font-size: 0.75rem; font-weight: 400; color: var(--ink-muted); margin-left: 6px; }
.pbar { height: 5px; border-radius: 5px; background: rgba(var(--v-theme-ink-blue), 0.13); margin-top: 8px; overflow: hidden; }
.pbar > div { height: 100%; border-radius: 5px; background: rgb(var(--v-theme-ink-blue)); transition: width 0.5s ease; }
.pbar.green { background: rgba(var(--v-theme-ink-green), 0.14); }
.pbar.green > div { background: rgb(var(--v-theme-ink-green)); }
.pval { font-weight: 500; text-align: right; white-space: nowrap; }
.pval .of { color: var(--ink-muted); font-weight: 400; font-size: 0.875rem; }
.rates { font-size: 0.875rem; line-height: 1.45; }
.empty-note { padding: 28px; text-align: center; color: var(--ink-muted); }

.sensors { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 12px; }
.sensor { padding: 16px 18px; border-radius: var(--r-lg); background: rgb(var(--v-theme-surface)); border: 1px solid var(--border); border-left: 4px solid rgb(var(--v-theme-ink-green)); }
.sensor.warm { border-left-color: rgb(var(--v-theme-ink-warm)); }
.sensor.hot { border-left-color: rgb(var(--v-theme-error)); }
.sv { font-family: var(--font-display); font-size: 1.75rem; font-weight: 500; line-height: 1; }
.sv small { font-size: 0.875rem; opacity: 0.6; margin-left: 2px; }
.sl { font-size: 0.8125rem; color: var(--ink-muted); margin-top: 6px; }

.link { color: inherit; text-decoration: none; transition: background-color 0.15s; }
.link:hover { background: rgba(var(--v-theme-on-surface), 0.04); }
.ver { font-weight: 400; font-size: 0.8125rem; color: var(--ink-muted); margin-left: 6px; }
.chev { color: var(--ink-muted); }

@media (max-width: 1000px) {
  .facts { padding-left: 0; border-left: 0; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .duo { grid-template-columns: 1fr; }
  .duo + .duo, .duo > div + div { margin-top: 0; }
}
@media (max-width: 700px) {
  .syscard { padding: 22px; gap: 22px; }
  .logo-tile { width: 108px; height: 108px; }
  .cores { grid-template-columns: repeat(5, 1fr); }
  .chart-wrap { padding: 20px 16px 16px; }
}
</style>
