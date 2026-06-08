<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { Pause, Play } from 'lucide-vue-next'
import { formatDuration } from '../lib/format'

const props = defineProps<{ src: string }>()

const audio = ref<HTMLAudioElement | null>(null)
const playing = ref(false)
const currentTime = ref(0)
const duration = ref(0)

function mount(src: string) {
  cleanup()
  const el = new Audio(src)
  audio.value = el
  el.addEventListener('timeupdate', () => { currentTime.value = el.currentTime })
  el.addEventListener('durationchange', () => { duration.value = el.duration || 0 })
  el.addEventListener('ended', () => { playing.value = false })
  el.addEventListener('pause', () => { playing.value = false })
  el.addEventListener('play', () => { playing.value = true })
}

function cleanup() {
  if (audio.value) {
    audio.value.pause()
    audio.value.src = ''
    audio.value = null
    playing.value = false
    currentTime.value = 0
    duration.value = 0
  }
}

watch(() => props.src, (src) => { if (src) mount(src) }, { immediate: true })
onBeforeUnmount(cleanup)

function togglePlay() {
  if (!audio.value) return
  playing.value ? audio.value.pause() : audio.value.play()
}

function seek(e: Event) {
  if (audio.value) audio.value.currentTime = Number((e.target as HTMLInputElement).value)
}
</script>

<template>
  <div class="flex items-center gap-2.5 bg-surface-secondary rounded-md px-3 py-2 border border-border-subtle">
    <button
      class="text-text-secondary hover:text-accent transition-colors shrink-0"
      @click="togglePlay"
    >
      <Pause v-if="playing" :size="14" />
      <Play v-else :size="14" />
    </button>
    <input
      type="range" min="0" :max="duration || 100" step="0.01"
      :value="currentTime"
      class="flex-1 accent-accent h-0.5 cursor-pointer"
      @input="seek"
    />
    <span class="text-[10px] font-mono text-text-muted shrink-0 tabular-nums">
      {{ formatDuration(currentTime) }} / {{ formatDuration(duration) }}
    </span>
  </div>
</template>
