<script setup lang="ts">
import type { PipelineRunStatus } from '../types'
import { RUN_STATUS_LABELS } from '../types'

defineProps<{ status: PipelineRunStatus }>()

const STYLE: Record<PipelineRunStatus, string> = {
  PENDING:   'bg-text-muted/15 text-text-secondary',
  RUNNING:   'bg-status-running/15 text-status-running',
  DONE:      'bg-status-done/15 text-status-done',
  FAILED:    'bg-status-failed/15 text-status-failed',
  PAUSED:    'bg-status-paused/15 text-status-paused',
  CANCELLED: 'bg-text-muted/15 text-text-muted',
}
</script>

<template>
  <span class="chip" :class="STYLE[status]">
    <span class="w-1.5 h-1.5 rounded-full bg-current" />
    {{ RUN_STATUS_LABELS[status] }}
  </span>
</template>
