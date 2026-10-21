<script setup lang="ts">
import { computed, ref } from 'vue'
import { mdiCubeOutline, mdiMonitor, mdiContentCopy, mdiMemory, mdiCpu64Bit, mdiClockOutline } from '@mdi/js'
import { api, type Instance } from '../services/api'
import { bytes, pct, since } from '../services/format'
import { copyText } from '../services/clipboard'
import { usePolling } from '../composables/usePolling'
import ErrorState from '../components/ErrorState.vue'

const { data, error, loading, refresh } = usePolling(api.instances, 6000)
const list = computed(() => data.value ?? [])
const running = computed(() => list.value.filter((i) => i.running).length)

const toast = ref(false)
const toastText = ref('')
async function copy(ip: string) {
  const ok = await copyText(ip)
  toastText.value = ok ? `Copied ${ip}` : 'Copy failed'
  toast.value = true
}

const osLabel = (i: Instance) => (i.os ? (i.release ? `${i.os} ${i.release}` : i.os) : '')
const isVm = (i: Instance) => i.type === 'virtual-machine'
</script>

<template>
  <div class="page wide">
    <div class="page-title">Instances</div>
    <div class="page-sub">
      <template v-if="data">{{ running }} running of {{ list.length }} · containers and virtual machines managed by Incus.</template>
      <template v-else>Containers and virtual machines managed by Incus.</template>
    </div>

    <ErrorState v-if="error && !data" :message="error" @retry="refresh" />

    <div v-else-if="loading" class="group list">
      <v-skeleton-loader v-for="n in 3" :key="n" type="list-item-two-line" class="skcard" />
    </div>

    <div v-else-if="list.length === 0" class="empty">
      <v-icon :icon="mdiCubeOutline" size="44" />
      <div class="t">No instances yet</div>
      <div class="d">Create a container or VM in Incus and it will show up here.</div>
    </div>

    <div v-else class="group list">
      <div v-for="i in list" :key="i.name" class="row inst" :class="{ off: !i.running }">
        <span class="avatar" :class="!i.running ? 'tone-warm' : isVm(i) ? 'tone-slate' : 'tone-blue'">
          <v-icon :icon="isVm(i) ? mdiMonitor : mdiCubeOutline" size="20" />
        </span>
        <div class="grow">
          <div class="name">{{ i.name }}</div>
          <div class="desc">{{ isVm(i) ? 'Virtual machine' : 'Container' }}<template v-if="osLabel(i)"> · {{ osLabel(i) }}</template></div>
        </div>
        <div class="cell ipcell">
          <button v-for="ip in i.ipv4" :key="ip" class="ip mono" type="button" :title="`Copy ${ip}`" @click="copy(ip)">
            {{ ip }}<v-icon :icon="mdiContentCopy" size="12" />
          </button>
          <span v-if="!i.ipv4.length" class="dim">{{ i.running ? 'No address' : '—' }}</span>
        </div>
        <div class="cell mem tabular">
          <span class="k"><v-icon :icon="mdiMemory" size="12" />Memory</span>
          {{ i.running ? bytes(i.memoryUsed) : '—' }}<span v-if="i.memoryLimit" class="of"> / {{ bytes(i.memoryLimit, 0) }}</span>
          <div v-if="i.memoryLimit && i.running" class="bar"><div :style="{ width: pct(i.memoryUsed, i.memoryLimit) + '%' }" /></div>
        </div>
        <div class="cell tabular">
          <span class="k"><v-icon :icon="mdiCpu64Bit" size="12" />CPU</span>
          {{ i.cpuLimit ? `${i.cpuLimit.split(',').length > 1 ? i.cpuLimit.split(',').length : i.cpuLimit} cores` : 'All cores' }}
        </div>
        <div class="cell tabular">
          <span class="k"><v-icon :icon="mdiClockOutline" size="12" />Running</span>
          {{ i.running ? since(i.since) : '—' }}
        </div>
        <span class="state" :class="i.running ? 'on' : 'stop'"><i />{{ i.status }}</span>
      </div>
    </div>

    <v-snackbar v-model="toast" :timeout="1600">{{ toastText }}</v-snackbar>
  </div>
</template>

<style scoped>
.page.wide { max-width: 1280px; }
.list { margin-top: 32px; }
.skcard { background: transparent; }
.inst { flex-wrap: wrap; row-gap: 14px; gap: 20px; padding: 30px 32px; min-height: 120px; }
.inst .avatar { width: 54px; height: 54px; }
.inst .avatar :deep(.v-icon) { font-size: 26px; width: 26px; height: 26px; }
.inst .grow { min-width: 190px; }
.inst .name { font-size: 1.375rem; }
.inst .desc { font-size: 1.0625rem; margin-top: 2px; }
.inst.off .name, .inst.off .desc { opacity: 0.7; }
.cell { width: 120px; font-size: 1.1875rem; }
.ipcell { width: 150px; display: flex; flex-direction: column; align-items: flex-start; gap: 4px; }
.mem { width: 170px; }
.k { display: flex; align-items: center; gap: 4px; font-size: 0.8125rem; letter-spacing: 0.08em; text-transform: uppercase; color: var(--ink-muted); margin-bottom: 6px; }
.of { opacity: 0.55; font-size: 1rem; }
.dim { color: var(--ink-muted); font-size: 1.0625rem; }
.ip { display: inline-flex; align-items: center; gap: 8px; padding: 5px 12px; border: 0; border-radius: var(--r); cursor: pointer; font-size: 1rem; color: rgb(var(--v-theme-ink-blue)); background: rgba(var(--v-theme-ink-blue), 0.12); transition: background-color 0.15s; }
.ip:hover { background: rgba(var(--v-theme-ink-blue), 0.24); }
.bar { height: 6px; border-radius: 4px; background: rgba(var(--v-theme-ink-blue), 0.15); margin-top: 6px; overflow: hidden; }
.bar > div { height: 100%; background: rgb(var(--v-theme-ink-blue)); transition: width 0.6s ease; }
.state { display: inline-flex; align-items: center; justify-content: center; gap: 8px; min-width: 108px; padding: 8px 14px; border-radius: var(--r); font-size: 0.9375rem; font-weight: 600; }
.state i { width: 9px; height: 9px; border-radius: 50%; background: currentColor; }
.state.on { background: rgba(var(--v-theme-success), 0.16); color: rgb(var(--v-theme-success)); }
.state.stop { background: rgba(var(--v-theme-secondary), 0.14); color: rgb(var(--v-theme-secondary)); }
.empty { text-align: center; padding: 72px 16px; color: var(--ink-muted); }
.empty .t { font-size: 1.25rem; font-weight: 600; color: var(--ink); margin-top: 12px; }
@media (max-width: 900px) {
  .inst .grow { flex-basis: 60%; }
  .cell, .ipcell, .mem { width: auto; flex: 1 1 30%; }
}
</style>
