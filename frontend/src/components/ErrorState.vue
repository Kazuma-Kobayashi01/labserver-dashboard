<script setup lang="ts">
import { mdiCloudAlertOutline } from '@mdi/js'
defineProps<{ message: string }>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="state">
    <v-icon :icon="mdiCloudAlertOutline" size="40" color="error" />
    <div class="title">Can't reach the server</div>
    <div class="msg">{{ message }}</div>
    <v-btn variant="tonal" color="primary" @click="$emit('retry')">Try again</v-btn>
  </div>
</template>

<style scoped>
.state { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 56px 16px; text-align: center; }
.title { font-size: 1.25rem; font-weight: 500; margin-top: 6px; }
.msg { color: rgb(var(--v-theme-surface-variant)); margin-bottom: 10px; }
</style>
