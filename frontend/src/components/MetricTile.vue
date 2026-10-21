<script setup lang="ts">
import Sparkline from './Sparkline.vue'

withDefaults(defineProps<{
  label: string
  icon: string
  tone: 'blue' | 'warm' | 'green' | 'slate'
  value: string
  unit?: string
  caption?: string
  progress?: number
  history?: number[]
  max?: number
  loading?: boolean
}>(), {})
</script>

<template>
  <div class="tile" :style="{ '--ink': `var(--v-theme-ink-${tone})` }">
    <div class="top">
      <div class="label">{{ label }}</div>
      <div class="badge"><v-icon :icon="icon" size="20" /></div>
    </div>
    <v-skeleton-loader v-if="loading" type="heading" class="sk" />
    <div v-else class="value">{{ value }}<span v-if="unit" class="unit">{{ unit }}</span></div>
    <div class="caption">{{ caption ?? ' ' }}</div>
    <div class="viz">
      <div v-if="progress !== undefined" class="track">
        <div class="fill" :class="{ hot: progress > 85 }" :style="{ width: progress + '%' }" />
      </div>
      <Sparkline v-else-if="history" :values="history" :max="max" :color="`ink-${tone}`" />
    </div>
  </div>
</template>

<style scoped>
.tile {
  border-radius: var(--r-lg);
  background: rgb(var(--v-theme-surface));
  border: 1px solid var(--border);
  padding: 22px 24px 20px;
  min-height: 210px;
  display: flex;
  flex-direction: column;
  transition: transform 0.2s ease;
  animation: rise 0.5s cubic-bezier(0.2, 0.7, 0.2, 1) both;
  animation-delay: var(--d, 0ms);
}
.tile:hover { transform: translateY(-2px); }
.top { display: flex; justify-content: space-between; align-items: center; }
.label { font-size: 0.8125rem; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; color: rgb(var(--ink)); }
.badge { width: 38px; height: 38px; border-radius: 12px; display: grid; place-items: center; background: rgba(var(--ink), 0.14); color: rgb(var(--ink)); }
.value { font-family: var(--font-display); font-size: 3rem; line-height: 1; font-weight: 500; letter-spacing: -0.03em; margin-top: 18px; font-variant-numeric: tabular-nums; }
.unit { font-size: 1.25rem; margin-left: 5px; font-weight: 400; opacity: 0.65; }
.caption { font-size: 0.875rem; margin-top: 8px; opacity: 0.7; }
.viz { margin-top: auto; padding-top: 16px; }
.track { height: 8px; border-radius: 8px; background: rgba(var(--ink), 0.16); overflow: hidden; }
.fill { height: 100%; border-radius: 8px; background: rgb(var(--ink)); transition: width 0.6s ease; }
.fill.hot { background: rgb(var(--v-theme-error)); }
.sk { background: transparent; }
@keyframes rise { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: none; } }
</style>
