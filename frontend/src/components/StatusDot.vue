<script setup lang="ts">
defineProps<{ state: 'up' | 'down' | 'idle' }>()
</script>

<template>
  <span class="dot" :class="state" role="img" :aria-label="state" />
</template>

<style scoped>
.dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; flex: none; }
.up { background: rgb(var(--v-theme-success)); box-shadow: 0 0 0 4px rgba(var(--v-theme-success), 0.18); }
.down { background: rgb(var(--v-theme-error)); box-shadow: 0 0 0 4px rgba(var(--v-theme-error), 0.18); }
.idle { background: rgb(var(--v-theme-secondary)); opacity: 0.55; }
</style>
