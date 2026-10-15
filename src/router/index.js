import { createRouter, createWebHistory } from 'vue-router'
import Layout from '@/components/layout/Layout.vue'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', guest: true },
  },
  {
    path: '/',
    component: Layout,
    redirect: '/dataset',
    children: [
      {
        path: 'dataset',
        name: 'Dataset',
        component: () => import('@/views/Dataset.vue'),
        meta: { title: '数据集管理', icon: 'FolderOpened' },
      },
      {
        path: 'preview/:id',
        name: 'Preview',
        component: () => import('@/views/Preview.vue'),
        meta: { title: '数据预览', icon: 'View' },
        props: true,
      },
      {
        path: 'clean/:id',
        name: 'Clean',
        component: () => import('@/views/Clean.vue'),
        meta: { title: '数据清洗', icon: 'Brush' },
        props: true,
      },
      {
        path: 'analysis/:id',
        name: 'Analysis',
        component: () => import('@/views/Analysis.vue'),
        meta: { title: '可视化分析', icon: 'DataAnalysis' },
        props: true,
      },
      {
        path: 'ai-config',
        name: 'AiConfig',
        component: () => import('@/views/AiConfig.vue'),
        meta: { title: 'AI 配置', icon: 'ChatDotRound' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 全局路由守卫
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')

  if (to.path === '/login') {
    if (token) {
      // 已登录用户访问登录页 → 跳转首页
      next('/dataset')
    } else {
      next()
    }
  } else {
    if (token) {
      next()
    } else {
      // 未登录 → 跳转登录页
      next('/login')
    }
  }
})

export default router
