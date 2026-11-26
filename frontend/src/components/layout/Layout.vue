<template>
  <div class="dc-layout">
    <!-- 移动端遮罩 -->
    <div v-if="isMobile && drawerVisible" class="dc-mask" @click="drawerVisible = false" />

    <!-- 侧边栏 / 移动端抽屉 -->
    <aside class="dc-sidebar" :class="{ 'is-mobile': isMobile, 'is-open': !isMobile || drawerVisible }">
      <div class="dc-logo">
        <el-icon :size="24" color="#fff"><DataAnalysis /></el-icon>
        <span class="dc-logo-text">DataClean</span>
        <el-tag v-if="appMode.publicMode" size="small" effect="dark" class="dc-logo-tag">公开</el-tag>
      </div>

      <nav class="dc-nav">
        <router-link
          v-for="item in visibleNav"
          :key="item.path"
          :to="item.path"
          class="dc-nav-item"
          :class="{ 'is-active': isActive(item.path) }"
          @click="drawerVisible = false"
        >
          <el-icon :size="17"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="dc-sidebar-foot">
        <div class="dc-foot-line">
          <span class="dc-dot" :class="appMode.publicMode ? 'is-warn' : 'is-ok'" />
          {{ appMode.publicMode ? '公开临时工作区' : '私有工作区' }}
        </div>
        <div class="dc-foot-sub">{{ appMode.publicMode ? '数据到期自动清理' : '完整管理能力' }}</div>
      </div>
    </aside>

    <!-- 主区域 -->
    <div class="dc-main">
      <header class="dc-header">
        <div class="dc-header-left">
          <el-button v-if="isMobile" text class="dc-menu-btn" @click="drawerVisible = true">
            <el-icon :size="20"><Expand /></el-icon>
          </el-button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/workbench' }">数据工作台</el-breadcrumb-item>
            <el-breadcrumb-item v-if="crumb && crumb !== '数据工作台'">{{ crumb }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="dc-header-right">
          <el-tag v-if="appMode.publicMode" type="warning" effect="plain" size="small" class="dc-mode-tag">
            临时会话 · {{ remainingText }}
          </el-tag>
          <el-tag v-else type="success" effect="plain" size="small" class="dc-mode-tag">私有工作区</el-tag>
          <el-tooltip content="刷新当前页面" placement="bottom">
            <el-button :icon="Refresh" circle size="small" @click="refreshPage" />
          </el-tooltip>
          <template v-if="!appMode.publicMode">
            <el-dropdown trigger="click">
              <div class="dc-user">
                <el-avatar :size="28" class="dc-avatar">{{ userStore.nickname?.charAt(0) || 'U' }}</el-avatar>
                <span class="dc-user-name">{{ userStore.nickname || '用户' }}</span>
                <el-icon><ArrowDown /></el-icon>
              </div>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </div>
      </header>

      <main class="dc-content">
        <!-- 公开模式统一提示条 -->
        <div v-if="appMode.publicMode" class="dc-public-bar">
          <div class="dc-public-item">
            <el-icon><Clock /></el-icon>
            <span>公开临时工作区，剩余 <b>{{ remainingText }}</b> 后自动清理</span>
          </div>
          <div class="dc-public-item">
            <el-icon><WarningFilled /></el-icon>
            <span>请勿上传个人隐私或保密数据</span>
          </div>
          <div class="dc-public-item">
            <el-icon><Delete /></el-icon>
            <span>临时工作区不可恢复</span>
          </div>
        </div>

        <router-view v-slot="{ Component }">
          <transition name="dc-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown, Brush, ChatDotRound, Clock, DataAnalysis, DataBoard,
  Delete, Expand, FolderOpened, Grid, List, QuestionFilled, Refresh,
  Setting, WarningFilled,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { appMode, workspaceRemainingMs } from '@/stores/appMode'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isMobile = ref(window.innerWidth < 768)
const drawerVisible = ref(false)
const now = ref(Date.now())

const navItems = [
  { path: '/workbench', label: '数据工作台', icon: DataBoard },
  { path: '/datasets', label: '数据集管理', icon: FolderOpened },
  { path: '/quality', label: '数据质量中心', icon: Brush },
  { path: '/tasks', label: '任务中心', icon: List },
  { path: '/dashboards', label: '数据看板', icon: Grid },
  { path: '/ai', label: 'AI 助手', icon: ChatDotRound },
  { path: '/help', label: '帮助与示例', icon: QuestionFilled },
  { path: '/system', label: '系统与运行状态', icon: Setting, privateOnly: true },
]

const visibleNav = computed(() => navItems.filter((i) => !i.privateOnly || !appMode.publicMode))

const crumb = computed(() => {
  const p = route.path
  if (p.startsWith('/preview/') || p.startsWith('/clean/') || p.startsWith('/analysis/')) return '数据集管理'
  if (p.startsWith('/dashboard/')) return '数据看板'
  const hit = navItems.find((i) => p.startsWith(i.path))
  return hit?.label || route.meta?.title || ''
})

function isActive(path) {
  if (path === '/datasets') {
    return ['/datasets', '/preview', '/clean', '/analysis'].some((x) => route.path.startsWith(x))
  }
  if (path === '/dashboards') return route.path.startsWith('/dashboard')
  return route.path.startsWith(path)
}

const remainingText = computed(() => {
  // 依赖 now，确保定时器触发后倒计时文案会重新计算
  void now.value
  const ms = workspaceRemainingMs()
  if (!appMode.publicMode) return ''
  if (ms <= 0) return '已到期'
  const totalMinutes = Math.ceil(ms / 60000)
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  if (hours > 0) return `${hours} 小时 ${minutes} 分`
  return `${minutes} 分钟`
})

function refreshPage() {
  router.go(0)
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '退出', cancelButtonText: '取消', type: 'warning',
    })
  } catch {
    return
  }
  userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}

function onResize() {
  isMobile.value = window.innerWidth < 768
  if (!isMobile.value) drawerVisible.value = false
}

let timer = null
onMounted(() => {
  window.addEventListener('resize', onResize)
  timer = setInterval(() => { now.value = Date.now() }, 30000)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.dc-layout { display: flex; height: 100vh; width: 100vw; overflow: hidden; background: var(--dc-bg); }

.dc-sidebar {
  width: 232px; flex: 0 0 232px;
  background: var(--dc-sidebar-bg);
  display: flex; flex-direction: column;
  transition: transform 0.25s ease;
  z-index: 30;
}
.dc-sidebar.is-mobile { position: fixed; inset: 0 auto 0 0; transform: translateX(-100%); }
.dc-sidebar.is-mobile.is-open { transform: translateX(0); box-shadow: 0 0 40px rgba(0, 0, 0, 0.35); }

.dc-logo { height: 60px; display: flex; align-items: center; gap: 10px; padding: 0 18px; border-bottom: 1px solid rgba(255, 255, 255, 0.08); }
.dc-logo-text { color: #fff; font-size: 18px; font-weight: 700; letter-spacing: 0.5px; }
.dc-logo-tag { margin-left: auto; }

.dc-nav { flex: 1; padding: 12px 10px; overflow-y: auto; }
.dc-nav-item {
  display: flex; align-items: center; gap: 10px;
  height: 42px; padding: 0 12px; margin-bottom: 4px;
  border-radius: 9px; color: #a9b4c9; font-size: 13.5px; text-decoration: none;
  transition: background 0.18s ease, color 0.18s ease;
}
.dc-nav-item:hover { background: rgba(255, 255, 255, 0.06); color: #e8edf7; }
.dc-nav-item.is-active { background: linear-gradient(135deg, var(--dc-brand), var(--dc-brand-2)); color: #fff; font-weight: 600; }

.dc-sidebar-foot { padding: 14px 18px 18px; border-top: 1px solid rgba(255, 255, 255, 0.08); }
.dc-foot-line { display: flex; align-items: center; gap: 8px; color: #d6deed; font-size: 12.5px; }
.dc-foot-sub { color: #7d8aa5; font-size: 11.5px; margin-top: 4px; padding-left: 16px; }
.dc-dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; }
.dc-dot.is-ok { background: #22c55e; }
.dc-dot.is-warn { background: #f59e0b; }

.dc-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }

.dc-header {
  height: var(--dc-header-h); flex: 0 0 var(--dc-header-h);
  background: #fff; border-bottom: 1px solid var(--dc-border);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 18px; gap: 12px;
}
.dc-header-left { display: flex; align-items: center; gap: 8px; min-width: 0; }
.dc-menu-btn { padding: 6px; }
.dc-header-right { display: flex; align-items: center; gap: 10px; }
.dc-mode-tag { white-space: nowrap; }
.dc-user { display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 4px 8px; border-radius: 9px; }
.dc-user:hover { background: #f4f6fa; }
.dc-avatar { background: linear-gradient(135deg, var(--dc-brand), var(--dc-brand-2)); color: #fff; font-size: 12px; }
.dc-user-name { font-size: 13px; max-width: 110px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.dc-content { flex: 1; overflow-y: auto; padding: 20px; }

.dc-public-bar {
  display: flex; flex-wrap: wrap; gap: 10px 22px;
  background: #fffbeb; border: 1px solid #fde68a; color: #92400e;
  border-radius: 10px; padding: 10px 14px; margin-bottom: 16px;
  font-size: 12.5px;
}
.dc-public-item { display: flex; align-items: center; gap: 6px; }
.dc-public-item b { font-variant-numeric: tabular-nums; }

.dc-mask { position: fixed; inset: 0; background: rgba(9, 13, 25, 0.45); z-index: 25; }

.dc-fade-enter-active, .dc-fade-leave-active { transition: opacity 0.16s ease; }
.dc-fade-enter-from, .dc-fade-leave-to { opacity: 0; }

@media (max-width: 640px) {
  .dc-content { padding: 14px; }
  .dc-header { padding: 0 12px; }
  .dc-user-name { display: none; }
  .dc-public-bar { gap: 6px 14px; }
}
</style>
