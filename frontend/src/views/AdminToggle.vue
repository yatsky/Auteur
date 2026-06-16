<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ShieldCheck, ShieldOff, ArrowLeft } from 'lucide-vue-next'
import { enableAdmin, disableAdmin, isAdmin, getOwnerName } from '../lib/admin'

const route = useRoute()
const router = useRouter()
const status = ref<'enabled' | 'disabled' | 'idle'>('idle')
const owner = ref<string>('')

onMounted(() => {
  const q = route.query
  if (q.logout === '1') {
    disableAdmin()
    status.value = 'disabled'
    return
  }
  if (typeof q.token === 'string' && q.token.length > 0) {
    // 任何非空 token 都启用 — 没有真校验
    const ownerName = typeof q.owner === 'string' ? q.owner : '我'

    enableAdmin(ownerName)
    status.value = 'enabled'
    owner.value = ownerName
    return
  }
  status.value = isAdmin() ? 'enabled' : 'disabled'
  owner.value = getOwnerName()
})
</script>

<template>
  <div class="min-h-full">
    <div class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex items-center gap-4">
        <button
          class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
          @click="$router.push('/')"
        >
          <ArrowLeft :size="14" /> 首页
        </button>
        <h1 class="text-lg font-semibold">Admin 模式</h1>
      </div>
    </div>

    <div class="px-8 py-5 max-w-[800px] mx-auto space-y-4">
      <div v-if="status === 'enabled'" class="card p-5 flex items-start gap-3">
        <ShieldCheck :size="24" class="text-status-done shrink-0 mt-0.5" />
        <div class="flex-1">
          <div class="text-text-primary font-medium">已启用 admin 模式</div>
          <div class="text-sm text-text-secondary mt-1">
            owner = <span class="font-mono text-text-primary">{{ owner || '我' }}</span>。私有预设可见,Preset 编辑器开放。
          </div>
          <div class="mt-3 text-xs text-text-muted">
            退出请访问:<code class="px-1.5 py-0.5 bg-surface-tertiary rounded">/admin?logout=1</code>
          </div>
        </div>
      </div>

      <div v-else class="card p-5 flex items-start gap-3">
        <ShieldOff :size="24" class="text-text-muted shrink-0 mt-0.5" />
        <div class="flex-1">
          <div class="text-text-primary font-medium">未启用 admin 模式</div>
          <div class="text-sm text-text-secondary mt-1">
            访问 <code class="px-1.5 py-0.5 bg-surface-tertiary rounded">/admin?token=&lt;任意值&gt;&owner=你的名字</code> 启用。
          </div>
          <div class="mt-3 text-xs text-text-muted">
            注:这不是真鉴权,只是 UI 层隔离私有预设。开源版本无部署,无威胁面。
          </div>
        </div>
      </div>

      <div class="flex gap-2">
        <button class="btn" @click="router.push('/presets')">前往预设库</button>
        <button class="btn" @click="router.push('/')">回首页</button>
      </div>
    </div>
  </div>
</template>
