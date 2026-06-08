<script setup lang="ts">
import ScriptPickerList from '../components/ScriptPickerList.vue'
</script>

<template>
  <ScriptPickerList
    title="生图工作台"
    subtitle="选择一个脚本查看图片或触发生图 / 图审"
    route-prefix="/images"
    hint="逐镜出图 + 自动图审 · 支持失败重跑"
  />
</template>
