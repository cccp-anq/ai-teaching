<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <section class="placeholder">
    <p class="eyebrow">404</p>
    <h1>页面不存在</h1>
    <p>请检查访问路径，或<RouterLink to="/offerings">返回开课查询入口</RouterLink>。</p>
  </section>
</template>
