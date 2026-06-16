<script setup lang="ts">
defineProps<{ value: string | null | undefined; relative?: boolean }>()

function format(s: string | null | undefined, relative: boolean | undefined): string {
  if (!s) return '-'
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s
  if (!relative) {
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  }
  const ms = Date.now() - d.getTime()
  const sec = Math.floor(ms / 1000)
  if (sec < 60) return `${sec} 秒前`
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min} 分钟前`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h} 小时前`
  const day = Math.floor(h / 24)
  return `${day} 天前`
}
</script>

<template>
  <span class="font-mono text-xs text-text-secondary">{{ format(value, relative) }}</span>
</template>
