<template>
  <div class="dc-page">
    <div class="dc-page-head">
      <div>
        <h1>{{ appMode.publicMode ? '临时工作区' : '数据工作台' }}</h1>
        <p>{{ headSubtitle }}</p>
      </div>
      <div class="dc-page-actions">
        <el-button :loading="loading" @click="loadAll">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="primary" @click="go('/datasets')">
          <el-icon><Upload /></el-icon>导入数据
        </el-button>
      </div>
    </div>

    <template v-if="loading && !loadedOnce">
      <div class="dc-card"><el-skeleton :rows="4" animated /></div>
      <div class="dc-card"><el-skeleton :rows="6" animated /></div>
    </template>

    <template v-else>
      <el-alert v-if="error" type="error" :closable="false" show-icon title="工作区概览加载失败">
        <template #default>
          <div class="alert-line">
            <span>{{ error }}</span>
            <el-button size="small" type="primary" plain @click="loadAll">重试</el-button>
          </div>
        </template>
      </el-alert>
      <el-alert v-else-if="warn" type="warning" :closable="false" show-icon :title="warn" />

      <div class="dc-card">
        <div class="dc-card-head">
          <div class="dc-card-title">工作区概览</div>
          <span v-if="appMode.publicMode" class="dc-muted">临时会话 · 到期自动清理</span>
        </div>
        <div class="dc-stat-grid">
          <div class="dc-stat">
            <div class="dc-stat-label">数据集</div>
            <div class="dc-stat-value">{{ fmtInt(datasetTotal) }}</div>
            <div class="dc-stat-hint">{{ datasetHint }}</div>
          </div>
          <div class="dc-stat">
            <div class="dc-stat-label">任务 / 进行中</div>
            <div class="dc-stat-value">
              {{ fmtInt(jobs.length) }}<span class="unit"> / {{ fmtInt(activeJobCount) }}</span>
            </div>
            <div class="dc-stat-hint">{{ failedJobCount ? `${failedJobCount} 个失败任务待处理` : '导入、清洗、恢复任务' }}</div>
          </div>
          <div class="dc-stat">
            <div class="dc-stat-label">待处理质量问题</div>
            <div class="dc-stat-value">{{ issueFieldCount === null ? '—' : fmtInt(issueFieldCount) }}</div>
            <div class="dc-stat-hint">{{ issueFieldCount === null ? '统计尚未完成' : '前 4 个数据集 · 客户端估算' }}</div>
          </div>
          <div class="dc-stat">
            <div class="dc-stat-label">看板</div>
            <div class="dc-stat-value">{{ fmtInt(dashboards.length) }}</div>
            <div class="dc-stat-hint">已创建的数据看板</div>
          </div>
          <template v-if="appMode.publicMode">
            <div class="dc-stat">
              <div class="dc-stat-label">剩余时间</div>
              <div class="dc-stat-value small">{{ remainingText }}</div>
              <div class="dc-stat-hint">会话 {{ appMode.config?.sessionTtlHours || 24 }} 小时</div>
            </div>
            <div class="dc-stat">
              <div class="dc-stat-label">容量上限</div>
              <div class="dc-stat-value small">{{ appMode.config?.maxFileSizeMb ?? '-' }} MB</div>
              <div class="dc-stat-hint">最多 {{ appMode.config?.maxRows ?? '-' }} 行 / {{ appMode.config?.maxColumns ?? '-' }} 列</div>
            </div>
            <div class="dc-stat">
              <div class="dc-stat-label">AI 剩余额度</div>
              <div class="dc-stat-value small">{{ quotaText }}</div>
              <div class="dc-stat-hint">当前会话</div>
            </div>
          </template>
        </div>
      </div>

      <div class="dc-grid-2">
        <div class="dc-card">
          <div class="dc-card-head">
            <div>
              <div class="dc-card-title">数据质量概览</div>
              <div class="dc-card-sub">前 {{ qualityDatasets.length }} 个数据集 · 客户端汇总</div>
            </div>
            <el-button text type="primary" @click="go('/quality')">进入质量中心</el-button>
          </div>
          <el-skeleton v-if="statsLoading" :rows="4" animated />
          <div v-else-if="!qualityDatasets.length" class="dc-empty">
            <h4>暂无数据集</h4>
            <p>导入数据后，这里会展示空值率、字段数与异常值字段。</p>
            <el-button type="primary" @click="go('/datasets')">导入数据</el-button>
          </div>
          <div v-else class="dc-list">
            <div v-if="qualityTotals" class="mini-summary">
              <span>整体完整度 <strong>{{ qualityTotals.completeness === null ? '未采集' : fmtPct(qualityTotals.completeness) }}</strong></span>
              <span>平均空值率 <strong>{{ qualityTotals.nullRate === null ? '未采集' : fmtPct(qualityTotals.nullRate) }}</strong></span>
              <span>字段数 <strong>{{ fmtInt(qualityTotals.fields) }}</strong></span>
              <span>异常值字段 <strong>{{ fmtInt(qualityTotals.anomalies) }}</strong></span>
            </div>
            <div v-for="item in qualityDatasets" :key="item.id" class="dc-list-item">
              <div class="dc-list-main">
                <div class="dc-list-title">{{ item.name }}</div>
                <div class="dc-list-meta">
                  <template v-if="item.stats">
                    空值率 {{ item.stats.nullRate === null ? '未采集' : fmtPct(item.stats.nullRate) }}
                    · 字段 {{ fmtInt(item.stats.fieldCount) }}
                    · 异常字段 {{ fmtInt(item.stats.anomalyCount) }}
                  </template>
                  <span v-else class="warn-text">统计尚未完成</span>
                </div>
              </div>
              <el-tag v-if="item.stats" size="small" :type="item.issue ? 'warning' : 'success'">
                {{ item.issue ? '需关注' : '良好' }}
              </el-tag>
              <el-tag v-else size="small" type="info">待统计</el-tag>
            </div>
          </div>
        </div>

        <div class="dc-card">
          <div class="dc-card-head">
            <div class="dc-card-title">下一步推荐</div>
          </div>
          <div class="dc-list">
            <div v-for="(rec, i) in recommendations" :key="i" class="dc-list-item">
              <div class="dc-list-main">
                <div class="dc-list-title">{{ rec.title }}</div>
                <div class="dc-list-meta">{{ rec.desc }}</div>
              </div>
              <el-button size="small" :type="i === 0 ? 'primary' : 'default'" @click="go(rec.path)">
                {{ rec.action }}
              </el-button>
            </div>
          </div>
        </div>
      </div>

      <div class="dc-card">
        <div class="dc-card-head">
          <div class="dc-card-title">最近活动</div>
          <span class="dc-muted">最近 8 条</span>
        </div>
        <div v-if="!activities.length" class="dc-empty">
          <h4>暂无活动</h4>
          <p>导入数据集或运行任务后，活动会出现在这里。</p>
        </div>
        <div v-else class="dc-list">
          <div v-for="a in activities" :key="a.key" class="dc-list-item">
            <el-icon class="activity-icon"><component :is="a.icon" /></el-icon>
            <div class="dc-list-main">
              <div class="dc-list-title">{{ a.title }}</div>
              <div class="dc-list-meta">{{ a.meta }}</div>
            </div>
            <span class="dc-muted">{{ formatDate(a.time) }}</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { getDatasetList, getDatasetStatistics } from '@/api/dataset'
import { getJobs } from '@/api/job'
import { listDashboards } from '@/api/dashboard'
import { getPublicAiQuota } from '@/api/ai'
import { appMode, workspaceRemainingMs } from '@/stores/appMode'

const router = useRouter()

const loading = ref(false)
const loadedOnce = ref(false)
const error = ref('')
const warn = ref('')

const datasets = ref([])
const datasetTotal = ref(0)
const jobs = ref([])
const dashboards = ref([])

const statsMap = ref({})
const statsLoading = ref(false)

const quota = ref(null)
const quotaFailed = ref(false)
const tick = ref(Date.now())
let timer = null

const NUMERIC_TYPES = ['TINYINT', 'SMALLINT', 'MEDIUMINT', 'INT', 'INTEGER', 'BIGINT', 'NUMBER', 'DECIMAL', 'NUMERIC', 'FLOAT', 'DOUBLE', 'REAL']

const isNumericType = (t) => NUMERIC_TYPES.includes(String(t || '').toUpperCase().split('(')[0].trim())

const toNum = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = typeof v === 'number' ? v : Number(String(v).replace(/,/g, ''))
  return Number.isFinite(n) ? n : null
}

const parseRate = (v) => {
  if (v === null || v === undefined || v === '') return null
  if (typeof v === 'number') return Number.isFinite(v) ? v : null
  const n = parseFloat(String(v).replace('%', ''))
  return Number.isFinite(n) ? n : null
}

const hasAnomaly = (c) => {
  if (!isNumericType(c.dataType)) return false
  const min = toNum(c.min)
  const max = toNum(c.max)
  const q1 = toNum(c.q1)
  const q3 = toNum(c.q3)
  if (min === null || max === null || q1 === null || q3 === null) return false
  const iqr = q3 - q1
  if (iqr <= 0) return false
  return min < q1 - 1.5 * iqr || max > q3 + 1.5 * iqr
}

const summarize = (stats) => {
  const rows = toNum(stats.rowCount) || 0
  const columns = Array.isArray(stats.columns) ? stats.columns : []
  let nullSum = 0
  let anomalyCount = 0
  let issues = 0
  columns.forEach((c) => {
    nullSum += toNum(c.nullCount) || 0
    const anomaly = hasAnomaly(c)
    if (anomaly) anomalyCount += 1
    const nullRate = parseRate(c.nullRate)
    if (anomaly || (nullRate !== null && nullRate >= 20)) issues += 1
  })
  const cells = rows * columns.length
  return {
    fieldCount: columns.length,
    anomalyCount,
    issues,
    nullRate: cells > 0 ? (nullSum / cells) * 100 : null,
  }
}

const qualityDatasets = computed(() =>
  datasets.value.slice(0, 4).map((d) => {
    const entry = statsMap.value[d.id]
    if (entry && !entry.__error && !entry.__pending) {
      const info = summarize(entry)
      return { ...d, stats: info, issue: info.issues > 0 }
    }
    return { ...d, stats: null, issue: false }
  })
)

const allStats = computed(() =>
  Object.values(statsMap.value).filter((s) => s && !s.__error && !s.__pending)
)

const issueFieldCount = computed(() => {
  if (!allStats.value.length) return null
  return allStats.value.reduce((sum, s) => sum + summarize(s).issues, 0)
})

const qualityTotals = computed(() => {
  const list = allStats.value
  if (!list.length) return null
  let nullSum = 0
  let cells = 0
  let fields = 0
  let anomalies = 0
  list.forEach((s) => {
    const rows = toNum(s.rowCount) || 0
    const columns = Array.isArray(s.columns) ? s.columns : []
    cells += rows * columns.length
    fields += columns.length
    columns.forEach((c) => {
      nullSum += toNum(c.nullCount) || 0
      if (hasAnomaly(c)) anomalies += 1
    })
  })
  const nullRate = cells > 0 ? (nullSum / cells) * 100 : null
  return {
    nullRate,
    completeness: nullRate === null ? null : 100 - nullRate,
    fields,
    anomalies,
  }
})

const highNullDataset = computed(() => {
  for (const d of datasets.value) {
    const entry = statsMap.value[d.id]
    if (!entry || entry.__error || entry.__pending) continue
    const columns = Array.isArray(entry.columns) ? entry.columns : []
    const hit = columns.some((c) => {
      const rate = parseRate(c.nullRate)
      return rate !== null && rate >= 20
    })
    if (hit) return d
  }
  return null
})

const activeJobCount = computed(() =>
  jobs.value.filter((j) => ['PENDING', 'RUNNING', 'CANCEL_REQUESTED'].includes(j.status)).length
)

const failedJobCount = computed(() => jobs.value.filter((j) => j.status === 'FAILED').length)

const processingDatasetCount = computed(() => datasets.value.filter((d) => d.status === 0).length)

const datasetHint = computed(() => {
  if (processingDatasetCount.value > 0) return `${processingDatasetCount.value} 个数据集导入中`
  if (appMode.publicMode) return '当前匿名会话可见'
  return '私有工作区'
})

const headSubtitle = computed(() =>
  appMode.publicMode
    ? (appMode.config?.notice || '公开临时工作区：数据会在会话到期后自动清理，请勿上传隐私或保密数据。')
    : '汇总数据集、任务、质量与看板，快速定位下一步。'
)

const quotaText = computed(() => {
  if (!appMode.config?.aiEnabled) return '未开放'
  if (quotaFailed.value || !quota.value) return '未开放'
  if (quota.value.enabled === false) return '未开放'
  return `剩余 ${fmtInt(quota.value.remaining)} / ${fmtInt(quota.value.limit)} 次`
})

const remainingText = computed(() => {
  tick.value
  const ms = workspaceRemainingMs()
  if (ms <= 0) return '已到期'
  const h = Math.floor(ms / 3600000)
  const m = Math.floor((ms % 3600000) / 60000)
  return h > 0 ? `${h} 小时 ${m} 分` : `${m} 分钟`
})

const jobTitle = (job) => {
  const typeLabels = { IMPORT: '数据导入', CLEAN: '数据清洗', RESTORE: '版本恢复' }
  const label = typeLabels[job.jobType] || job.jobType || '后台'
  if (job.status === 'SUCCEEDED') return `${label}任务完成`
  if (job.status === 'FAILED') return `${label}任务失败`
  if (job.status === 'CANCELLED') return `${label}任务已取消`
  if (job.status === 'PENDING') return `${label}任务排队中`
  if (job.status === 'RUNNING') return `${label}任务进行中`
  return `${label}任务`
}

const activities = computed(() => {
  const list = []
  datasets.value.forEach((d) => {
    list.push({
      key: `dataset-${d.id}`,
      title: `导入数据集 · ${d.name}`,
      meta: `${fmtInt(d.rowCount)} 行 · ${fmtInt(d.columnCount)} 列`,
      time: d.createdAt,
      icon: 'Document',
    })
  })
  jobs.value.forEach((j) => {
    list.push({
      key: `job-${j.id}`,
      title: jobTitle(j),
      meta: `任务 #${j.id}${j.datasetId ? ` · 数据集 #${j.datasetId}` : ''}`,
      time: j.createdAt,
      icon: j.jobType === 'CLEAN' ? 'Brush' : 'Refresh',
    })
  })
  return list
    .filter((a) => a.time)
    .sort((a, b) => new Date(b.time).getTime() - new Date(a.time).getTime())
    .slice(0, 8)
})

const recommendations = computed(() => {
  if (!datasets.value.length) {
    const list = [
      { title: '导入第一份数据', desc: '支持 CSV / Excel，导入后可预览、清洗与分析。', action: '导入数据', path: '/datasets' },
    ]
    if (appMode.publicMode && appMode.config?.demoDataEnabled) {
      list.push({
        title: '使用演示数据',
        desc: '当前工作区已开启演示数据，可在数据集页使用官方示例。',
        action: '去数据集页',
        path: '/datasets',
      })
    }
    return list
  }

  const list = []
  if (!jobs.value.length) {
    list.push({ title: '查看数据质量', desc: '当前还没有后台任务，先确认空值率与异常字段。', action: '质量中心', path: '/quality' })
  }
  if (failedJobCount.value > 0) {
    list.push({
      title: '处理失败任务',
      desc: `有 ${failedJobCount.value} 个任务失败，请到任务中心查看原因并重试。`,
      action: '任务中心',
      path: '/tasks',
    })
  }
  if (highNullDataset.value) {
    list.push({
      title: '创建清洗规则草稿',
      desc: `数据集「${highNullDataset.value.name}」存在空值率较高的字段，可先生成草稿，需人工确认后执行。`,
      action: '清洗草稿',
      path: `/clean/${highNullDataset.value.id}`,
    })
  }
  if (!list.length) {
    const dash = dashboards.value[0]
    list.push({
      title: '生成图表',
      desc: '字段统计已就绪，可在可视化分析中生成图表并沉淀到看板。',
      action: '查看看板',
      path: dash ? `/dashboard/${dash.id}` : '/dashboards',
    })
  }
  return list
})

const fmtInt = (v) => {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  return Number.isFinite(n) ? n.toLocaleString('zh-CN') : String(v)
}

const fmtPct = (v) => `${Number(v).toFixed(1)}%`

const formatDate = (v) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-')

const go = (path) => router.push(path)

const loadStats = async () => {
  const targets = datasets.value.slice(0, 4)
  if (!targets.length) {
    statsMap.value = {}
    return
  }
  const pending = {}
  targets.forEach((d) => {
    pending[d.id] = { __pending: true }
  })
  statsMap.value = pending
  statsLoading.value = true
  try {
    const results = await Promise.allSettled(targets.map((d) => getDatasetStatistics(d.id)))
    const next = {}
    results.forEach((r, i) => {
      next[targets[i].id] = r.status === 'fulfilled' ? r.value : { __error: true }
    })
    statsMap.value = next
  } finally {
    statsLoading.value = false
  }
}

const loadQuota = async () => {
  if (!appMode.publicMode) return
  if (!appMode.config?.aiEnabled) return
  quotaFailed.value = false
  try {
    quota.value = await getPublicAiQuota()
  } catch (e) {
    quota.value = null
    quotaFailed.value = true
  }
}

const loadAll = async () => {
  loading.value = true
  error.value = ''
  warn.value = ''
  try {
    const [dsRes, jobRes, dashRes] = await Promise.allSettled([
      getDatasetList({ page: 1, size: 100 }),
      getJobs(),
      listDashboards(),
    ])

    if (dsRes.status === 'fulfilled') {
      const data = dsRes.value || {}
      datasets.value = data.records || []
      datasetTotal.value = typeof data.total === 'number' ? data.total : datasets.value.length
    } else {
      datasets.value = []
      datasetTotal.value = 0
      error.value = dsRes.reason?.message || '数据集加载失败，请稍后重试。'
    }

    jobs.value = jobRes.status === 'fulfilled' ? jobRes.value || [] : []
    dashboards.value = dashRes.status === 'fulfilled' ? dashRes.value || [] : []

    const failedParts = []
    if (jobRes.status === 'rejected') failedParts.push('任务')
    if (dashRes.status === 'rejected') failedParts.push('看板')
    if (failedParts.length) {
      warn.value = `${failedParts.join('、')}数据加载失败，其余概览仍可查看。`
    }

    await loadStats()
    await loadQuota()
  } finally {
    loading.value = false
    loadedOnce.value = true
  }
}

onMounted(() => {
  loadAll()
  timer = setInterval(() => {
    tick.value = Date.now()
  }, 30000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.alert-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.unit {
  font-size: 15px;
  font-weight: 500;
  color: var(--dc-text-3);
}
.dc-stat-value.small {
  font-size: 19px;
}
.activity-icon {
  color: var(--dc-text-3);
  margin-top: 2px;
}
.warn-text {
  color: var(--dc-warning);
}
.mini-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  padding: 8px 0 12px;
  margin-bottom: 4px;
  border-bottom: 1px dashed var(--dc-border);
  font-size: 12px;
  color: var(--dc-text-3);
}
.mini-summary strong {
  color: var(--dc-text);
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
</style>
