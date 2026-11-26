<template>
  <div class="dc-page">
    <div class="dc-page-head">
      <div>
        <h1>AI 助手</h1>
        <p>基于字段信息与聚合统计生成分析建议；所有输出仅供参考，需人工确认。</p>
      </div>
      <div class="dc-page-actions">
        <el-tag v-if="appMode.publicMode" size="large" effect="plain" :type="quotaTagType">
          AI 额度：{{ quotaText }}
        </el-tag>
        <el-button v-if="appMode.publicMode" :loading="quotaLoading" @click="loadQuota">
          <el-icon><Refresh /></el-icon>刷新额度
        </el-button>
        <el-button v-else @click="router.push('/ai-config')">
          <el-icon><Setting /></el-icon>AI 配置
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="appMode.publicMode && aiDisabledText"
      type="warning"
      :closable="false"
      show-icon
      :title="aiDisabledText"
      description="可浏览数据集与场景说明，生成按钮已禁用。"
    />

    <el-alert
      v-if="!appMode.publicMode"
      type="info"
      :closable="false"
      show-icon
      title="私有模式请在 AI 配置 / AI 治理中管理供应商与配额"
      description="此处不展示供应商、模型、密钥、Prompt 或成本信息。"
    >
      <template #default>
        <div class="private-actions">
          <span>私有模式请在 AI 配置 / AI 治理中管理供应商与配额。</span>
          <el-button size="small" @click="router.push('/ai-config')">AI 配置</el-button>
          <el-button size="small" @click="router.push('/ai-governance')">AI 治理</el-button>
        </div>
      </template>
    </el-alert>

    <div class="dc-card">
      <div class="dc-card-head">
        <div>
          <div class="dc-card-title">选择数据集</div>
          <div class="dc-card-sub">AI 仅使用字段名、类型与聚合统计摘要</div>
        </div>
        <el-button text type="primary" :disabled="!selectedDatasetId" @click="loadColumns">
          <el-icon><Refresh /></el-icon>刷新字段
        </el-button>
      </div>
      <el-select
        v-model="selectedDatasetId"
        placeholder="选择数据集"
        filterable
        style="width: 320px"
        :disabled="!datasets.length"
        @change="onDatasetChange"
      >
        <el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <div v-if="!datasets.length" class="empty-inline dc-muted">
        暂无可选数据集，请先
        <el-button link type="primary" @click="router.push('/datasets')">导入数据</el-button>
      </div>
    </div>

    <div class="dc-grid-3 scene-grid">
      <div
        v-for="scene in scenes"
        :key="scene.key"
        class="dc-card scene-card"
        :class="{ active: activeScene === scene.key }"
        @click="selectScene(scene)"
      >
        <div class="scene-head">
          <el-icon class="scene-icon"><component :is="scene.icon" /></el-icon>
          <el-tag v-if="scene.frontend" size="small" type="info" effect="plain">前端规则</el-tag>
          <el-tag v-else-if="scene.key === 'clean_suggestion'" size="small" type="warning" effect="plain">需人工确认</el-tag>
        </div>
        <div class="scene-title">{{ scene.title }}</div>
        <div class="scene-desc">{{ scene.desc }}</div>
      </div>
    </div>

    <div class="dc-card">
      <div class="dc-card-head">
        <div>
          <div class="dc-card-title">{{ activeSceneObj ? activeSceneObj.title : '分析结果' }}</div>
          <div class="dc-card-sub">{{ activeSceneObj ? activeSceneObj.desc : '' }}</div>
        </div>
        <el-button
          v-if="!activeSceneIsChart"
          type="primary"
          :loading="generating"
          :disabled="!canGenerate"
          @click="runActive"
        >
          <el-icon><MagicStick /></el-icon>{{ generating ? '生成中…' : '生成分析' }}
        </el-button>
      </div>

      <el-alert
        v-if="sceneNote"
        :type="activeScene === 'clean_suggestion' ? 'warning' : 'info'"
        :closable="false"
        show-icon
        :title="sceneNote"
        style="margin-bottom: 14px"
      />

      <el-skeleton v-if="generating" :rows="6" animated />

      <template v-else-if="activeSceneIsChart">
        <div v-if="!columns.length" class="dc-empty">
          <h4>字段信息尚未加载</h4>
          <p>图表推荐需要读取字段类型与唯一值信息。</p>
          <el-button type="primary" plain @click="loadColumns">加载字段</el-button>
        </div>
        <div v-else class="dc-list">
          <div v-for="(rec, i) in chartRecommendations" :key="i" class="dc-list-item">
            <el-tag size="small" type="info" effect="plain">{{ rec.label }}</el-tag>
            <div class="dc-list-main">
              <div class="dc-list-title">{{ rec.title }}</div>
              <div class="dc-list-meta">{{ rec.reason }}</div>
            </div>
          </div>
          <div v-if="!chartRecommendations.length" class="dc-empty">
            <h4>暂无推荐</h4>
            <p>字段类型不足以生成稳定的图表建议。</p>
          </div>
        </div>
      </template>

      <template v-else>
        <div v-if="aiError" class="ai-error">{{ aiError }}</div>
        <div v-else-if="result" class="ai-result">{{ result }}</div>
        <div v-else class="dc-empty">
          <h4>选择场景后生成</h4>
          <p>AI 只读取字段与聚合统计摘要，不会获取原始全量数据。</p>
        </div>

        <div v-if="activeScene === 'clean_suggestion' && result" class="draft-bar">
          <span class="dc-muted">清洗建议仅为草稿，需人工确认规则后才能执行。</span>
          <el-button type="warning" @click="toCleanDraft">
            <el-icon><Brush /></el-icon>转为可编辑清洗草稿
          </el-button>
        </div>
      </template>
    </div>

    <div class="dc-card boundary-card">
      <div class="dc-card-title">AI 使用边界</div>
      <ul class="boundary-list">
        <li>仅接收字段名、类型与聚合统计摘要，不接收原始全量数据。</li>
        <li>不会自动执行清洗、SQL、导出、删除或配置修改。</li>
        <li>输出可能不准确，请结合数据实际情况人工复核后再行动。</li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDatasetList, getColumns } from '@/api/dataset'
import { generate, generatePublic, getPublicAiQuota } from '@/api/ai'
import { appMode } from '@/stores/appMode'

const route = useRoute()
const router = useRouter()

const scenes = [
  {
    key: 'dataset_summary',
    title: '数据集摘要',
    desc: '汇总字段构成、缺失情况与数值分布',
    templateKey: 'dataset_summary',
    contextType: 'dataset_summary',
    icon: 'DataAnalysis',
  },
  {
    key: 'field_relation',
    title: '字段关系说明',
    desc: '解释字段之间可能的关联与组合方式',
    templateKey: 'field_relation',
    contextType: 'field_relation',
    icon: 'Connection',
  },
  {
    key: 'outlier_analysis',
    title: '异常值解释',
    desc: '结合分位数与极值说明异常字段的可能原因',
    templateKey: 'outlier_analysis',
    contextType: 'outlier_analysis',
    icon: 'Warning',
  },
  {
    key: 'chart_insight',
    title: '图表洞察',
    desc: '基于统计分布给出图表解读与观察建议',
    templateKey: 'chart_insight',
    contextType: 'chart_insight',
    icon: 'PieChart',
  },
  {
    key: 'clean_suggestion',
    title: '清洗建议草稿',
    desc: '生成清洗方向建议，结果需人工确认',
    templateKey: 'clean_suggestion',
    contextType: 'clean_suggestion',
    icon: 'MagicStick',
  },
  {
    key: 'chart_recommend',
    title: '图表推荐',
    desc: '前端按字段类型规则推荐，不调用 AI',
    frontend: true,
    icon: 'Histogram',
  },
]

const datasets = ref([])
const selectedDatasetId = ref(null)
const columns = ref([])
const activeScene = ref('dataset_summary')
const generating = ref(false)
const result = ref('')
const aiError = ref('')

const quota = ref(null)
const quotaError = ref(false)
const quotaLoading = ref(false)
const loadingDatasets = ref(false)

const NUMERIC_TYPES = ['TINYINT', 'SMALLINT', 'MEDIUMINT', 'INT', 'INTEGER', 'BIGINT', 'NUMBER', 'DECIMAL', 'NUMERIC', 'FLOAT', 'DOUBLE', 'REAL']

const isNumericType = (t) => NUMERIC_TYPES.includes(String(t || '').toUpperCase().split('(')[0].trim())
const isTemporalType = (t) => /^(DATE|DATETIME|TIMESTAMP|TIME|YEAR)/i.test(String(t || '').trim())

const activeSceneObj = computed(() => scenes.find((s) => s.key === activeScene.value) || scenes[0])
const activeSceneIsChart = computed(() => activeSceneObj.value?.frontend === true)

const effectiveTemplateKey = computed(() => {
  const scene = activeSceneObj.value
  if (!scene) return 'dataset_summary'
  if (appMode.publicMode && scene.key === 'clean_suggestion') return 'dataset_summary'
  return scene.templateKey || 'dataset_summary'
})

const sceneNote = computed(() => {
  const scene = activeSceneObj.value
  if (!scene) return ''
  if (scene.frontend) return '以下推荐由前端规则生成，不调用 AI。'
  if (scene.key === 'clean_suggestion') {
    return appMode.publicMode
      ? '公开模式没有独立清洗模板，将基于数据集摘要生成建议；需人工确认后才能转为清洗草稿。'
      : '清洗建议仅为草稿，需人工确认后才能转为可编辑规则。'
  }
  return ''
})

const aiEnabled = computed(() => appMode.publicMode ? appMode.config?.aiEnabled === true : true)

const quotaRemaining = computed(() => {
  if (!appMode.publicMode) return null
  if (quotaError.value) return null
  if (quota.value?.enabled === false) return 0
  return typeof quota.value?.remaining === 'number' ? quota.value.remaining : null
})

const aiDisabledText = computed(() => {
  if (!appMode.publicMode) return ''
  if (!aiEnabled.value || quota.value?.enabled === false) return '本工作区未开放 AI 分析'
  if (quotaRemaining.value === 0) return '当前会话 AI 额度已用完，请明天再试'
  return ''
})

const quotaText = computed(() => {
  if (!appMode.publicMode) return '私有模式'
  if (!appMode.config?.aiEnabled) return '未开放'
  if (quotaError.value) return '未开放'
  if (!quota.value) return quotaLoading.value ? '加载中…' : '未开放'
  if (quota.value.enabled === false) return '未开放'
  return `${fmtInt(quota.value.remaining)} / ${fmtInt(quota.value.limit)} 次`
})

const quotaTagType = computed(() => {
  if (!appMode.config?.aiEnabled || quotaError.value || quota.value?.enabled === false) return 'info'
  if (quotaRemaining.value === 0) return 'danger'
  if (typeof quotaRemaining.value === 'number' && quotaRemaining.value <= 2) return 'warning'
  return 'success'
})

const canGenerate = computed(() => {
  if (generating.value) return false
  if (!selectedDatasetId.value) return false
  if (activeSceneIsChart.value) return false
  if (appMode.publicMode && (!aiEnabled.value || quotaRemaining.value === 0)) return false
  return true
})

const chartRecommendations = computed(() => {
  const cols = columns.value || []
  if (!cols.length) return []
  const numeric = cols.filter((c) => isNumericType(c.dataType))
  const temporal = cols.filter((c) => isTemporalType(c.dataType))
  const categorical = cols.filter((c) => !isNumericType(c.dataType) && !isTemporalType(c.dataType))
  const recs = []

  if (temporal.length && numeric.length) {
    recs.push({
      label: '折线图',
      title: `${temporal[0].columnName} 趋势 · ${numeric[0].columnName}`,
      reason: '时间字段搭配数值字段，适合观察指标随时间的变化趋势。',
    })
  }
  if (categorical.length && numeric.length) {
    recs.push({
      label: '柱状图',
      title: `${categorical[0].columnName} 对比 · ${numeric[0].columnName}`,
      reason: '分类字段搭配数值字段，适合按类别比较指标大小。',
    })
  }
  if (numeric.length >= 2) {
    recs.push({
      label: '散点图',
      title: `${numeric[0].columnName} 与 ${numeric[1].columnName}`,
      reason: '两个数值字段可用于观察相关性与离群点。',
    })
  }
  if (numeric.length === 1) {
    recs.push({
      label: '分布图',
      title: `${numeric[0].columnName} 分布`,
      reason: '单个数值字段适合查看集中趋势与分布形态。',
    })
  }
  if (categorical.length && Number(categorical[0].uniqueCount) > 0 && Number(categorical[0].uniqueCount) <= 20) {
    recs.push({
      label: '饼图',
      title: `${categorical[0].columnName} 占比`,
      reason: '低基数分类字段适合展示各类别占比。',
    })
  }
  if (!recs.length) {
    recs.push({
      label: '明细表',
      title: '字段明细',
      reason: '当前字段类型不足以支撑稳定的图形化推荐。',
    })
  }
  return recs.slice(0, 4)
})

const fmtInt = (v) => {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  return Number.isFinite(n) ? n.toLocaleString('zh-CN') : String(v)
}

const describeError = (e) => {
  const status = e?.response?.status
  const msg = e?.message || ''
  if (status === 429) return '当前会话或网络今日 AI 调用次数已达上限，请明天再试。'
  if (status === 503) return 'AI 服务暂不可用或今日额度已用完，请稍后再试。'
  if (/timeout|ECONNABORTED|Network Error/i.test(msg)) return 'AI 分析超时或网络异常，请稍后重试。'
  if (status === 403) return msg || '当前工作区未开放该分析场景。'
  return msg || 'AI 分析失败，请稍后重试。'
}

const loadQuota = async () => {
  if (!appMode.publicMode) return
  quotaLoading.value = true
  quotaError.value = false
  try {
    quota.value = await getPublicAiQuota()
  } catch (e) {
    quota.value = null
    quotaError.value = true
  } finally {
    quotaLoading.value = false
  }
}

const loadColumns = async () => {
  if (!selectedDatasetId.value) {
    columns.value = []
    return
  }
  try {
    columns.value = (await getColumns(selectedDatasetId.value)) || []
  } catch (e) {
    columns.value = []
  }
}

const onDatasetChange = async () => {
  result.value = ''
  aiError.value = ''
  await loadColumns()
}

const selectScene = async (scene) => {
  activeScene.value = scene.key
  result.value = ''
  aiError.value = ''
  if (scene.frontend && !columns.value.length) {
    await loadColumns()
  }
}

const runActive = async () => {
  if (generating.value) return
  if (!selectedDatasetId.value) {
    ElMessage.warning('请先选择数据集')
    return
  }
  if (appMode.publicMode && aiDisabledText.value) {
    ElMessage.warning(aiDisabledText.value)
    return
  }

  generating.value = true
  result.value = ''
  aiError.value = ''
  try {
    const payload = {
      datasetId: Number(selectedDatasetId.value),
      templateKey: effectiveTemplateKey.value,
      contextType: activeSceneObj.value.contextType,
    }
    const text = appMode.publicMode ? await generatePublic(payload) : await generate(payload)
    result.value = typeof text === 'string' ? text : text === null || text === undefined ? '' : String(text)
    if (!result.value) result.value = 'AI 未返回内容，请稍后重试。'
    if (appMode.publicMode) await loadQuota()
  } catch (e) {
    aiError.value = describeError(e)
  } finally {
    generating.value = false
  }
}

const toCleanDraft = async () => {
  if (!selectedDatasetId.value) return
  try {
    await ElMessageBox.confirm(
      '将跳转到数据清洗页，可把 AI 建议转为可编辑规则；不会自动创建任务或执行清洗。',
      '转为清洗草稿',
      { confirmButtonText: '前往清洗页', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  router.push(`/clean/${selectedDatasetId.value}`)
}

const loadDatasets = async () => {
  loadingDatasets.value = true
  try {
    const res = await getDatasetList({ page: 1, size: 100 })
    datasets.value = res?.records || []
    if (!datasets.value.length) return
    const fromQuery = route.query.datasetId ? Number(route.query.datasetId) : null
    const matched = datasets.value.find((d) => d.id === fromQuery)
    selectedDatasetId.value = matched ? matched.id : datasets.value[0].id
    await loadColumns()
  } catch (e) {
    datasets.value = []
  } finally {
    loadingDatasets.value = false
  }
}

onMounted(async () => {
  await loadDatasets()
  await loadQuota()
})
</script>

<style scoped>
.private-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.empty-inline {
  margin-top: 8px;
  font-size: 13px;
}
.scene-grid {
  align-items: stretch;
}
.scene-card {
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.scene-card:hover {
  border-color: var(--dc-brand);
}
.scene-card.active {
  border-color: var(--dc-brand);
  box-shadow: 0 0 0 2px var(--dc-brand-soft);
}
.scene-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}
.scene-icon {
  font-size: 20px;
  color: var(--dc-brand);
}
.scene-title {
  font-size: 14px;
  font-weight: 650;
}
.scene-desc {
  margin-top: 6px;
  font-size: 12px;
  color: var(--dc-text-3);
  line-height: 1.6;
}
.ai-result {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--dc-text-2);
  max-height: 560px;
  overflow-y: auto;
}
.ai-error {
  padding: 14px 16px;
  border-radius: 9px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: var(--dc-danger);
  font-size: 13px;
  line-height: 1.7;
}
.draft-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--dc-border);
}
.boundary-card {
  border-style: dashed;
}
.boundary-list {
  margin: 10px 0 0;
  padding-left: 18px;
  font-size: 12.5px;
  color: var(--dc-text-3);
  line-height: 1.9;
}
</style>
