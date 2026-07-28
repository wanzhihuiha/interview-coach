<template>
  <el-container class="app-layout" direction="vertical">
    <AppHeader />
    <el-main class="app-main">
      <slot />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import AppHeader from './AppHeader.vue'
</script>

<style scoped>
.app-layout {
  min-height: 100vh;
}

.app-main {
  background-color: #f5f7fa;
  padding: 0;
}
</style>
