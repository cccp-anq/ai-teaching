<script setup lang="ts">
defineProps<{ title: string; phase: string; description: string }>()
</script>

<template>
  <section class="placeholder">
    <p class="eyebrow">{{ phase }}</p>
    <h1>{{ title }}</h1>
    <p class="lead">{{ description }}</p>
    <div class="empty-state">
      <span class="empty-mark" aria-hidden="true">…</span>
      <h2>页面占位，功能尚未实现</h2>
      <p>当前仅展示页面结构；业务数据与操作将在对应故事验收时提供。</p>
    </div>
  </section>
</template>
