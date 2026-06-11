<script setup lang="ts">
import AppLayout from './layouts/AppLayout.vue'
import DemoBanner from './demo/DemoBanner.vue'
import { isDemoMode } from './demo'
</script>

<template>
  <DemoBanner v-if="isDemoMode()" />
  <AppLayout />
</template>
