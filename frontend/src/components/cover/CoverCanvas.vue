<script setup lang="ts">
// CoverCanvas —— 通用封面 canvas 预览组件。
// 接收 ratio + brand + design + heroImg + logoImg,内部按 native 像素绘制(1080×1440 等),
// CSS 缩放到 displayWidth 显示。用 watch 监听任何输入变化重画。
//
// 父组件通过 expose() 拿到内部 canvas 引用,用于 toBlob 导出。
import { computed, onMounted, ref, watch } from 'vue'
import type { BrandIdentity, CoverDesign, CoverRatio } from '../../types'
import { COVER_RATIO_DIMENSIONS } from '../../types'
import { getTemplate } from '../../lib/coverTemplates'

const props = defineProps<{
  ratio: CoverRatio
  brand: BrandIdentity
  design: CoverDesign
  heroImg: HTMLImageElement | null
  logoImg: HTMLImageElement | null
  displayWidth?: number      // CSS 宽度,默认 280
}>()

const canvasRef = ref<HTMLCanvasElement | null>(null)

const dim = computed(() => COVER_RATIO_DIMENSIONS[props.ratio])
const displayW = computed(() => props.displayWidth ?? 280)
const displayH = computed(() => Math.round(displayW.value * dim.value.h / dim.value.w))

function redraw() {
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  // native 像素由模板渲染按比例绘制,CSS 用 width/height style 缩放
  canvas.width = dim.value.w
  canvas.height = dim.value.h
  const tpl = getTemplate(props.design.templateId)
  ctx.clearRect(0, 0, dim.value.w, dim.value.h)
  tpl.render(ctx, dim.value.w, dim.value.h, props.brand, props.design,
    props.heroImg, props.logoImg)
}

onMounted(redraw)
watch(() => [
  props.ratio,
  props.brand.brandName,
  props.brand.authorName,
  props.brand.logoDataUrl,
  props.brand.primaryColor,
  props.brand.secondaryColor,
  props.brand.accentColor,
  props.brand.bgColor,
  props.brand.titleFont,
  props.design.templateId,
  props.design.titleText,
  props.design.heroImageUrl,
  props.heroImg,
  props.logoImg,
], () => redraw(), { deep: true })

defineExpose({
  getCanvas: () => canvasRef.value,
  getNativeDim: () => dim.value,
})
</script>

<template>
  <canvas
    ref="canvasRef"
    :style="{ width: displayW + 'px', height: displayH + 'px' }"
    class="rounded border border-border-subtle bg-surface-tertiary block"
  />
</template>
