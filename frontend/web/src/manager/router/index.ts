import type { RouteRecordRaw } from 'vue-router'

/**
 * 后台管理模块路由配置。
 */
export const managerRoutes: RouteRecordRaw = {
  path: '/admin',
  name: 'Admin',
  component: () => import('@/manager/views/AdminLayout.vue'),
  meta: { requiresAdmin: true },
  redirect: '/admin/dashboard',
  children: [
    {
      path: 'dashboard',
      name: 'AdminDashboard',
      component: () => import('@/manager/views/AdminDashboard.vue'),
      meta: { title: '管理概览' }
    },
    {
      path: 'positions',
      name: 'AdminPositionAudit',
      component: () => import('@/manager/views/PositionAuditView.vue'),
      meta: { title: '岗位审核' }
    },
    {
      path: 'questions',
      name: 'AdminQuestionAudit',
      component: () => import('@/manager/views/QuestionAuditView.vue'),
      meta: { title: '题目审核' }
    },
    {
      path: 'users',
      name: 'AdminUserManage',
      component: () => import('@/manager/views/UserManageView.vue'),
      meta: { title: '用户管理' }
    },
    {
      path: 'audit-logs',
      name: 'AdminAuditLog',
      component: () => import('@/manager/views/AuditLogView.vue'),
      meta: { title: '审计日志' }
    }
  ]
}
