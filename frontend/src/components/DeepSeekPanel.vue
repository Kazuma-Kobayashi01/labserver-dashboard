<script setup lang="ts">
import { computed, ref } from 'vue'
import { mdiWalletOutline, mdiCashMinus, mdiTimerSandComplete, mdiTextBoxMultipleOutline } from '@mdi/js'
import { api } from '../services/api'
import { usePolling } from '../composables/usePolling'
import MetricTile from './MetricTile.vue'
import BarChart, { type Bar } from './BarChart.vue'
import StatusDot from './StatusDot.vue'

const { data, error } = usePolling(api.deepseek, 30000)
const view = ref<'spend' | 'tokens'>('spend')

const d = computed(() => data.value)
const hermes = computed(() => d.value?.hermes ?? null)

function money(n: number): string {
  if (n === 0) return '¥0'
  return '¥' + (n >= 1 ? n.toFixed(2) : n >= 0.01 ? n.toFixed(3) : n.toFixed(4))
}
function tokens(n: number): string {
  if (n >= 1e6) return (n / 1e6).toFixed(n >= 1e7 ? 1 : 2) + 'M'
  if (n >= 1e3) return (n / 1e3).toFixed(n >= 1e4 ? 1 : 2) + 'K'
  return String(n)
}
const hit = (t: { input: number; cacheRead: number }) => {
  const all = t.input + t.cacheRead
  return all > 0 ? Math.round((t.cacheRead / all) * 100) + '%' : '–'
}
function ago(ts?: number): string {
  if (!ts) return ''
  const s = Math.max(0, Math.round(Date.now() / 1000 - ts))
  return s < 90 ? 'just now' : s < 5400 ? `${Math.round(s / 60)} min ago` : `${Math.round(s / 3600)} h ago`
}

const balance = computed(() => d.value?.balance ?? null)
const sym = computed(() => (balance.value?.currency === 'USD' ? '$' : '¥'))
const spendCaption = computed(() => {
  const s = d.value?.spend
  if (!s) return ''
  return `7 days ${money(s.last7)} · 30 days ${money(s.last30)}`
})
const runway = computed(() => {
  const s = d.value?.spend
  if (!s) return { v: '', c: '' }
  if (s.daysLeft == null) {
    const need = Math.max(0, 2 - s.trackedDays)
    return { v: '–', c: need > 0 ? `needs ${need.toFixed(1)} more days of data` : 'no spend recorded' }
  }
  const days = s.daysLeft
  return { v: days >= 365 ? '1y+' : days >= 100 ? Math.round(days) + '' : days.toFixed(0), c: `at ${money(s.avgPerDay)} per day (recent average)` }
})
const tokenToday = computed(() => (hermes.value ? hermes.value.today.input + hermes.value.today.output : 0))

const bars = computed<Bar[]>(() => {
  if (!d.value) return []
  if (view.value === 'spend') return d.value.daily.map((x) => ({ label: x.date, value: x.spend, hint: x.topup > 0 ? `top-up ${money(x.topup)}` : undefined }))
  return (hermes.value?.daily ?? []).map((x) => ({ label: x.date, value: x.input, value2: x.output, hint: `${x.calls} calls · cache read ${tokens(x.cacheRead)}` }))
})
const fmtBar = computed(() => (view.value === 'spend' ? money : tokens))
const empty = computed(() => bars.value.every((b) => b.value === 0 && !(b.value2 ?? 0)))
</script>

<template>
  <div class="section-head">
    <div class="section-title">DeepSeek</div>
    <div class="head-right">
      <span v-if="balance" class="status"><StatusDot :state="balance.available ? 'up' : 'down'" />{{ balance.available ? 'Account active' : 'Unavailable' }}</span>
      <v-btn-toggle v-model="view" mandatory density="compact" divided variant="tonal" color="primary">
        <v-btn value="spend" size="small">Spend</v-btn>
        <v-btn value="tokens" size="small">Tokens</v-btn>
      </v-btn-toggle>
    </div>
  </div>

  <div v-if="error && !d" class="group note">DeepSeek data is unavailable ({{ error }}).</div>

  <template v-else>
    <div class="tiles">
      <MetricTile label="Balance" tone="green" :icon="mdiWalletOutline" style="--d: 0ms"
        :value="balance ? `${sym}${balance.total.toFixed(2)}` : ''" :loading="!d"
        :caption="balance ? `top-up ${sym}${balance.toppedUp.toFixed(2)} · granted ${sym}${balance.granted.toFixed(2)}` : (d?.balanceError ?? '')" />
      <MetricTile label="Spent today" tone="warm" :icon="mdiCashMinus" style="--d: 70ms"
        :value="d ? money(d.spend.today) : ''" :caption="spendCaption" :loading="!d" />
      <MetricTile label="Runway" tone="blue" :icon="mdiTimerSandComplete" style="--d: 140ms"
        :value="runway.v" :unit="runway.v && runway.v !== '–' ? 'days' : ''" :caption="runway.c" :loading="!d" />
      <MetricTile label="Tokens today" tone="slate" :icon="mdiTextBoxMultipleOutline" style="--d: 210ms"
        :value="hermes ? tokens(tokenToday) : '–'" :loading="!d"
        :caption="hermes ? `in ${tokens(hermes.today.input)} · out ${tokens(hermes.today.output)} · ${hermes.today.calls} calls` : 'no Hermes data yet'" />
    </div>

    <div class="group chart-wrap">
      <BarChart v-if="!empty" :bars="bars" :format="fmtBar" :names="view === 'tokens' ? ['Input', 'Output'] : ['Spent', '']" :height="190"
        :color="view === 'spend' ? 'ink-warm' : 'ink-blue'" color2="ink-green" />
      <div v-else class="empty-chart">{{ view === 'spend' ? 'No spend recorded in the last 14 days.' : 'No token usage recorded in the last 14 days.' }}</div>
      <div class="legend" v-if="view === 'tokens' && !empty"><span><i style="background: rgb(var(--v-theme-ink-blue))" />Input</span><span><i style="background: rgb(var(--v-theme-ink-green))" />Output</span></div>
    </div>

    <div v-if="hermes && hermes.byModel.length" class="group usage">
      <div class="urow uhead"><span>Hermes profile · model</span><span>Calls</span><span>Input</span><span>Output</span><span>Cache hit</span><span>Est. cost</span></div>
      <div v-for="m in hermes.byModel" :key="m.profile + m.model" class="urow">
        <span class="mname"><b>{{ m.profile }}</b> <em>{{ m.model }}</em></span>
        <span class="tabular">{{ m.calls }}</span>
        <span class="tabular">{{ tokens(m.input) }}</span>
        <span class="tabular">{{ tokens(m.output) }}</span>
        <span class="tabular">{{ hit(m) }}</span>
        <span class="tabular">${{ m.costUsd.toFixed(4) }}</span>
      </div>
    </div>

    <p v-if="d" class="foot">
      Spend is derived from balance changes{{ d.since ? ` since ${new Date(d.since).toLocaleDateString()}` : '' }}, so it counts everything that uses this API key.
      Tokens come from Hermes session records{{ hermes ? ` (updated ${ago(hermes.updatedAt)}${hermes.stale ? ', stale' : ''})` : '' }}; cost there is an estimate in USD.
    </p>
  </template>
</template>

<style scoped>
.section-head { display: flex; align-items: flex-end; justify-content: space-between; margin-top: 32px; }
.section-head .section-title { margin: 0 4px 12px; }
.head-right { display: flex; align-items: center; gap: 16px; margin-bottom: 10px; }
.status { display: inline-flex; align-items: center; gap: 8px; font-size: 0.8125rem; color: var(--ink-muted); }
.tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; }
.chart-wrap { padding: 28px 28px 20px; margin-top: 14px; }
.empty-chart { text-align: center; padding: 56px 12px; color: var(--ink-muted); }
.legend { display: flex; gap: 18px; margin-top: 14px; font-size: 0.8125rem; }
.legend span { display: inline-flex; align-items: center; gap: 8px; }
.legend i { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.usage { margin-top: 14px; }
.urow { display: grid; grid-template-columns: minmax(0, 2.2fr) repeat(5, minmax(0, 1fr)); gap: 12px; padding: 14px 22px; align-items: center; }
.urow + .urow { border-top: 1px solid var(--border); }
.uhead { font-size: 0.75rem; letter-spacing: 0.08em; text-transform: uppercase; color: var(--ink-muted); padding-top: 16px; padding-bottom: 12px; }
.uhead span:not(:first-child), .urow span:not(:first-child) { text-align: right; }
.mname em { font-style: normal; color: var(--ink-muted); margin-left: 6px; font-size: 0.875rem; }
.foot { margin: 14px 6px 0; font-size: 0.8125rem; color: var(--ink-muted); line-height: 1.55; }
.note { padding: 28px; text-align: center; color: var(--ink-muted); }
@media (max-width: 700px) {
  .urow { grid-template-columns: minmax(0, 1.6fr) repeat(3, minmax(0, 1fr)); }
  .urow > :nth-child(n+5) { display: none; }
  .chart-wrap { padding: 20px 16px 16px; }
}
</style>
