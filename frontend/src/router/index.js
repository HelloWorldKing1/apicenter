import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '@/layout/MainLayout.vue'

// 管理面路由(对应原型 7 个导航:概览/应用/分组/接口/适配器/监控)
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: MainLayout,
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', name: 'dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '概览' } },
        { path: 'apps', name: 'apps', component: () => import('@/views/Apps.vue'), meta: { title: '应用管理' } },
        { path: 'groups', name: 'groups', component: () => import('@/views/Groups.vue'), meta: { title: '分组管理' } },
        { path: 'interfaces', name: 'interfaces', component: () => import('@/views/Interfaces.vue'), meta: { title: '接口管理' } },
        { path: 'adapters', name: 'adapters', component: () => import('@/views/Adapters.vue'), meta: { title: '适配器' } },
        { path: 'monitor', name: 'monitor', component: () => import('@/views/Monitor.vue'), meta: { title: '接口监控' } }
      ]
    }
  ]
})

export default router
