<template>
  <div class="dc-page">
    <div class="dc-page-head">
      <div>
        <h1>任务中心</h1>
        <p>导入、清洗、版本恢复、质量统计、图表生成、导出与 AI 调用统一记录，可查看进度、取消与结果跳转</p>
      </div>
      <div class="dc-page-actions">
        <el-tag v-if="hasActive" type="warning" effect="plain" size="small">自动刷新中 · 4s</el-tag>
        <el-button @click="loadData()" :loading="loading">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="appMode.publicMode"
      type="warning"
      :closable="false"
      show-icon
      title="仅显示本匿名会话任务"
      description="公开模式下无法查看其他会话的任务；会话到期后任务记录随临时工作区一并清理。"
    />

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="任务类型已统一接入"
      description="质量统计、图表生成、导出与 AI 调用均写入任务中心；同步任务完成后不可重试，需在原页面重新发起。"
    />

    <div class="dc-stat-grid">
      <div class="dc-card dc-stat">
        <div class="dc-stat-label">任务总数</div>
        <div class="dc-stat-value">{{ summary.total }}</div>
        <div class="dc-stat-hint">当前可见任务</div>
      </div>
      <div class="dc-card dc-stat">
        <div class="dc-stat-label">运行中</div>
        <div class="dc-stat-value">{{ summary.running }}</div>
        <div class="dc-stat-hint">含排队与取消中</div>
      </div>
      <div class="dc-card dc-stat">
        <div class="dc-stat-label">失败 / 可恢复</div>
        <div class="dc-stat-value">{{ summary.failed }}</div>
        <div class="dc-stat-hint">可在操作列重试</div>
      </div>
      <div class="dc-card dc-stat">
        <div class="dc-stat-label">已完成</div>
        <div class="dc-stat-value">{{ summary.succeeded }}</div>
        <div class="dc-stat-hint">成功结束的任务</div>
      </div>
    </div>

    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      :title="`任务列表加载失败：${loadError}`"
    >
      <template #default>
        <el-button size="small" @click="loadData()">重新加载</el-button>
      </template>
    </el-alert>

    <div class="dc-card">
      <div class="dc-card-head jobs-filter">
        <div class="dc-card-title">
          <el-icon><List /></el-icon>
          任务列表
          <span class="dc-muted jobs-count">共 {{ filteredJobs.length }} 条</span>
        </div>
        <div class="dc-page-actions">
          <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 130px">
            <el-option v-for="(label, value) in statusLabels" :key="value" :label="label" :value="value" />
          </el-select>
          <el-select v-model="filters.jobType" placeholder="全部类型" clearable style="width: 130px">
            <el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" />
          </el-select>
          <el-select v-model="filters.datasetId" placeholder="全部数据集" clearable style="width: 150px">
            <el-option v-for="opt in datasetOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            style="width: 240px"
          />
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>

      <el-table
        :data="filteredJobs"
        v-loading="loading"
        :empty-text="emptyText"
        size="default"
      >
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ typeLabel(row.jobType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度 / 阶段" min-width="190">
          <template #default="{ row }">
            <el-progress
              :percentage="Number(row.progress) || 0"
              :status="progressStatus(row)"
              :stroke-width="8"
            />
            <div class="progress-meta">
              <span>{{ row.phase || '阶段未上报' }}</span>
              <span v-if="row.totalRows">{{ Number(row.processedRows || 0).toLocaleString() }} / {{ Number(row.totalRows).toLocaleString() }} 行</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="数据集与版本" min-width="140">
          <template #default="{ row }">
            <div>{{ row.datasetId ? `数据集 #${row.datasetId}` : '未关联数据集' }}</div>
            <div class="dc-muted version-line">
              {{ versionLine(row) }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="130">
          <template #default="{ row }">{{ elapsedLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="失败摘要" min-width="180">
          <template #default="{ row }">
            <span class="error-text">{{ sanitizeError(row.errorSummary) || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
            <el-button v-if="canRetry(row)" link type="primary" size="small" @click="handleRetry(row)">重试</el-button>
            <el-button v-if="isActive(row.status)" link type="warning" size="small" @click="handleCancel(row)">取消</el-button>
            <el-button v-if="row.datasetId" link type="success" size="small" @click="goResult(row)">结果</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 任务详情 -->
    <el-dialog v-model="detailVisible" title="任务详情" width="680px">
      <div v-loading="detailLoading">
        <el-descriptions v-if="detailJob" :column="2" border size="small">
          <el-descriptions-item label="任务 ID">#{{ detailJob.id }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ typeLabel(detailJob.jobType) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detailJob.status)" size="small">{{ statusLabel(detailJob.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="进度">{{ Number(detailJob.progress) || 0 }}%</el-descriptions-item>
          <el-descriptions-item label="当前阶段">{{ detailJob.phase || '阶段未上报' }}</el-descriptions-item>
          <el-descriptions-item label="数据集">
            {{ detailJob.datasetId ? `#${detailJob.datasetId}` : '未关联' }}
          </el-descriptions-item>
          <el-descriptions-item label="版本">{{ versionLine(detailJob) }}</el-descriptions-item>
          <el-descriptions-item label="重试">
            {{ detailJob.retryCount || 0 }} / {{ detailJob.maxRetries ?? '未采集' }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(detailJob.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ formatDateTime(detailJob.startedAt) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ formatDateTime(detailJob.finishedAt) }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ elapsedLabel(detailJob) }}</el-descriptions-item>
        </el-descriptions>
        <el-alert
          v-if="detailJob?.errorSummary"
          type="error"
          :closable="false"
          show-icon
          title="失败摘要（已脱敏）"
          :description="sanitizeError(detailJob.errorSummary)"
          style="margin-top: 12px"
        />
        <el-alert
          v-if="detailJob?.resultSummary"
          type="success"
          :closable="false"
          show-icon
          title="结果摘要"
          :description="sanitizeError(detailJob.resultSummary)"
          style="margin-top: 12px"
        />
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button
          v-if="detailJob?.datasetId"
          type="primary"
          @click="goResult(detailJob)"
        >查看结果数据</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { List, Refresh } from '@element-plus/icons-vue'
import { getJobs, getJob, cancelJob, retryJob } from '@/api/job'
import { appMode } from '@/stores/appMode'

const router = useRouter()

const loading = ref(false)
const jobs = ref([])
const loadError = ref('')
const filters = reactive({ status: '', jobType: '', datasetId: '' })
const dateRange = ref([])
const now = ref(Date.now())

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailJob = ref(null)

let pollTimer = null
let clockTimer = null

const typeLabels = {
  IMPORT: '数据导入',
  CLEAN: '数据清洗',
  RESTORE: '版本恢复',
  QUALITY_STATS: '质量统计',
  CHART: '图表生成',
  EXPORT: '导出',
  AI: 'AI 调用',
}
const WORKER_TYPES = ['IMPORT', 'CLEAN', 'RESTORE']
const statusLabels = {
  PENDING: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCEL_REQUESTED: '取消中',
  CANCELLED: '已取消',
  RECOVERABLE: '可恢复',
}
const statusTypes = {
  PENDING: 'info',
  RUNNING: 'primary',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  CANCEL_REQUESTED: 'warning',
  CANCELLED: 'info',
  RECOVERABLE: 'warning',
}

const typeLabel = (type) => typeLabels[type] || type || '未知'
const statusLabel = (status) => statusLabels[status] || status || '未知'
const statusType = (status) => statusTypes[status] || 'info'
const isActive = (status) => ['PENDING', 'RUNNING', 'CANCEL_REQUESTED'].includes(status)
const canRetry = (row) =>
  WORKER_TYPES.includes(row.jobType) &&
  ['FAILED', 'RECOVERABLE', 'CANCELLED'].includes(row.status) &&
  (row.retryCount || 0) < (row.maxRetries ?? 0)

const hasActive = computed(() => jobs.value.some((job) => isActive(job.status)))

const summary = computed(() => ({
  total: jobs.value.length,
  running: jobs.value.filter((job) => isActive(job.status)).length,
  failed: jobs.value.filter((job) => ['FAILED', 'RECOVERABLE'].includes(job.status)).length,
  succeeded: jobs.value.filter((job) => job.status === 'SUCCEEDED').length,
}))

const datasetOptions = computed(() => {
  const ids = new Set()
  jobs.value.forEach((job) => {
    if (job.datasetId !== null && job.datasetId !== undefined) ids.add(job.datasetId)
  })
  return Array.from(ids)
    .sort((a, b) => a - b)
    .map((id) => ({ value: id, label: `数据集 #${id}` }))
})

const filteredJobs = computed(() => {
  return jobs.value.filter((job) => {
    if (filters.status && job.status !== filters.status) return false
    if (filters.jobType && job.jobType !== filters.jobType) return false
    if (filters.datasetId && job.datasetId !== filters.datasetId) return false
    if (Array.isArray(dateRange.value) && dateRange.value.length === 2) {
      const created = job.createdAt ? new Date(job.createdAt).getTime() : null
      if (created === null || Number.isNaN(created)) return false
      const start = new Date(`${dateRange.value[0]}T00:00:00`).getTime()
      const end = new Date(`${dateRange.value[1]}T23:59:59`).getTime()
      if (created < start || created > end) return false
    }
    return true
  })
})

const emptyText = computed(() => {
  if (jobs.value.length === 0) return '暂无后台任务'
  return '没有符合筛选条件的任务'
})

const loadData = async (silent = false) => {
  if (!silent) loading.value = true
  try {
    const res = await getJobs()
    jobs.value = Array.isArray(res) ? res : []
    loadError.value = ''
  } catch (e) {
    loadError.value = e?.message || '未知错误'
  } finally {
    if (!silent) loading.value = false
    schedulePoll()
  }
}

const schedulePoll = () => {
  if (pollTimer) clearTimeout(pollTimer)
  if (jobs.value.some((job) => isActive(job.status))) {
    pollTimer = setTimeout(() => loadData(true), 4000)
  }
}

const resetFilters = () => {
  filters.status = ''
  filters.jobType = ''
  filters.datasetId = ''
  dateRange.value = []
}

const progressStatus = (row) => {
  if (row.status === 'FAILED' || row.status === 'RECOVERABLE') return 'exception'
  if (row.status === 'SUCCEEDED') return 'success'
  return ''
}

const versionLine = (row) => {
  const input = row.inputVersionId ?? null
  const output = row.outputVersionId ?? null
  if (input === null && output === null) return '版本未记录'
  return `V${input ?? '—'} → V${output ?? '—'}`
}

const formatDateTime = (value) => {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN')
}

const elapsedLabel = (row) => {
  if (!row.createdAt) return '—'
  const start = new Date(row.createdAt).getTime()
  if (Number.isNaN(start)) return '—'
  let end = row.finishedAt ? new Date(row.finishedAt).getTime() : null
  if (end === null || Number.isNaN(end)) {
    if (!isActive(row.status)) return '—'
    end = now.value
  }
  const seconds = Math.max(0, Math.floor((end - start) / 1000))
  let text
  if (seconds < 60) {
    text = `${seconds} 秒`
  } else if (seconds < 3600) {
    text = `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
  } else {
    text = `${Math.floor(seconds / 3600)} 时 ${Math.floor((seconds % 3600) / 60)} 分`
  }
  return isActive(row.status) ? `已耗时 ${text}` : text
}

/**
 * 失败摘要脱敏：包含 SQL、堆栈、绝对路径等技术细节时统一模糊化，
 * 避免把内部实现暴露给普通用户。
 */
const sanitizeError = (text) => {
  if (!text) return ''
  const raw = String(text)
  const risky =
    /\b(select|insert|update|delete|drop|alter|create)\s+/i.test(raw) ||
    /exception|stack\s*trace|\bat\s+[\w.$]+\(/i.test(raw) ||
    /java\.|com\.dataclean|[A-Za-z]:\\|\/var\/|\/home\/|\/opt\//i.test(raw)
  if (risky) return '任务执行失败（技术细节已隐藏，请联系管理员）'
  const withoutPath = raw
    .replace(/[A-Za-z]:\\[^\s]+/g, '[路径已隐藏]')
    .replace(/\/(?:[\w.-]+\/)+[\w.-]*/g, '[路径已隐藏]')
  return withoutPath.length > 80 ? `${withoutPath.slice(0, 80)}…` : withoutPath
}

const openDetail = async (row) => {
  detailJob.value = row
  detailVisible.value = true
  detailLoading.value = true
  try {
    detailJob.value = await getJob(row.id)
  } catch (e) {
    // 拦截器已提示；继续展示列表中的快照
  } finally {
    detailLoading.value = false
  }
}

const goResult = (row) => {
  if (row?.jobType === 'QUALITY_STATS') {
    router.push(row.datasetId ? `/quality?dataset=${row.datasetId}` : '/quality')
    return
  }
  if (row?.jobType === 'CHART' || row?.jobType === 'EXPORT') {
    router.push('/dashboards')
    return
  }
  if (row?.jobType === 'AI') {
    router.push('/ai')
    return
  }
  if (!row?.datasetId) {
    ElMessage.info('该任务没有可跳转的数据集')
    return
  }
  router.push(`/preview/${row.datasetId}`)
}

const handleCancel = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定取消任务 #${row.id}？已完成的步骤不会被回滚。`,
      '取消任务',
      { confirmButtonText: '确认取消', cancelButtonText: '返回', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await cancelJob(row.id)
    ElMessage.success('已发送取消请求')
    loadData(true)
  } catch (e) {
    // 拦截器已提示后端 message
  }
}

const handleRetry = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定重试任务 #${row.id}？任务将重新排队并重新执行。`,
      '重试任务',
      { confirmButtonText: '确认重试', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await retryJob(row.id)
    ElMessage.success('已重新排队')
    loadData(true)
  } catch (e) {
    // 拦截器已提示后端 message
  }
}

onMounted(() => {
  loadData()
  clockTimer = setInterval(() => {
    now.value = Date.now()
  }, 10000)
})

onUnmounted(() => {
  if (pollTimer) clearTimeout(pollTimer)
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<style scoped>
.jobs-filter {
  flex-wrap: wrap;
}
.jobs-count {
  font-size: 12px;
  font-weight: 400;
}
.progress-meta {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: var(--dc-text-3);
  margin-top: 4px;
}
.version-line {
  font-size: 12px;
  margin-top: 2px;
}
.error-text {
  color: var(--dc-danger);
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}
</style>
