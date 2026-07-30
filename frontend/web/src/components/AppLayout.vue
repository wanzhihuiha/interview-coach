<template>
  <el-container class="app-layout" :class="{ 'app-layout--dark': isDarkContext }" direction="vertical">
    <AppHeader />
    <el-main class="app-main">
      <slot />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from './AppHeader.vue'

const route = useRoute()
const isDarkContext = computed(() => route.name === 'HomeLegacy')
</script>

<style scoped>
.app-layout {
  min-height: 100vh;
  background: var(--color-canvas);
}

.app-main {
  min-width: 0;
  flex: 1;
  background-color: var(--color-canvas);
  padding: 0;
}

.app-layout--dark .app-main {
  background-color: #08090b;
}
</style>
