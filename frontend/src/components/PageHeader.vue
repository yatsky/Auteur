<script setup lang="ts">
defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <header class="mb-6">
    <h1 class="text-2xl font-semibold text-text-primary">{{ title }}</h1>
    <p v-if="subtitle" class="text-sm text-text-muted mt-1">{{ subtitle }}</p>
  </header>
</template>
