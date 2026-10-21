<script setup lang="ts">
import { computed, useId } from 'vue'

const props = withDefaults(defineProps<{ values: number[]; max?: number; height?: number; color?: string }>(), {
  height: 56,
  color: 'ink-blue',
})

const gid = `g${useId()}`
const W = 200
const points = computed(() => {
  const v = props.values
  if (v.length < 2) return null
  const max = props.max ?? Math.max(...v, 1) * 1.2
  const step = W / (v.length - 1)
  return v.map((y, i) => [i * step, props.height - 3 - (Math.min(y, max) / max) * (props.height - 10)] as const)
})
const line = computed(() => {
  const p = points.value
  if (!p) return ''
  // Smooth the polyline with midpoint quadratic curves.
  let d = `M${p[0][0].toFixed(1)},${p[0][1].toFixed(1)}`
  for (let i = 1; i < p.length; i++) {
    const mx = (p[i - 1][0] + p[i][0]) / 2
    const my = (p[i - 1][1] + p[i][1]) / 2
    d += ` Q${p[i - 1][0].toFixed(1)},${p[i - 1][1].toFixed(1)} ${mx.toFixed(1)},${my.toFixed(1)}`
  }
  const last = p[p.length - 1]
  return `${d} T${last[0].toFixed(1)},${last[1].toFixed(1)}`
})
const area = computed(() => (line.value ? `${line.value} L${W},${props.height} L0,${props.height} Z` : ''))
const stroke = computed(() => `rgb(var(--v-theme-${props.color}))`)
</script>

<template>
  <svg class="spark" :viewBox="`0 0 ${W} ${height}`" preserveAspectRatio="none" :style="{ height: height + 'px' }" aria-hidden="true">
    <defs>
      <linearGradient :id="gid" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" :stop-color="stroke" stop-opacity="0.28" />
        <stop offset="1" :stop-color="stroke" stop-opacity="0" />
      </linearGradient>
    </defs>
    <path v-if="area" :d="area" :fill="`url(#${gid})`" />
    <path v-if="line" :d="line" fill="none" :stroke="stroke" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" vector-effect="non-scaling-stroke" />
  </svg>
</template>

<style scoped>
.spark { width: 100%; display: block; overflow: visible; }
</style>
