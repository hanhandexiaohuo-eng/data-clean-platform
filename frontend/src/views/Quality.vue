<template>
  <div class="dc-page">
    <div class="dc-page-head">
      <div>
        <h1>数据质量中心</h1>
        <p>重复率与类型不一致由后端数据库聚合真实计算；空值与字段分布来自数据集统计接口。</p>
      </div>
      <div class="dc-page-actions">
        <el-button type="primary" :loading="qualityLoading" :disabled="!selectedDatasetId" @click="computeQualityNow">
          <el-icon><DataAnalysis /></el-icon>{{ qualityReady ? '重新统计' : '开始质量统计' }}
        </el-button>
        <el-button :loading="statsLoading" :disabled="!selectedDatasetId" @click="refreshStats">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="warning" :disabled="!canDraft" @click="goCleanDraft">
          <el-icon><Brush /></el-icon>生成清洗规则草稿
        </el-button>
      </div>
    </div>

    <div class="dc-card">
      <div class="dc-card-head">
        <div>
          <div class="dc-card-title">数据集与数据版本</div>
          <div class="dc-card-sub">版本切换仅作展示与说明，不会触发清洗或恢复</div>
        </div>
      </div>
      <div class="select-row">
        <el-select
          v-model="selectedDatasetId"
          placeholder="选择数据集"
          filterable
          style="width: 280px"
          :disabled="!datasets.length"
          @change="onDatasetChange"
        >
          <el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>

        <el-select
          v-model="selectedVersionId"
          placeholder="数据版本"
          style="width: 300px"
          :disabled="!versions.length"
          @change="onVersionChange"
        >
          <el-option v-for="v in versions" :key="v.id" :label="versionLabel(v)" :value="v.id" />
        </el-select>

        <span v-if="!datasets.length" class="dc-muted">暂无可选数据集</span>
      </div>
      <el-alert
        v-if="versionNotice"
        type="warning"
        :closable="false"
        show-icon
        :title="versionNotice"
        style="margin-top: 12px"
      />
    </div>

    <div v-if="initializing" class="dc-card">
      <el-skeleton :rows="8" animated />
    </div>

    <template v-else-if="!datasets.length">
      <div class="dc-card">
        <div class="dc-empty">
          <h4>暂无数据集</h4>
          <p>请先导入数据，统计完成后即可查看字段级质量明细。</p>
          <el-button type="primary" @click="router.push('/datasets')">导入数据</el-button>
        </div>
      </div>
    </template>

    <template v-else>
      <el-alert
        v-if="qualityStatus === 'FAILED'"
        type="error"
        :closable="false"
        show-icon
        title="质量统计失败"
        :description="qualityMessage || '统计计算失败，请稍后重试；任务中心可查看安全失败摘要。'"
      >
        <template #default>
          <div class="alert-line">
            <span>{{ qualityMessage || '统计计算失败，请稍后重试。' }}</span>
            <el-button size="small" type="primary" plain @click="computeQualityNow">重新统计</el-button>
          </div>
        </template>
      </el-alert>
      <el-alert
        v-else-if="qualityStatus === 'COMPUTING'"
        type="info"
        :closable="false"
        show-icon
        title="统计计算中"
        description="正在使用数据库聚合计算重复率与类型不一致，请稍候刷新。"
      />
      <el-alert
        v-else-if="qualityStatus === 'NOT_COLLECTED'"
        type="warning"
        :closable="false"
        show-icon
        title="质量统计尚未采集"
        description="点击“开始质量统计”后由后端真实计算；未采集不会用 0 或模拟数据代替。"
      />
      <el-alert
        v-else-if="quality && quality.stale"
        type="warning"
        :closable="false"
        show-icon
        title="质量统计已过期"
        description="统计结果超过缓存有效期，请重新统计后再作为判断依据。"
      />

      <el-alert
        v-if="statsState === 'missing'"
        type="warning"
        :closable="false"
        show-icon
        title="统计尚未完成"
        :description="statsError || '数据集可能仍在导入或统计缓存尚未生成，请稍后刷新。'"
      >
        <template #default>
          <div class="alert-line">
            <span>{{ statsError || '数据集可能仍在导入或统计缓存尚未生成，请稍后刷新。' }}</span>
            <el-button size="small" type="primary" plain @click="refreshStats">重试</el-button>
          </div>
        </template>
      </el-alert>

      <el-alert
        v-else-if="statsState === 'history'"
        type="info"
        :closable="false"
        show-icon
        title="历史版本：仅提供质量统计"
        description="空值率与字段分布按当前版本统计；重复率与类型不一致按所选历史版本真实计算。"
      />

      <template v-else>
        <el-alert
          v-if="stats && stats.approximate"
          type="info"
          :closable="false"
          show-icon
          :title="`统计基于抽样（${fmtInt(stats.sampleRows)} 行）计算，空值与分位数为近似值`"
        />
        <el-alert
          v-if="statsState === 'ok' && stats && !columns.length"
          type="warning"
          :closable="false"
          show-icon
          title="统计尚未完成"
          description="当前数据集未返回字段统计信息。"
        />
        <el-alert
          v-else-if="statsState === 'ok' && rowCount === 0"
          type="info"
          :closable="false"
          show-icon
          title="数据集暂无数据"
          description="数据集行数为 0，无法计算空值率、唯一值等质量指标。"
        />

        <div class="dc-card">
          <div class="dc-card-head">
            <div>
              <div class="dc-card-title">质量总览</div>
              <div class="dc-card-sub">不含“未采集”项的估算均由客户端汇总</div>
            </div>
            <el-tag v-if="summary" size="small" :type="priorityType">
              问题优先级：{{ summary.priority }}
            </el-tag>
          </div>
          <div class="dc-stat-grid">
            <div class="dc-stat">
              <div class="dc-stat-label">空值率</div>
              <div class="dc-stat-value">{{ summary && summary.nullRate !== null ? fmtPct(summary.nullRate) : '—' }}</div>
              <div class="dc-stat-hint">按单元格加权 · 统计接口</div>
            </div>
            <div class="dc-stat">
              <div class="dc-stat-label">重复率</div>
              <div class="dc-stat-value" :class="{ uncollected: !qualityReady }">
                {{ qualityReady ? quality.duplicate.duplicateRate : qualityStatusText }}
              </div>
              <div class="dc-stat-hint">
                {{ qualityReady ? `${fmtInt(quality.duplicate.duplicateRows)} 行重复 · 数据库聚合` : '需先开始质量统计' }}
              </div>
            </div>
            <div class="dc-stat">
              <div class="dc-stat-label">异常值字段数</div>
              <div class="dc-stat-value">{{ summary ? fmtInt(summary.anomalyCount) : '—' }}</div>
              <div class="dc-stat-hint">IQR 1.5 倍 · 客户端估算</div>
            </div>
            <div class="dc-stat">
              <div class="dc-stat-label">类型不一致</div>
              <div class="dc-stat-value" :class="{ uncollected: !qualityReady }">
                {{ qualityReady ? fmtInt(quality.typeMismatch.mismatchColumnCount) : qualityStatusText }}
              </div>
              <div class="dc-stat-hint">
                {{ qualityReady ? `格式异常字段数 · 受影响 ${fmtInt(quality.typeMismatch.totalMismatchRows)} 行` : '需先开始质量统计' }}
              </div>
            </div>
            <div class="dc-stat">
              <div class="dc-stat-label">唯一值比例</div>
              <div class="dc-stat-value">{{ summary && summary.uniqueness !== null ? fmtPct(summary.uniqueness) : '—' }}</div>
              <div class="dc-stat-hint">按字段均值 · 客户端汇总</div>
            </div>
            <div class="dc-stat">
              <div class="dc-stat-label">字段质量评分</div>
              <div class="dc-stat-value">{{ summary && summary.score != null ? summary.score : '—' }}</div>
              <div class="dc-stat-hint">{{ rowCount === 0 ? '无数据不评分' : '0~100 · 客户端估算（空值 + IQR 加权）' }}</div>
            </div>
            <div class="dc-stat">
              <div class="dc-stat-label">问题优先级</div>
              <div class="dc-stat-value">{{ summary ? summary.priority : '—' }}</div>
              <div class="dc-stat-hint">基于空值率与异常字段 · 客户端估算</div>
            </div>
          </div>
        </div>

        <div class="dc-card">
          <div class="dc-card-head">
            <div>
              <div class="dc-card-title">字段明细</div>
              <div class="dc-card-sub">{{ fmtInt(fieldRows.length) }} 个字段 · 字段评分与格式异常来自真实统计</div>
            </div>
            <el-button text type="primary" :disabled="!canDraft" @click="goCleanDraft">生成清洗草稿</el-button>
          </div>

          <el-table
            v-if="fieldRows.length"
            v-loading="statsLoading || qualityLoading"
            :data="fieldRows"
            size="small"
            border
            max-height="560"
            empty-text="暂无字段统计"
          >
            <el-table-column label="字段名" width="140" fixed>
              <template #default="{ row }">
                <span class="dc-mono">{{ row.columnName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="row.typeSupported ? 'info' : 'danger'">
                  {{ row.dataType || '未知' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="格式异常" width="150">
              <template #default="{ row }">
                <span v-if="row.mismatchCount === null" class="dc-muted">未采集</span>
                <span v-else-if="row.mismatchCount === 0" class="ok-text">无异常</span>
                <el-tooltip v-else placement="top" :content="`示例：${row.mismatchSamplesText}`">
                  <span class="danger-text">{{ fmtInt(row.mismatchCount) }} ({{ row.mismatchRate }})</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="字段评分" width="90">
              <template #default="{ row }">
                <span v-if="row.score === null" class="dc-muted">未采集</span>
                <span v-else>{{ row.score }}</span>
              </template>
            </el-table-column>
            <el-table-column label="示例值 (min ~ max)" min-width="170">
              <template #default="{ row }">
                <span v-if="row.example" class="dc-ellipsis">{{ row.example }}</span>
                <span v-else class="dc-muted">未采集</span>
              </template>
            </el-table-column>
            <el-table-column label="空值数 / 率" width="130">
              <template #default="{ row }">
                <span>{{ fmtInt(row.nullCount) }}</span>
                <span class="dc-muted"> / {{ row.nullRate === null ? '未采集' : fmtPct(row.nullRate) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="唯一值" width="100">
              <template #default="{ row }">
                {{ row.uniqueCount === null ? '未采集' : fmtInt(row.uniqueCount) }}
              </template>
            </el-table-column>
            <el-table-column label="最小 / 最大" min-width="150">
              <template #default="{ row }">
                <span v-if="row.min !== null || row.max !== null">
                  {{ fmtCell(row.min) }} / {{ fmtCell(row.max) }}
                </span>
                <span v-else class="dc-muted">未采集</span>
              </template>
            </el-table-column>
            <el-table-column label="分布（中位与 IQR）" min-width="220">
              <template #default="{ row }">
                <div v-if="row.dist" class="dist-wrap">
                  <div class="dist-track">
                    <span class="dist-iqr" :style="{ left: row.dist.left + '%', width: row.dist.width + '%' }"></span>
                    <span class="dist-median" :style="{ left: row.dist.medianLeft + '%' }"></span>
                  </div>
                  <div class="dist-labels">
                    最小 {{ fmtCell(row.dist.min) }} · 中位 {{ fmtCell(row.dist.median) }} · 最大 {{ fmtCell(row.dist.max) }}
                  </div>
                  <div class="dist-labels">
                    Q1 {{ fmtCell(row.dist.q1) }} · Q3 {{ fmtCell(row.dist.q3) }}
                  </div>
                </div>
                <span v-else class="dc-muted">未采集</span>
              </template>
            </el-table-column>
            <el-table-column label="问题标签" min-width="160">
              <template #default="{ row }">
                <div class="dc-tag-row">
                  <el-tag v-for="t in row.tags" :key="t.label" size="small" :type="t.type">{{ t.label }}</el-tag>
                  <span v-if="!row.tags.length" class="dc-muted">无</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="建议处理方式" min-width="230">
              <template #default="{ row }">
                <span class="suggestion">{{ row.suggestion }}</span>
              </template>
            </el-table-column>
            <el-table-column label="预计影响行数" width="140">
              <template #default="{ row }">
                <span>{{ row.impact }}</span>
              </template>
            </el-table-column>
          </el-table>

          <div v-else class="dc-empty">
            <h4>暂无字段统计</h4>
            <p>统计尚未完成或当前数据集没有可展示的字段，请刷新统计后重试。</p>
            <el-button type="primary" plain @click="refreshStats">刷新统计</el-button>
          </div>
        </div>

        <p class="footnote dc-muted">
          重复率与类型不一致来自后端数据库聚合的真实统计；空值率与异常值为统计接口的辅助估算。
          所有清洗动作均需人工确认，不会自动执行。
        </p>
      </template>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDatasetList, getDatasetStatistics, getVersions } from '@/api/dataset'
import { getQualityReport, computeQuality } from '@/api/quality'

const route = useRoute()
const router = useRouter()

const datasets = ref([])
const selectedDatasetId = ref(null)
const versions = ref([])
const currentVersionId = ref(null)
const selectedVersionId = ref(null)

const stats = ref(null)
const statsState = ref('idle')
const statsError = ref('')
const versionNotice = ref('')
const statsLoading = ref(false)
const initializing = ref(true)

const quality = ref(null)
const qualityLoading = ref(false)
const qualityStatus = ref('NOT_COLLECTED')
const qualityMessage = ref('')

const NUMERIC_TYPES = ['TINYINT', 'SMALLINT', 'MEDIUMINT', 'INT', 'INTEGER', 'BIGINT', 'NUMBER', 'DECIMAL', 'NUMERIC', 'FLOAT', 'DOUBLE', 'REAL']
const SUPPORTED_TYPES = [
  'TINYINT', 'SMALLINT', 'MEDIUMINT', 'INT', 'INTEGER', 'BIGINT', 'NUMBER', 'DECIMAL', 'NUMERIC', 'FLOAT', 'DOUBLE', 'REAL',
  'VARCHAR', 'CHAR', 'TEXT', 'TINYTEXT', 'MEDIUMTEXT', 'LONGTEXT',
  'DATE', 'DATETIME', 'TIMESTAMP', 'TIME', 'YEAR',
  'BOOLEAN', 'BOOL', 'BIT', 'JSON',
  'BINARY', 'VARBINARY', 'BLOB', 'TINYBLOB', 'MEDIUMBLOB', 'LONGBLOB',
]

const isNumericType = (t) => NUMERIC_TYPES.includes(String(t || '').toUpperCase().split('(')[0].trim())

const isTypeSupported = (t) => {
  if (!t) return false
  const base = String(t).toUpperCase().split('(')[0].trim()
  return SUPPORTED_TYPES.includes(base)
}

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

const fmtInt = (v) => {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  return Number.isFinite(n) ? n.toLocaleString('zh-CN') : String(v)
}

const fmtPct = (v) => (v === null || v === undefined ? '未采集' : `${Number(v).toFixed(1)}%`)

const fmtCell = (v) => (v === null || v === undefined || v === '' ? '未采集' : String(v))

const rowCount = computed(() => toNum(stats.value?.rowCount) || toNum(quality.value?.rowCount) || 0)
const columns = computed(() => (Array.isArray(stats.value?.columns) ? stats.value.columns : []))
const qualityColumns = computed(() =>
  Array.isArray(quality.value?.typeMismatch?.columns) ? quality.value.typeMismatch.columns : []
)
const isCurrentVersion = computed(
  () => !selectedVersionId.value || !currentVersionId.value || selectedVersionId.value === currentVersionId.value
)
const qualityReady = computed(() => qualityStatus.value === 'DONE' && !!quality.value)
const qualityStatusText = computed(
  () => ({ NOT_COLLECTED: '未采集', COMPUTING: '计算中', FAILED: '失败' })[qualityStatus.value] || '未采集'
)
const qualityByAlias = computed(() => {
  const map = {}
  qualityColumns.value.forEach((item) => {
    if (item.columnAlias) map[item.columnAlias] = item
    if (item.columnName) map[item.columnName] = item
  })
  return map
})

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

const buildDist = (c) => {
  if (!isNumericType(c.dataType)) return null
  const min = toNum(c.min)
  const max = toNum(c.max)
  const q1 = toNum(c.q1)
  const q3 = toNum(c.q3)
  const median = toNum(c.median)
  if (min === null || max === null || q1 === null || q3 === null || max <= min) return null
  const pct = (v) => Math.min(100, Math.max(0, ((v - min) / (max - min)) * 100))
  return {
    min,
    max,
    q1,
    q3,
    median: median === null ? q1 : median,
    left: pct(q1),
    width: Math.max(2, pct(q3) - pct(q1)),
    medianLeft: pct(median === null ? q1 : median),
  }
}

const fieldRows = computed(() => {
  const base = columns.value.length
    ? columns.value
    : qualityColumns.value.map((q) => ({
        columnName: q.columnAlias || q.columnName,
        dataType: q.declaredType,
        nullCount: q.nullCount,
        nullRate: null,
        uniqueCount: q.distinctCount,
        min: null,
        max: null,
      }))
  return base.map((c) => {
    const q = qualityByAlias.value[c.columnName] || null
    const nullRate = parseRate(c.nullRate)
    const nullCount = toNum(c.nullCount)
    const uniqueCount = toNum(c.uniqueCount)
    const anomaly = hasAnomaly(c)
    const typeSupported = isTypeSupported(c.dataType)
    const mismatchCount = q ? Number(q.mismatchCount || 0) : null

    const tags = []
    if (nullRate !== null && nullRate >= 30) tags.push({ label: '高空值', type: 'danger' })
    else if (nullRate !== null && nullRate > 0) tags.push({ label: '含空值', type: 'warning' })
    if (anomaly) tags.push({ label: '异常值', type: 'warning' })
    if (mismatchCount !== null && mismatchCount > 0) tags.push({ label: '格式异常', type: 'danger' })
    if (uniqueCount !== null && uniqueCount <= 1) tags.push({ label: '常量列', type: 'info' })
    if (uniqueCount !== null && rowCount.value > 0 && uniqueCount >= rowCount.value) {
      tags.push({ label: '唯一键候选', type: 'success' })
    }
    if (q?.enumLike) tags.push({ label: '枚举候选', type: 'info' })
    if (!typeSupported) tags.push({ label: '类型不兼容', type: 'danger' })

    let suggestion = '暂无必须处理项'
    if (q?.suggestion) suggestion = q.suggestion
    else if (nullRate !== null && nullRate >= 30) suggestion = '先确认空值来源，再决定填充或删除（需人工确认）'
    else if (anomaly) suggestion = '人工核查 IQR 异常值，不自动修改'
    else if (uniqueCount !== null && uniqueCount <= 1) suggestion = '确认字段无分析价值后可考虑移除'
    else if (nullRate !== null && nullRate > 0) suggestion = '按业务口径评估是否填补空值'

    let impact = '0 行'
    if (mismatchCount !== null && mismatchCount > 0) impact = `${fmtInt(mismatchCount)} 行（格式异常）`
    else if (nullCount !== null && nullCount > 0) impact = `${fmtInt(nullCount)} 行（空值）`
    else if (anomaly) impact = '异常行数未采集'

    const example =
      c.min != null || c.max != null
        ? c.min === c.max
          ? fmtCell(c.min)
          : `${fmtCell(c.min)} ~ ${fmtCell(c.max)}`
        : q?.samples?.length
          ? String(q.samples[0])
          : ''

    return {
      ...c,
      nullRate,
      nullCount,
      uniqueCount,
      anomaly,
      typeSupported,
      mismatchCount,
      mismatchRate: q?.mismatchRate || '0%',
      mismatchSamplesText: q?.samples?.length ? q.samples.join('、') : '无抽样示例',
      expectedType: q?.expectedType || null,
      score: q?.score ?? null,
      tags,
      suggestion,
      impact,
      example,
      min: c.min ?? null,
      max: c.max ?? null,
      dist: buildDist(c),
    }
  })
})

const summary = computed(() => {
  const cols = columns.value
  const q = qualityReady.value ? quality.value : null
  if (!cols.length && !q) return null
  const rows = rowCount.value
  if (!cols.length && !q) return null

  let nullSum = 0
  let uniqueRatioSum = 0
  let uniqueRated = 0
  let anomalyCount = 0
  let highNullCount = 0

  cols.forEach((c) => {
    nullSum += toNum(c.nullCount) || 0
    const unique = toNum(c.uniqueCount)
    if (unique !== null && rows > 0) {
      uniqueRatioSum += Math.min(1, unique / rows)
      uniqueRated += 1
    }
    const rate = parseRate(c.nullRate)
    if (rate !== null && rate >= 30) highNullCount += 1
    if (hasAnomaly(c)) anomalyCount += 1
  })

  const cells = rows * cols.length
  const nullRate = cells > 0 ? (nullSum / cells) * 100 : null
  const uniqueness = uniqueRated ? (uniqueRatioSum / uniqueRated) * 100 : null

  let score = q?.summary?.score != null ? Math.round(Number(q.summary.score)) : null
  // 0 行数据集没有可评估样本：不允许用“无空值/无异常”倒推出 100 分
  if (score === null && cols.length && rows > 0) {
    const penalties = cols.map((c) => {
      const rate = parseRate(c.nullRate)
      const nullPenalty = Math.min(1, (rate === null ? 0 : rate) / 100) * 60
      const anomalyPenalty = hasAnomaly(c) ? 20 : 0
      return nullPenalty + anomalyPenalty
    })
    const avgPenalty = penalties.reduce((a, b) => a + b, 0) / penalties.length
    score = Math.round(Math.max(0, Math.min(100, 100 - avgPenalty)))
  }

  const mismatchColumns = q?.summary?.mismatchColumnCount ?? null
  const duplicateRate = q?.duplicate?.duplicateRateValue ?? null

  let priority = '低'
  if (rows === 0) {
    priority = '空数据集'
  } else if (
    (score !== null && score < 60) ||
    (nullRate !== null && nullRate >= 30) ||
    highNullCount >= 3 ||
    (duplicateRate !== null && duplicateRate >= 10) ||
    (mismatchColumns !== null && mismatchColumns >= 3)
  ) {
    priority = '高'
  } else if (
    (score !== null && score < 80) ||
    anomalyCount > 0 ||
    (nullRate !== null && nullRate >= 10) ||
    (mismatchColumns !== null && mismatchColumns > 0) ||
    (duplicateRate !== null && duplicateRate > 0)
  ) {
    priority = '中'
  }
  if (rows > 0 && score === null && mismatchColumns === null) priority = '未知'

  return { nullRate, uniqueness, anomalyCount, highNullCount, score, priority, mismatchColumns }
})

const priorityType = computed(() => {
  const p = summary.value?.priority
  if (p === '高') return 'danger'
  if (p === '中') return 'warning'
  if (p === '低') return 'success'
  return 'info'
})

const canDraft = computed(() => !!selectedDatasetId.value && fieldRows.value.length > 0)

const versionLabel = (v) => {
  const current = v.id === currentVersionId.value ? '（当前）' : ''
  const time = v.createdAt ? new Date(v.createdAt).toLocaleString('zh-CN', { hour12: false }) : '-'
  return `v${v.versionNo ?? '-'} · ${time}${current}`
}

const loadVersions = async () => {
  versions.value = []
  currentVersionId.value = null
  selectedVersionId.value = null
  if (!selectedDatasetId.value) return
  try {
    const res = await getVersions(selectedDatasetId.value)
    versions.value = res?.versions || []
    currentVersionId.value = res?.currentVersionId ?? null
    selectedVersionId.value = currentVersionId.value ?? versions.value[0]?.id ?? null
  } catch (e) {
    versions.value = []
  }
}

const loadQuality = async () => {
  if (!selectedDatasetId.value) return
  qualityLoading.value = true
  try {
    const params = selectedVersionId.value ? { versionId: selectedVersionId.value } : {}
    const res = await getQualityReport(selectedDatasetId.value, params)
    quality.value = res || null
    qualityStatus.value = res?.status || 'NOT_COLLECTED'
    qualityMessage.value = res?.message || ''
  } catch (e) {
    quality.value = null
    qualityStatus.value = 'FAILED'
    qualityMessage.value = e?.message || '质量统计读取失败'
  } finally {
    qualityLoading.value = false
  }
}

const computeQualityNow = async () => {
  if (!selectedDatasetId.value) return
  try {
    await ElMessageBox.confirm(
      '将对所选数据集与数据版本执行真实质量统计（数据库聚合），仅生成统计结果与清洗规则草稿，不会修改任何数据。',
      '开始质量统计',
      { confirmButtonText: '开始统计', cancelButtonText: '取消', type: 'info' }
    )
  } catch {
    return
  }
  qualityLoading.value = true
  try {
    const params = { versionId: selectedVersionId.value ?? undefined }
    const res = await computeQuality(selectedDatasetId.value, params)
    quality.value = res || null
    qualityStatus.value = res?.status || 'DONE'
    qualityMessage.value = ''
    ElMessage.success('质量统计完成')
    if (!isCurrentVersion.value) {
      stats.value = null
      statsState.value = 'history'
    }
  } catch (e) {
    qualityStatus.value = 'FAILED'
    qualityMessage.value = e?.message || '统计失败，请稍后重试'
    ElMessage.error(qualityMessage.value)
  } finally {
    qualityLoading.value = false
  }
}

const refreshStats = async () => {
  if (!selectedDatasetId.value) return
  await loadQuality()
  if (!isCurrentVersion.value) {
    stats.value = null
    statsState.value = 'history'
    statsError.value = ''
    return
  }
  statsLoading.value = true
  statsState.value = 'loading'
  statsError.value = ''
  try {
    stats.value = await getDatasetStatistics(selectedDatasetId.value)
    statsState.value = 'ok'
  } catch (e) {
    stats.value = null
    statsState.value = 'missing'
    statsError.value = e?.message || '统计尚未完成，请稍后刷新。'
  } finally {
    statsLoading.value = false
  }
}

const loadDatasets = async () => {
  try {
    const res = await getDatasetList({ page: 1, size: 100 })
    datasets.value = res?.records || []
    if (!datasets.value.length) return
    const fromQuery = route.query.datasetId ? Number(route.query.datasetId) : null
    const matched = datasets.value.find((d) => d.id === fromQuery)
    selectedDatasetId.value = matched ? matched.id : datasets.value[0].id
    await loadVersions()
    await refreshStats()
  } catch (e) {
    datasets.value = []
  } finally {
    initializing.value = false
  }
}

const onDatasetChange = async () => {
  versionNotice.value = ''
  stats.value = null
  statsState.value = 'idle'
  statsError.value = ''
  quality.value = null
  qualityStatus.value = 'NOT_COLLECTED'
  qualityMessage.value = ''
  await loadVersions()
  await refreshStats()
}

const onVersionChange = () => {
  versionNotice.value = ''
  stats.value = null
  quality.value = null
  qualityStatus.value = 'NOT_COLLECTED'
  qualityMessage.value = ''
  refreshStats()
}

const goCleanDraft = async () => {
  if (!canDraft.value) return
  try {
    await ElMessageBox.confirm(
      '将跳转到数据清洗页生成规则草稿；不会自动创建任务，也不会自动执行清洗。',
      '生成清洗规则草稿',
      { confirmButtonText: '前往清洗页', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  ElMessage.info('请在清洗页确认规则后再执行')
  router.push(`/clean/${selectedDatasetId.value}`)
}

onMounted(loadDatasets)
</script>

<style scoped>
.select-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.alert-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.uncollected {
  font-size: 18px;
  color: var(--dc-text-3);
}
.dist-wrap {
  min-width: 180px;
}
.dist-track {
  position: relative;
  height: 8px;
  border-radius: 999px;
  background: #eef1f7;
  margin: 6px 0;
}
.dist-iqr {
  position: absolute;
  top: 0;
  height: 100%;
  border-radius: 999px;
  background: var(--dc-brand);
  opacity: 0.45;
}
.dist-median {
  position: absolute;
  top: -3px;
  width: 2px;
  height: 14px;
  margin-left: -1px;
  background: var(--dc-text-2);
}
.dist-labels {
  font-size: 11px;
  color: var(--dc-text-3);
  white-space: nowrap;
}
.suggestion {
  display: inline-block;
  color: var(--dc-text-2);
  line-height: 1.5;
}
.ok-text {
  color: #16a34a;
}
.danger-text {
  color: #dc2626;
  font-weight: 600;
}
.footnote {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
}
</style>
