<template>
  <el-container class="app-layout" :class="{ 'app-layout--home': isHome }" direction="vertical">
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
const isHome = computed(() => route.name === 'Home')
</script>

<style scoped>
.app-layout {
  min-height: 100vh;
}

.app-main {
  background-color: #f5f7fa;
  padding: 0;
}

.app-layout--home .app-main {
  background-color: #08090b;
}
</style>
