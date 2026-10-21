<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { smoothPath, type Pt } from '../services/chart'

export interface Series { name: string; color: string; values: number[] }

const props = withDefaults(defineProps<{
  series: Series[]
  max?: number
  format: (n: number) => string
  height?: number
  stepSeconds?: number
}>(), { height: 240, stepSeconds: 2 })

const uid = useId()
const W = 600
const H = computed(() => props.height)
const n = computed(() => Math.max(...props.series.map((s) => s.values.length), 0))
const ceiling = computed(() => props.max ?? Math.max(1, ...props.series.flatMap((s) => s.values)) * 1.2)

function pts(values: number[]): Pt[] {
  const len = values.length
  if (len < 2) return []
  const off = n.value - len // right-align shorter series
  const step = W / Math.max(n.value - 1, 1)
  return values.map((v, i) => [(i + off) * step, H.value - 4 - (Math.min(v, ceiling.value) / ceiling.value) * (H.value - 14)] as const)
}
const paths = computed(() =>
  props.series.map((s) => {
    const line = smoothPath(pts(s.values))
    return { ...s, line, area: line ? `${line} L${W},${H.value} L${((n.value - s.values.length) * W) / Math.max(n.value - 1, 1)},${H.value} Z` : '' }
  }),
)

const grid = computed(() => [1, 0.75, 0.5, 0.25, 0].map((f) => ({ f, label: props.format(ceiling.value * f) })))
const spanLabel = computed(() => {
  const s = (n.value - 1) * props.stepSeconds
  return s >= 90 ? `${Math.round(s / 60)} min ago` : `${Math.round(s)} s ago`
})

const hover = ref<number | null>(null)
const box = ref<HTMLElement>()
function move(e: PointerEvent) {
  const r = box.value?.getBoundingClientRect()
  if (!r || n.value < 2) return
  const f = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width))
  hover.value = Math.round(f * (n.value - 1))
}
const hoverPct = computed(() => (hover.value === null ? 0 : (hover.value / Math.max(n.value - 1, 1)) * 100))
const tipValues = computed(() =>
  hover.value === null
    ? []
    : props.series.map((s) => ({ name: s.name, color: s.color, v: s.values[hover.value! - (n.value - s.values.length)] })).filter((x) => x.v !== undefined),
)
const tipAgo = computed(() => {
  if (hover.value === null) return ''
  const s = (n.value - 1 - hover.value) * props.stepSeconds
  return s === 0 ? 'now' : s >= 90 ? `${Math.round(s / 60)} min ago` : `${s} s ago`
})
</script>

<template>
  <div class="chart">
    <div ref="box" class="plot" :style="{ height: height + 'px' }" @pointermove="move" @pointerleave="hover = null">
      <div v-for="g in grid" :key="g.f" class="gl" :style="{ bottom: `calc(${g.f * 100}% * (${(height - 14) / height}) + ${4 / height * 100}%)` }">
        <span>{{ g.label }}</span>
      </div>
      <svg :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none">
        <defs>
          <linearGradient v-for="(p, i) in paths" :id="`${uid}-${i}`" :key="i" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" :stop-color="`rgb(var(--v-theme-${p.color}))`" stop-opacity="0.26" />
            <stop offset="1" :stop-color="`rgb(var(--v-theme-${p.color}))`" stop-opacity="0" />
          </linearGradient>
        </defs>
        <g v-for="(p, i) in paths" :key="p.name">
          <path v-if="p.area" :d="p.area" :fill="`url(#${uid}-${i})`" />
          <path v-if="p.line" :d="p.line" fill="none" :stroke="`rgb(var(--v-theme-${p.color}))`" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" vector-effect="non-scaling-stroke" />
        </g>
      </svg>
      <template v-if="hover !== null">
        <div class="cursor" :style="{ left: hoverPct + '%' }" />
        <div class="tip" :style="{ left: hoverPct + '%', transform: `translateX(${hoverPct > 70 ? '-105%' : '12px'})` }">
          <div class="when">{{ tipAgo }}</div>
          <div v-for="t in tipValues" :key="t.name" class="tl"><i :style="{ background: `rgb(var(--v-theme-${t.color}))` }" />{{ t.name }}<b>{{ format(t.v) }}</b></div>
        </div>
      </template>
      <div v-if="n < 2" class="wait">Collecting data…</div>
    </div>
    <div class="axis"><span>{{ spanLabel }}</span><span>now</span></div>
    <div class="legend">
      <span v-for="s in series" :key="s.name"><i :style="{ background: `rgb(var(--v-theme-${s.color}))` }" />{{ s.name }}</span>
    </div>
  </div>
</template>

<style scoped>
.plot { position: relative; touch-action: pan-y; }
svg { position: absolute; inset: 0; width: 100%; height: 100%; }
.gl { position: absolute; left: 0; right: 0; border-top: 1px dashed rgba(var(--v-theme-on-surface), 0.09); }
.gl span { position: absolute; top: -9px; left: 0; font-size: 0.6875rem; padding-right: 6px; color: rgb(var(--v-theme-surface-variant)); background: rgb(var(--v-theme-surface)); font-variant-numeric: tabular-nums; }
.cursor { position: absolute; top: 0; bottom: 0; width: 1px; background: rgba(var(--v-theme-on-surface), 0.3); pointer-events: none; }
.tip { position: absolute; top: 8px; padding: 10px 14px; border-radius: 14px; font-size: 0.8125rem; min-width: 130px; pointer-events: none; background: rgb(var(--v-theme-on-surface)); color: rgb(var(--v-theme-surface)); }
.when { opacity: 0.65; font-size: 0.75rem; margin-bottom: 4px; }
.tl { display: flex; align-items: center; gap: 8px; }
.tl b { margin-left: auto; padding-left: 14px; font-weight: 600; font-variant-numeric: tabular-nums; }
.tl i, .legend i { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.axis { display: flex; justify-content: space-between; font-size: 0.75rem; color: rgb(var(--v-theme-surface-variant)); margin-top: 8px; }
.legend { display: flex; gap: 18px; margin-top: 12px; font-size: 0.8125rem; }
.legend span { display: inline-flex; align-items: center; gap: 8px; }
.wait { position: absolute; inset: 0; display: grid; place-items: center; color: rgb(var(--v-theme-surface-variant)); }
</style>
