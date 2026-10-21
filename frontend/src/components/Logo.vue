<script setup lang="ts">
// Starburst logo: eight rays grow outward on mount; the wordmark reveals left to right.
const rays = Array.from({ length: 8 }, (_, i) => {
  const a = (Math.PI / 4) * i + Math.PI / 8
  return { x1: 12 + 2 * Math.cos(a), y1: 12 + 2 * Math.sin(a), x2: 12 + 10 * Math.cos(a), y2: 12 + 10 * Math.sin(a) }
})
</script>

<template>
  <span class="logo-wrap">
    <svg class="logo-icon" viewBox="2 2 20 20" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" aria-hidden="true">
      <line
        v-for="(r, i) in rays" :key="i" :x1="r.x1" :y1="r.y1" :x2="r.x2" :y2="r.y2"
        pathLength="1" stroke-dasharray="1" stroke-dashoffset="1"
        :style="{ animation: `ray-grow 0.6s ease ${0.1 + i * 0.04}s forwards` }"
      />
    </svg>
    <span class="logo-wordmark">Labserver</span>
  </span>
</template>
