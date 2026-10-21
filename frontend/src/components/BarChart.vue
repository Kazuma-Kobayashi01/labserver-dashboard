<script setup lang="ts">
import { computed, ref } from 'vue'

export interface Bar { label: string; value: number; value2?: number; hint?: string }

const props = withDefaults(defineProps<{
  bars: Bar[]
  format: (n: number) => string
  names?: [string, string]
  height?: number
  color?: string
  color2?: string
}>(), { height: 180, color: 'ink-blue', color2: 'ink-warm' })

const hover = ref<number | null>(null)
const max = computed(() => Math.max(...props.bars.map((b) => b.value + (b.value2 ?? 0)), 0.0000001))
const pct = (v: number) => `${Math.max((v / max.value) * 100, v > 0 ? 2 : 0)}%`
const short = (d: string) => d.slice(5).replace('-', '/')
</script>

<template>
  <div class="bars" :style="{ height: height + 'px' }" @pointerleave="hover = null">
    <div v-for="(b, i) in bars" :key="b.label" class="col" @pointerenter="hover = i">
      <div class="stack" :class="{ on: hover === i }">
        <div v-if="b.value2" class="seg" :style="{ height: pct(b.value2), background: `rgb(var(--v-theme-${color2}))` }" />
        <div class="seg" :style="{ height: pct(b.value), background: `rgb(var(--v-theme-${color}))` }" />
      </div>
      <div class="lbl" :class="{ today: i === bars.length - 1 }">{{ short(b.label) }}</div>
      <div v-if="hover === i" class="tip" :class="{ left: i > bars.length / 2 }">
        <div class="when">{{ b.label }}</div>
        <div class="tl"><i :style="{ background: `rgb(var(--v-theme-${color}))` }" />{{ names ? names[0] : 'Value' }}<b>{{ format(b.value) }}</b></div>
        <div v-if="b.value2 !== undefined" class="tl"><i :style="{ background: `rgb(var(--v-theme-${color2}))` }" />{{ names ? names[1] : 'Value' }}<b>{{ format(b.value2) }}</b></div>
        <div v-if="b.hint" class="hint">{{ b.hint }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bars { display: flex; align-items: stretch; gap: 8px; position: relative; padding-bottom: 22px; }
.col { flex: 1; min-width: 0; position: relative; display: flex; flex-direction: column; justify-content: flex-end; }
.stack { flex: 1; display: flex; flex-direction: column; justify-content: flex-end; border-radius: 6px; background: rgba(var(--v-theme-on-surface), 0.04); overflow: hidden; transition: background-color 0.15s; }
.stack.on { background: rgba(var(--v-theme-on-surface), 0.09); }
.seg { width: 100%; transition: height 0.5s ease; }
.lbl { position: absolute; bottom: -22px; left: 0; right: 0; text-align: center; font-size: 0.6875rem; color: var(--ink-muted); }
.lbl.today { color: var(--ink); font-weight: 600; }
.tip { position: absolute; top: 4px; left: 50%; transform: translateX(-50%); z-index: 5; padding: 10px 14px; border-radius: 12px; font-size: 0.8125rem; min-width: 150px; pointer-events: none; background: rgb(var(--v-theme-on-surface)); color: rgb(var(--v-theme-surface)); }
.tip.left { transform: translateX(-88%); }
.when { opacity: 0.65; font-size: 0.75rem; margin-bottom: 4px; }
.tl { display: flex; align-items: center; gap: 8px; }
.tl b { margin-left: auto; padding-left: 14px; font-weight: 600; font-variant-numeric: tabular-nums; }
.tl i { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.hint { opacity: 0.65; font-size: 0.75rem; margin-top: 4px; }
</style>
