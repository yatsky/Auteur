<script setup lang="ts">
import ScriptPickerList from '../components/ScriptPickerList.vue'
</script>

<template>
  <ScriptPickerList
    title="事实核查"
    subtitle="选择一个脚本查看核查结果或重新跑核查"
    route-prefix="/factcheck"
    hint="LLM 双轨核查 · 严重等级 BLOCKING / WARN / INFO"
  />
</template>
