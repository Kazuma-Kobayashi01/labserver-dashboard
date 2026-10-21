<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../services/api'
import { usePolling } from '../composables/usePolling'

const system = usePolling(api.system, 4000)
const services = usePolling(api.services, 15000)

const sys = computed(() => system.data.value)
const down = computed(() => services.data.value?.filter((s) => s.status === 'down') ?? [])

const motto = computed(() => {
  if (system.error.value && !sys.value) return 'The server is not responding.'
  if (down.value.length === 1) return `${down.value[0].name} needs attention.`
  if (down.value.length > 1) return `${down.value.length} services need attention.`
  return 'Everything is running smoothly.'
})

const now = ref(new Date())
let timer: ReturnType<typeof setInterval>
onMounted(() => { timer = setInterval(() => (now.value = new Date()), 1000) })
onUnmounted(() => clearInterval(timer))

</script>

<template>
  <section class="home">
    <div class="copy">
      <div class="titleblock">
        <div class="brandline">Labserver</div>
        <h1 class="headline">Control Center</h1>
        <p class="motto" :class="{ bad: down.length > 0 || (system.error.value && !sys) }">{{ motto }}</p>
      </div>

      <div class="clock">
        <div class="clock-time mono">{{ now.toLocaleTimeString('en-US', { hour12: false }) }}</div>
        <div class="clock-date">{{ now.toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }) }}</div>
      </div>

    </div>
  </section>
</template>

<style scoped>
.home { flex: 1; display: flex; align-items: center; padding: 40px 80px; min-height: 0; }
.copy { display: flex; flex-direction: column; gap: 48px; max-width: 100%; }
.titleblock { display: flex; flex-direction: column; gap: 40px; }
.brandline, .headline { font-family: 'Playfair Display', Georgia, serif; font-size: clamp(48px, 6vw, 92px); line-height: 1.05; letter-spacing: -0.02em; margin: 0; white-space: nowrap; }
.brandline { font-weight: 700; }
.headline { font-weight: 400; }
.motto { font-style: italic; color: var(--ink-soft); font-size: clamp(16px, 1.6vw, 22px); margin: 0; }
.motto.bad { color: var(--bad); }
.clock { display: flex; flex-direction: column; gap: 8px; }
.clock-time { font-size: clamp(32px, 4vw, 56px); letter-spacing: 0.02em; line-height: 1; font-weight: 500; }
.clock-date { font-size: 16px; color: var(--ink-soft); }
@media (max-width: 900px) {
  .home { padding: 32px 20px; }
  .brandline, .headline { font-size: clamp(34px, 11vw, 56px); }
  .titleblock { gap: 22px; }
  .copy { gap: 32px; }
}
</style>
