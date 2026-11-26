import { createRouter, createWebHistory } from 'vue-router'
import Layout from '@/components/layout/Layout.vue'
import { appMode, loadAppMode } from '@/stores/appMode'

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
    redirect: '/workbench',
    children: [
      {
        path: 'workbench',
        name: 'Workbench',
        component: () => import('@/views/Workbench.vue'),
        meta: { title: '数据工作台' },
      },
      {
        path: 'datasets',
        alias: ['/dataset'],
        name: 'Datasets',
        component: () => import('@/views/Dataset.vue'),
        meta: { title: '数据集管理' },
      },
      {
        path: 'preview/:id',
        name: 'Preview',
        component: () => import('@/views/Preview.vue'),
        meta: { title: '数据预览' },
        props: true,
      },
      {
        path: 'clean/:id',
        name: 'Clean',
        component: () => import('@/views/Clean.vue'),
        meta: { title: '数据清洗' },
        props: true,
      },
      {
        path: 'analysis/:id',
        name: 'Analysis',
        component: () => import('@/views/Analysis.vue'),
        meta: { title: '可视化分析' },
        props: true,
      },
      {
        path: 'quality',
        name: 'Quality',
        component: () => import('@/views/Quality.vue'),
        meta: { title: '数据质量中心' },
      },
      {
        path: 'tasks',
        alias: ['/jobs'],
        name: 'Tasks',
        component: () => import('@/views/Jobs.vue'),
        meta: { title: '任务中心' },
      },
      {
        path: 'dashboards',
        alias: ['/dashboard'],
        name: 'Dashboards',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '数据看板' },
      },
      {
        path: 'dashboard/:id',
        name: 'DashboardDetail',
        component: () => import('@/views/DashboardDetail.vue'),
        meta: { title: '看板详情' },
        props: true,
      },
      {
        path: 'ai',
        name: 'AiAssistant',
        component: () => import('@/views/AiAssistant.vue'),
        meta: { title: 'AI 助手' },
      },
      {
        path: 'help',
        name: 'Help',
        component: () => import('@/views/Help.vue'),
        meta: { title: '帮助与示例' },
      },
      {
        path: 'system',
        name: 'SystemStatus',
        component: () => import('@/views/SystemStatus.vue'),
        meta: { title: '系统与运行状态', privateOnly: true },
      },
      {
        path: 'ai-config',
        name: 'AiConfig',
        component: () => import('@/views/AiConfig.vue'),
        meta: { title: 'AI 配置', privateOnly: true },
      },
      {
        path: 'ai-governance',
        name: 'AiGovernance',
        component: () => import('@/views/AiGovernance.vue'),
        meta: { title: 'AI 治理', privateOnly: true },
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

const PUBLIC_BLOCKED = ['/login', '/system', '/ai-config', '/ai-governance']

router.beforeEach(async (to, from, next) => {
  if (!appMode.loaded) {
    await loadAppMode()
  }

  if (appMode.publicMode) {
    // 公开模式：无需登录，但系统配置/运维类页面一律不可访问
    if (PUBLIC_BLOCKED.some((p) => to.path === p || to.path.startsWith(p + '/'))) {
      next('/workbench')
      return
    }
    next()
    return
  }

  const token = localStorage.getItem('token')
  if (to.path === '/login') {
    next(token ? '/workbench' : undefined)
    return
  }
  if (!token) {
    next('/login')
    return
  }
  next()
})

export default router
