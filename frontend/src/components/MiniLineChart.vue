<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  data: { x: string; y: number }[]
  width?: number
  height?: number
  stroke?: string
  fill?: string
  yLabel?: string
  /** sparkline 模式:隐藏 x/y 轴文字、收紧 padding,适合 KPI 卡片内的小图 */
  compact?: boolean
}>(), {
  width: 600,
  height: 180,
  stroke: '#A855F7',
  fill: 'rgba(168, 85, 247, 0.15)',
  compact: false,
})

const PADDING = computed(() =>
  props.compact
    ? { top: 2, right: 2, bottom: 2, left: 2 }
    : { top: 16, right: 8, bottom: 28, left: 36 },
)

const view = computed(() => {
  const pad = PADDING.value
  const w = props.width - pad.left - pad.right
  const h = props.height - pad.top - pad.bottom
  if (props.data.length === 0) return null

  const maxY = Math.max(...props.data.map((p) => p.y), 1)
  const minY = Math.min(...props.data.map((p) => p.y), 0)
  const span = Math.max(maxY - minY, 1)

  const points = props.data.map((p, i) => ({
    px: pad.left + (props.data.length === 1 ? w / 2 : (i / (props.data.length - 1)) * w),
    py: pad.top + h - ((p.y - minY) / span) * h,
    label: p.x,
    value: p.y,
  }))

  const linePath = points.map((p, i) => (i === 0 ? `M${p.px},${p.py}` : `L${p.px},${p.py}`)).join(' ')
  const areaPath = `${linePath} L${points[points.length - 1].px},${pad.top + h} L${points[0].px},${pad.top + h} Z`

  return { points, linePath, areaPath, maxY, minY, w, h, pad }
})
</script>

<template>
  <div>
    <svg
      v-if="view"
      :width="width" :height="height"
      class="w-full h-auto"
      :viewBox="`0 0 ${width} ${height}`"
      preserveAspectRatio="none"
    >
      <!-- y axis labels (full mode only) -->
      <template v-if="!compact">
        <text :x="4" :y="view.pad.top + 4" font-size="10" fill="#737373">{{ Math.round(view.maxY) }}</text>
        <text :x="4" :y="height - view.pad.bottom" font-size="10" fill="#737373">{{ Math.round(view.minY) }}</text>
      </template>
      <!-- area fill -->
      <path :d="view.areaPath" :fill="fill" />
      <!-- line -->
      <path :d="view.linePath" :stroke="stroke" :stroke-width="compact ? 1.5 : 2" fill="none" />
      <!-- points (full mode only — sparkline 不画端点更干净) -->
      <g v-if="!compact">
        <circle
          v-for="(p, i) in view.points" :key="i"
          :cx="p.px" :cy="p.py" r="3" :fill="stroke"
        />
      </g>
      <!-- x labels (full mode only) -->
      <g v-if="!compact">
        <text
          v-for="(p, i) in view.points" :key="`l-${i}`"
          :x="p.px" :y="height - 8"
          font-size="10" fill="#737373" text-anchor="middle"
        >
          {{ p.label }}
        </text>
      </g>
    </svg>
  </div>
</template>
