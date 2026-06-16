<script setup lang="ts">
// user content 以 "[系统通知]" 开头时按系统提示样式渲染(RunWatcher 自动塞的,区分真实用户输入)
import type { AgentMessage } from '../../api/agent'
import ToolCallCard from './ToolCallCard.vue'
import MarkdownContent from './MarkdownContent.vue'
import { Sparkles, User, Loader2, Bell } from 'lucide-vue-next'

defineProps<{
  messages: AgentMessage[]
  busy?: boolean
}>()

function isSystemNotice(content: string | null | undefined): boolean {
  return !!content && content.startsWith('[系统通知]')
}
</script>

<template>
  <div class="flex flex-col gap-4 px-4 py-6">
    <template v-for="msg in messages" :key="msg.id">
      <template v-if="msg.role === 'user'">
        <div v-if="isSystemNotice(msg.content)" class="flex justify-center">
          <div class="text-[11px] text-text-muted bg-surface-tertiary/50 border border-border-subtle rounded-md px-3 py-1.5 flex items-center gap-1.5">
            <Bell :size="11" class="text-text-muted" />
            <span class="font-mono">{{ (msg.content || '').replace(/^\[系统通知\]\s*/, '') }}</span>
          </div>
        </div>
        <div v-else class="flex justify-end">
          <div class="max-w-[80%] flex gap-2 items-start">
            <div class="bg-accent-soft text-text-primary px-4 py-2 rounded-lg whitespace-pre-wrap break-words">
              {{ msg.content }}
            </div>
            <div class="w-7 h-7 rounded-full bg-accent flex items-center justify-center shrink-0">
              <User :size="14" class="text-white" />
            </div>
          </div>
        </div>
      </template>

      <div v-else-if="msg.role === 'assistant'" class="flex justify-start">
        <div class="max-w-full flex-1 flex gap-2 items-start min-w-0">
          <div class="w-7 h-7 rounded-full bg-surface-tertiary flex items-center justify-center shrink-0">
            <Sparkles :size="14" class="text-accent" />
          </div>
          <div class="bg-surface-secondary border border-border-subtle px-4 py-2 rounded-lg break-words text-sm min-w-0 flex-1">
            <MarkdownContent v-if="msg.content" :source="msg.content" />
            <span v-else class="text-text-muted italic">(已发起工具调用)</span>
          </div>
        </div>
      </div>

      <div v-else-if="msg.role === 'tool'" class="flex justify-start pl-9">
        <ToolCallCard :message="msg" class="max-w-[80%] w-full" />
      </div>
    </template>

    <div v-if="busy" class="flex justify-start">
      <div class="flex gap-2 items-center">
        <div class="w-7 h-7 rounded-full bg-surface-tertiary flex items-center justify-center shrink-0">
          <Sparkles :size="14" class="text-accent" />
        </div>
        <div class="bg-surface-secondary border border-border-subtle px-4 py-2 rounded-lg flex items-center gap-2 text-sm text-text-muted">
          <Loader2 :size="14" class="animate-spin" /> 思考中…
        </div>
      </div>
    </div>
  </div>
</template>
