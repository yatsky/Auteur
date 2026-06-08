<script setup lang="ts">
import { AlertCircle } from 'lucide-vue-next'
defineProps<{ msg: string | null }>()
</script>

<template>
  <div
    v-if="msg"
    class="card p-4 mb-4 flex items-start gap-3 border-status-failed/40"
  >
    <AlertCircle :size="18" class="text-status-failed mt-0.5" />
    <div class="text-sm">
      <div class="text-status-failed font-medium">无法连接后端</div>
      <div class="text-text-muted mt-1">{{ msg }}</div>
    </div>
  </div>
</template>
