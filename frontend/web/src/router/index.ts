import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { managerRoutes } from '@/manager/router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true }
    },
    {
      path: '/register',
      name: 'Register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { public: true }
    },
    {
      path: '/',
      name: 'Home',
      component: () => import('@/views/HomeV2View.vue'),
      alias: '/home-v2',
      meta: { public: true }
    },
    {
      path: '/home-legacy',
      name: 'HomeLegacy',
      component: () => import('@/views/HomeView.vue'),
      meta: { public: true }
    },
    {
      path: '/resume',
      name: 'Resume',
      component: () => import('@/views/ResumeView.vue')
    },
    {
      path: '/position',
      name: 'Position',
      component: () => import('@/views/PositionView.vue')
    },
    {
      path: '/interview/config',
      name: 'InterviewConfig',
      component: () => import('@/views/InterviewConfigView.vue')
    },
    {
      path: '/interview/:id',
      name: 'Interview',
      component: () => import('@/views/InterviewView.vue')
    },
    {
      path: '/interview/:id/report',
      name: 'Report',
      component: () => import('@/views/ReportView.vue')
    },
    {
      path: '/interview/:id/growth',
      name: 'Growth',
      component: () => import('@/views/GrowthView.vue')
    },
    {
      path: '/history',
      name: 'History',
      component: () => import('@/views/HistoryView.vue')
    },
    {
      path: '/profile',
      name: 'Profile',
      component: () => import('@/views/ProfileView.vue')
    },
    managerRoutes
  ]
})

router.beforeEach((to) => {
  const userStore = useUserStore()
  if (!to.meta.public && !userStore.token) {
    return '/login'
  }
  if (to.meta.requiresAdmin && !userStore.isAdmin) {
    return '/'
  }
})

export default router
