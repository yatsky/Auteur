<script setup lang="ts">
import ScriptPickerList from '../components/ScriptPickerList.vue'
</script>

<template>
  <ScriptPickerList
    title="分镜工作台"
    subtitle="按 SRT 时间戳对齐切镜 · 选择脚本进入分镜编辑"
    route-prefix="/storyboard"
    hint="分镜由 LLM 按脚本节奏 + SRT 时间戳生成,每镜默认 4-7 秒"
  />
</template>
