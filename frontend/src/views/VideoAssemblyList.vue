<script setup lang="ts">
import ScriptPickerList from '../components/ScriptPickerList.vue'
</script>

<template>
  <ScriptPickerList
    title="视频组装"
    subtitle="选择一个脚本进入组装预览,触发 ffmpeg 渲染成片"
    route-prefix="/assembly"
    hint="ffmpeg 把图片 + 配音 + 字幕组装成 mp4 · 默认 1080×1920 9:16"
  />
</template>
