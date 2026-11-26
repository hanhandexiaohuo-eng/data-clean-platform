<template>
  <div class="dc-page" v-loading="loading">
    <template v-if="appMode.publicMode">
      <div class="dc-card">
        <div class="dc-empty">
          <el-icon class="dc-empty-icon"><Lock /></el-icon>
          <h4>该页面仅私有模式可用</h4>
          <p>公开临时工作区不提供系统与运行状态信息。如需查看，请在私有工作区登录后访问。</p>
          <el-button type="primary" @click="router.push('/workbench')">返回工作台</el-button>
        </div>
      </div>
    </template>

    <template v-else>
      <div class="dc-page-head">
        <div>
          <h1>系统与运行状态</h1>
          <p>基于现有接口可获取的运行摘要，最后刷新：{{ lastUpdatedText }}</p>
        </div>
        <div class="dc-page-actions">
          <el-button @click="load" :loading="loading">
            <el-icon><Refresh /></el-icon>刷新
          </el-button>
        </div>
      </div>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="指标边界说明"
        description="以下指标为现有接口可获取的摘要；服务器版本、内网地址、数据库信息与密钥不展示。没有的指标显示“未采集”，不做推断。"
      />

      <el-alert
        v-if="partialError"
        type="warning"
        :closable="false"
        show-icon
        title="部分指标暂不可用"
        description="存在接口调用失败，对应指标已显示为“未采集”，可稍后刷新重试。"
      />

      <div class="dc-stat-grid">
        <div class="dc-card dc-stat">
          <div class="dc-stat-label">服务状态</div>
          <div class="dc-stat-value" :class="health.state === 'down' ? 'is-danger' : ''">{{ health.status }}</div>
          <div class="dc-stat-hint">来源：/api/health</div>
        </div>
        <div class="dc-card dc-stat">
          <div class="dc-stat-label">数据库检查</div>
          <div class="dc-stat-value" :class="health.database === 'DOWN' ? 'is-danger' : ''">{{ health.database }}</div>
          <div class="dc-stat-hint">仅返回 UP / DOWN</div>
        </div>
        <div class="dc-card dc-stat">
          <div class="dc-stat-label">任务失败 / 可恢复</div>
          <div class="dc-stat-value">{{ taskStats.failed }}</div>
          <div class="dc-stat-hint">来源：getJobs</div>
        </div>
        <div class="dc-card dc-stat">
          <div class="dc-stat-label">运行中任务</div>
          <div class="dc-stat-value">{{ taskStats.running }}</div>
          <div class="dc-stat-hint">含排队与取消中</div>
        </div>
      </div>

      <div class="dc-grid-2">
        <div class="dc-card">
          <div class="dc-card-head">
            <div>
              <div class="dc-card-title">
                <el-icon><FolderOpened /></el-icon>容量使用概览
              </div>
              <div class="dc-card-sub">基于 getDatasetList 当前可见数据集统计</div>
            </div>
          </div>
          <div class="dc-stat-grid">
            <div class="dc-stat">
              <div class="dc-stat-label">数据集数量</div>
              <div class="dc-stat-value">{{ capacity.datasetCount }}</div>
            </div>
            <div class="dc-stat">
              <div class="dc-stat-label">总存储</div>
              <div class="dc-stat-value">{{ capacity.storage }}</div>
            </div>
            <div class="dc-stat">
              <div class="dc-stat-label">总行数</div>
              <div class="dc-stat-value">{{ capacity.rows }}</div>
            </div>
            <div class="dc-stat">
              <div class="dc-stat-label">看板数量</div>
              <div class="dc-stat-value">{{ capacity.dashboardCount }}</div>
            </div>
          </div>
          <div class="dc-divider" />
          <div class="dc-card-sub" style="margin-bottom: 6px">文件体积较大的数据集</div>
          <div v-if="capacity.largest.length" class="dc-list">
            <div v-for="item in capacity.largest" :key="item.id" class="dc-list-item">
              <div class="dc-list-main">
                <div class="dc-list-title">{{ item.name }}</div>
                <div class="dc-list-meta">
                  {{ item.fileName || '文件名未记录' }} · {{ formatNumber(item.rowCount) }} 行
                </div>
              </div>
              <el-tag size="small" type="info" effect="plain">{{ formatBytes(item.fileSize) }}</el-tag>
            </div>
          </div>
          <div v-else class="dc-muted sys-empty">未采集</div>
          <div v-if="capacity.truncated" class="dc-muted sys-hint">
            当前仅统计接口返回的前 {{ capacity.loadedCount }} 个数据集，未覆盖全部记录。
          </div>
        </div>

        <div class="dc-card">
          <div class="dc-card-head">
            <div>
              <div class="dc-card-title">
                <el-icon><List /></el-icon>任务统计
              </div>
              <div class="dc-card-sub">来源：getJobs（当前调用者可见任务）</div>
            </div>
          </div>
          <div class="dc-list">
            <div class="dc-list-item">
              <div class="dc-list-main"><div class="dc-list-title">任务总数</div></div>
              <b class="sys-num">{{ taskStats.total }}</b>
            </div>
            <div class="dc-list-item">
              <div class="dc-list-main"><div class="dc-list-title">已完成</div></div>
              <b class="sys-num">{{ taskStats.succeeded }}</b>
            </div>
            <div class="dc-list-item">
              <div class="dc-list-main"><div class="dc-list-title">失败 / 可恢复</div></div>
              <b class="sys-num">{{ taskStats.failed }}</b>
            </div>
            <div class="dc-list-item">
              <div class="dc-list-main"><div class="dc-list-title">运行中</div></div>
              <b class="sys-num">{{ taskStats.running }}</b>
            </div>
          </div>
          <div class="dc-divider" />
          <div class="dc-card-sub" style="margin-bottom: 6px">运行与配置</div>
          <div class="dc-list">
            <div v-for="row in configRows" :key="row.label" class="dc-list-item">
              <div class="dc-list-main">
                <div class="dc-list-title">{{ row.label }}</div>
              </div>
              <span class="sys-value">{{ row.value }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="dc-card">
        <div class="dc-card-head">
          <div>
            <div class="dc-card-title">
              <el-icon><Lock /></el-icon>不展示的指标
            </div>
            <div class="dc-card-sub">按安全策略，以下信息不在前端展示</div>
          </div>
        </div>
        <div class="dc-list">
          <div v-for="item in unavailableMetrics" :key="item.label" class="dc-list-item">
            <div class="dc-list-main">
              <div class="dc-list-title">{{ item.label }}</div>
            </div>
            <el-tag size="small" type="info" effect="plain">{{ item.value }}</el-tag>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'
import { FolderOpened, List, Lock, Refresh } from '@element-plus/icons-vue'
import { getJobs } from '@/api/job'
import { listDashboards } from '@/api/dashboard'
import { getDatasetList } from '@/api/dataset'
import { appMode } from '@/stores/appMode'

const router = useRouter()

const loading = ref(false)
const lastUpdated = ref(null)
const health = ref({ status: '未采集', database: '未采集', state: 'idle' })

const jobsLoaded = ref(false)
const jobs = ref([])
const dashboardsLoaded = ref(false)
const dashboardCount = ref('未采集')
const datasetsLoaded = ref(false)
const datasets = ref([])
const datasetTotal = ref(null)

const loadHealth = async () => {
  health.value = { status: '检测中…', database: '检测中…', state: 'loading' }
  try {
    const res = await axios.get('/api/health', { timeout: 8000, validateStatus: () => true })
    const data = res.data || {}
    if (res.status >= 200 && res.status < 300) {
      health.value = {
        status: data.status || '未采集',
        database: data?.checks?.database || '未采集',
        state: 'ok',
      }
    } else {
      health.value = {
        status: data.status || '不可用',
        database: data?.checks?.database || '不可用',
        state: 'down',
      }
    }
  } catch (e) {
    health.value = { status: '不可用', database: '不可用', state: 'down' }
  }
}

const loadJobs = async () => {
  try {
    const res = await getJobs()
    jobs.value = Array.isArray(res) ? res : []
    jobsLoaded.value = true
  } catch (e) {
    jobs.value = []
    jobsLoaded.value = false
  }
}

const loadDashboards = async () => {
  try {
    const res = await listDashboards()
    dashboardCount.value = Array.isArray(res) ? res.length : '未采集'
    dashboardsLoaded.value = true
  } catch (e) {
    dashboardCount.value = '未采集'
    dashboardsLoaded.value = false
  }
}

const loadDatasets = async () => {
  try {
    const res = await getDatasetList({ page: 1, size: 500 })
    datasets.value = res?.records || []
    datasetTotal.value = res?.total ?? datasets.value.length
    datasetsLoaded.value = true
  } catch (e) {
    datasets.value = []
    datasetTotal.value = null
    datasetsLoaded.value = false
  }
}

const load = async () => {
  if (appMode.publicMode) return
  loading.value = true
  await Promise.allSettled([loadHealth(), loadJobs(), loadDashboards(), loadDatasets()])
  loading.value = false
  lastUpdated.value = new Date()
}

const taskStats = computed(() => {
  if (!jobsLoaded.value) {
    return { total: '未采集', succeeded: '未采集', failed: '未采集', running: '未采集' }
  }
  const isActive = (status) => ['PENDING', 'RUNNING', 'CANCEL_REQUESTED'].includes(status)
  return {
    total: jobs.value.length,
    succeeded: jobs.value.filter((job) => job.status === 'SUCCEEDED').length,
    failed: jobs.value.filter((job) => ['FAILED', 'RECOVERABLE'].includes(job.status)).length,
    running: jobs.value.filter((job) => isActive(job.status)).length,
  }
})

const capacity = computed(() => {
  if (!datasetsLoaded.value) {
    return {
      datasetCount: '未采集',
      storage: '未采集',
      rows: '未采集',
      dashboardCount: dashboardsLoaded.value ? dashboardCount.value : '未采集',
      largest: [],
      truncated: false,
      loadedCount: 0,
    }
  }
  const totalBytes = datasets.value.reduce((sum, item) => sum + (Number(item.fileSize) || 0), 0)
  const totalRows = datasets.value.reduce((sum, item) => sum + (Number(item.rowCount) || 0), 0)
  const largest = datasets.value
    .slice()
    .sort((a, b) => (Number(b.fileSize) || 0) - (Number(a.fileSize) || 0))
    .slice(0, 5)
  return {
    datasetCount: datasetTotal.value ?? datasets.value.length,
    storage: formatBytes(totalBytes),
    rows: formatNumber(totalRows),
    dashboardCount: dashboardsLoaded.value ? dashboardCount.value : '未采集',
    largest,
    truncated: Number(datasetTotal.value) > datasets.value.length,
    loadedCount: datasets.value.length,
  }
})

const configText = (key, unit = '') => {
  const value = appMode.config?.[key]
  if (value === undefined || value === null) return '未采集'
  if (typeof value === 'boolean') return value ? '开启' : '未开启'
  return `${value}${unit}`
}

const configRows = computed(() => [
  { label: '运行模式', value: appMode.mode || '未采集' },
  { label: '公开模式', value: appMode.publicMode ? '开启' : '未开启' },
  { label: '公开会话 TTL', value: configText('sessionTtlHours', ' 小时') },
  { label: '公开单文件上限', value: configText('maxFileSizeMb', ' MB') },
  { label: '公开数据集上限', value: configText('maxDatasets', ' 个') },
  { label: '公开演示数据', value: configText('demoDataEnabled') },
])

const unavailableMetrics = [
  { label: '服务器版本', value: '未采集' },
  { label: '内网地址', value: '未采集' },
  { label: '数据库信息', value: '未采集' },
  { label: '密钥 / 凭据', value: '未采集' },
]

const partialError = computed(
  () => health.value.state === 'down' || !jobsLoaded.value || !datasetsLoaded.value || !dashboardsLoaded.value
)

const lastUpdatedText = computed(() => {
  if (!lastUpdated.value) return '尚未刷新'
  return lastUpdated.value.toLocaleString('zh-CN')
})

const formatBytes = (bytes) => {
  const value = Number(bytes)
  if (!Number.isFinite(value) || value < 0) return '未采集'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(2)} MB`
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`
}

const formatNumber = (value) => {
  const num = Number(value)
  return Number.isFinite(num) ? num.toLocaleString('zh-CN') : '未采集'
}

onMounted(() => {
  if (!appMode.publicMode) load()
})
</script>

<style scoped>
.dc-stat {
  background: #fff;
  border: 1px solid var(--dc-border);
  border-radius: var(--dc-radius);
}
.is-danger {
  color: var(--dc-danger);
}
.sys-num {
  font-variant-numeric: tabular-nums;
  color: var(--dc-text);
}
.sys-value {
  font-size: 12.5px;
  color: var(--dc-text-2);
  font-variant-numeric: tabular-nums;
}
.sys-empty {
  font-size: 12.5px;
  padding: 4px 0;
}
.sys-hint {
  font-size: 11.5px;
  margin-top: 8px;
}
</style>
