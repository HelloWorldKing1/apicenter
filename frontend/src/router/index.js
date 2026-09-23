import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '@/layout/MainLayout.vue'
import http from '@/api/http'
import { authStore } from '@/utils/auth.mjs'
import { canManageAccounts, roleLabel } from '@/utils/roles.mjs'
import { ElMessage } from 'element-plus'

// 管理面路由(对应原型 7 个导航:概览/应用/分组/接口/适配器/监控)
const router = createRouter({
  history: createWebHistory(),
  routes: [
    // 登录 / 注册（无布局壳；2026-09-18 账号登录）
    { path: '/login', name: 'login', component: () => import('@/views/Login.vue'), meta: { title: '登录', public: true } },
    {
      path: '/',
      component: MainLayout,
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', name: 'dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '概览' } },
        { path: 'apps', name: 'apps', component: () => import('@/views/Apps.vue'), meta: { title: '应用管理' } },
        { path: 'groups', name: 'groups', component: () => import('@/views/Groups.vue'), meta: { title: '分组管理' } },
        { path: 'clients', name: 'clients', component: () => import('@/views/Clients.vue'), meta: { title: '调用方管理' } },
        { path: 'interfaces', name: 'interfaces', component: () => import('@/views/Interfaces.vue'), meta: { title: '接口管理' } },
        { path: 'inbound-auth', name: 'inbound-auth', component: () => import('@/views/InboundAuth.vue'), meta: { title: '入站鉴权' } },
        { path: 'adapters', name: 'adapters', component: () => import('@/views/Adapters.vue'), meta: { title: '适配器' } },
        { path: 'monitor', name: 'monitor', component: () => import('@/views/Monitor.vue'), meta: { title: '接口监控' } },
        { path: 'users', name: 'users', component: () => import('@/views/Users.vue'), meta: { title: '账号管理', roles: ['OWNER', 'ADMIN'] } }
      ]
    },
    // 兜底：未知路径回概览（未登录则由守卫转登录页）
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
  ]
})

/** 认证开关缓存（null = 未知）：`auth.enabled=false` 时后端不校验，前端也不强制跳登录（本地开发/应急） */
let authEnabledCache = authStore.getAuthEnabled()

async function authEnabled() {
  if (authEnabledCache !== null) {
    return authEnabledCache
  }
  try {
    const status = await http.get('/auth/status')
    authEnabledCache = !(status && status.enabled === false)
  } catch (e) {
    authEnabledCache = true // 拉不到状态按「需要登录」处理（安全性优先）
  }
  authStore.setAuthEnabled(authEnabledCache)
  return authEnabledCache
}

/**
 * 路由守卫（2026-09-18）：
 * - 已登录访问 /login → 回概览；未登录访问业务页 → 带 redirect 跳登录页（登录后原路返回）；
 * - `auth.enabled=false`（后端不校验）→ 放行，避免前后端口径不一致导致「明明不用登录却进不去」。
 */
router.beforeEach(async (to) => {
  if (to.path === '/login') {
    return authStore.getToken() ? '/dashboard' : true
  }
  if (authStore.getToken()) {
    // 角色控制（RBAC 第一层，2026-09-18）：meta.roles 命中的页面按当前角色放行；无权限回概览并提示
    // （服务端仍会拦 40303/40302，前端只是不让人进到「点了必失败」的页面）
    const need = to.meta?.roles
    if (Array.isArray(need) && need.length) {
      const role = authStore.getUser()?.role
      if (!canManageAccounts(role)) {
        ElMessage.warning(`当前角色（${roleLabel(role)}）无权访问「${to.meta?.title || to.path}」`)
        return '/dashboard'
      }
    }
    return true
  }
  if ((await authEnabled()) === false) {
    return true
  }
  return { path: '/login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
})

export default router
