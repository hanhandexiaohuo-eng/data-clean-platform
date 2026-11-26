<template>
  <div class="analysis-page" v-loading="loading">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-left">
        <el-button @click="$router.back()" text>
          <el-icon><ArrowLeft /></el-icon>返回
        </el-button>
        <h2>可视化分析 - {{ dataset?.name }}</h2>
      </div>
      <div class="header-actions">
        <el-button @click="openDashboardList">
          <el-icon><Grid /></el-icon>看板
        </el-button>
        <el-button @click="showAiPanel = !showAiPanel" :type="showAiPanel ? 'primary' : 'default'">
          <el-icon><ChatDotRound /></el-icon>AI 洞察
        </el-button>
      </div>
    </div>

    <!-- 统计概览卡片 -->
    <el-row :gutter="16" class="stats-row">
      <el-col :span="6" v-for="stat in basicStats" :key="stat.label">
        <el-card class="stat-card" shadow="never">
          <div class="stat-label">{{ stat.label }}</div>
          <div class="stat-value">{{ stat.value }}</div>
        </el-card>
      </el-col>
      <el-col :span="24" style="margin-top: 16px">
        <el-alert type="info" :closable="false" show-icon>
          <template #title>
            数据预览：{{ dataset?.rowCount?.toLocaleString() }} 行 × {{ columns.length }} 列 &nbsp;|&nbsp;
            文件类型：{{ dataset?.fileType?.toUpperCase() }} &nbsp;|&nbsp;
            上次更新：{{ dataset?.updatedAt ? new Date(dataset.updatedAt).toLocaleString('zh-CN') : '-' }}
          </template>
        </el-alert>
      </el-col>
    </el-row>

    <el-row :gutter="20" class="main-row">
      <!-- 图表配置区 -->
      <el-col :span="showAiPanel ? 16 : 20">
        <!-- 智能推荐 -->
        <el-card shadow="never" class="config-card">
          <template #header>
            <div class="card-header">
              <span>推荐图表</span>
              <el-button type="primary" link size="small" @click="loadRecommendations">
                <el-icon><Refresh /></el-icon>重新推荐
              </el-button>
            </div>
          </template>
          <div v-if="recommendations.length" class="recommend-list">
            <div v-for="(item, index) in recommendations" :key="index" class="recommend-item"
                 @click="applyRecommendation(item)">
              <div class="recommend-title">
                <el-tag size="small" :type="confidenceType(item.confidence)">{{ confidenceText(item.confidence) }}</el-tag>
                {{ item.title || item.chartType }}
              </div>
              <div class="recommend-reason">{{ item.reason }}</div>
            </div>
          </div>
          <el-empty v-else description="暂无推荐，可手动选择字段配置" :image-size="60" />
          <div v-if="recommendNotice" class="recommend-notice">{{ recommendNotice }}</div>
        </el-card>

        <!-- 图表配置卡片 -->
        <el-card shadow="never" class="config-card">
          <template #header>
            <span>图表配置</span>
          </template>
          <el-form :inline="true" class="chart-config-form">
            <el-form-item label="图表类型">
              <el-radio-group v-model="chartConfig.chartType" @change="onConfigChange">
                <el-radio-button label="bar">柱状图</el-radio-button>
                <el-radio-button label="line">折线图</el-radio-button>
                <el-radio-button label="area">面积图</el-radio-button>
                <el-radio-button label="pie">饼图</el-radio-button>
                <el-radio-button label="scatter">散点图</el-radio-button>
                <el-radio-button label="heatmap">热力图</el-radio-button>
                <el-radio-button label="distribution">分布图</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="颜色主题">
              <el-select v-model="chartConfig.colorTheme" style="width: 110px" @change="renderChart">
                <el-option label="默认" value="default" />
                <el-option label="暖色" value="warm" />
                <el-option label="冷色" value="cool" />
                <el-option label="暗色" value="dark" />
                <el-option label="柔和" value="pastel" />
              </el-select>
            </el-form-item>
          </el-form>
          <el-form :inline="true" class="chart-config-form">
            <el-form-item label="X轴字段">
              <el-select v-model="chartConfig.xAxis" placeholder="选择X轴" style="width: 160px" @change="onConfigChange">
                <el-option v-for="col in xAxisColumns" :key="col.id" :label="col.columnName" :value="col.columnAlias" />
              </el-select>
            </el-form-item>
            <el-form-item label="Y轴字段" v-if="needsYAxis">
              <el-select v-model="chartConfig.yAxis" placeholder="选择Y轴" style="width: 160px" @change="onConfigChange">
                <el-option v-for="col in numericColumns" :key="col.id" :label="col.columnName" :value="col.columnAlias" />
              </el-select>
            </el-form-item>
            <el-form-item label="聚合方式" v-if="needsYAxis">
              <el-select v-model="chartConfig.aggregation" style="width: 120px" @change="onConfigChange">
                <el-option label="求和 SUM" value="SUM" />
                <el-option label="平均 AVG" value="AVG" />
                <el-option label="计数 COUNT" value="COUNT" />
                <el-option label="最大 MAX" value="MAX" />
                <el-option label="最小 MIN" value="MIN" />
              </el-select>
            </el-form-item>
            <el-form-item label="分组字段" v-if="needsYAxis">
              <el-select v-model="chartConfig.groupBy" placeholder="可选" clearable style="width: 150px" @change="onConfigChange">
                <el-option v-for="col in columns" :key="col.id" :label="col.columnName" :value="col.columnAlias" />
              </el-select>
            </el-form-item>
            <el-form-item label="图表标题">
              <el-input v-model="chartConfig.chartTitle" placeholder="可选自定义标题" style="width: 180px" clearable @change="renderChart" />
            </el-form-item>
            <el-form-item label="数据量">
              <el-input-number v-model="chartConfig.dataLimit" :min="10" :max="2000" :step="50" style="width: 130px" @change="onConfigChange" />
            </el-form-item>
          </el-form>
          <el-form :inline="true" class="chart-config-form">
            <el-form-item label="时间粒度" v-if="isDateAxis">
              <el-select v-model="chartConfig.timeGranularity" style="width: 110px" @change="onConfigChange">
                <el-option label="自动" value="AUTO" />
                <el-option label="按日" value="DAY" />
                <el-option label="按周" value="WEEK" />
                <el-option label="按月" value="MONTH" />
                <el-option label="按年" value="YEAR" />
              </el-select>
            </el-form-item>
            <el-form-item label="Top N" v-if="supportsTopN">
              <el-input-number v-model="chartConfig.topN" :min="1" :max="500" style="width: 120px" @change="onConfigChange" />
            </el-form-item>
            <el-form-item label="分桶数" v-if="chartConfig.chartType === 'distribution'">
              <el-input-number v-model="chartConfig.bucketCount" :min="5" :max="50" style="width: 120px" @change="onConfigChange" />
            </el-form-item>
            <el-form-item label="排序">
              <el-select v-model="chartConfig.sortField" style="width: 90px" @change="onConfigChange">
                <el-option label="不排序" value="" />
                <el-option label="X" value="x" />
                <el-option label="Y" value="y" />
              </el-select>
              <el-select v-model="chartConfig.sortDirection" style="width: 90px; margin-left: 6px"
                         :disabled="!chartConfig.sortField" @change="onConfigChange">
                <el-option label="升序" value="ASC" />
                <el-option label="降序" value="DESC" />
              </el-select>
            </el-form-item>
          </el-form>
          <div class="chart-actions">
            <el-button type="primary" @click="generateChartData" :loading="chartLoading">
              <el-icon><Refresh /></el-icon>生成图表
            </el-button>
            <el-button @click="openSaveDialog">
              <el-icon><Star /></el-icon>{{ currentSavedId ? '更新保存' : '保存图表' }}
            </el-button>
            <el-button @click="exportChart" :loading="pngExporting" :disabled="!chartInstance">
              <el-icon><Picture /></el-icon>导出图片
            </el-button>
            <el-button @click="exportCSV" :loading="csvExporting" :disabled="!chartData?.rawData?.length">
              <el-icon><Download /></el-icon>导出CSV
            </el-button>
          </div>
        </el-card>

        <!-- 图表展示区 -->
        <el-card shadow="never" class="chart-card" v-if="chartData">
          <template #header>
            <div class="chart-header">
              <span>图表预览</span>
              <div class="chart-stats" v-if="chartData?.statistics">
                <el-tag size="small" type="info">均值: {{ formatNumber(chartData.statistics.mean) }}</el-tag>
                <el-tag size="small" type="info">最大: {{ formatNumber(chartData.statistics.max) }}</el-tag>
                <el-tag size="small" type="info">最小: {{ formatNumber(chartData.statistics.min) }}</el-tag>
              </div>
            </div>
          </template>

          <!-- 数据解释：范围、聚合、抽样/截断、版本 -->
          <div class="chart-explain">
            <el-tag size="small" type="info">数据集：{{ dataset?.name }}</el-tag>
            <el-tag size="small" type="info" v-if="chartData.aggregation">聚合：{{ chartData.aggregation }}</el-tag>
            <el-tag size="small" type="info" v-if="chartData.rowCount !== null && chartData.rowCount !== undefined">
              扫描行数：{{ Number(chartData.rowCount).toLocaleString() }}
            </el-tag>
            <el-tag size="small" type="info" v-if="chartData.dataVersionNo">数据版本：V{{ chartData.dataVersionNo }}</el-tag>
            <el-tag size="small" type="warning" v-if="chartData.truncated">结果已截断</el-tag>
            <el-tag size="small" type="warning" v-if="chartData.sampled">已抽样（{{ chartData.sampleSize }} 点）</el-tag>
            <el-tag size="small" type="success" v-if="chartData.generatedAt">
              生成于 {{ new Date(chartData.generatedAt).toLocaleTimeString('zh-CN') }}
            </el-tag>
          </div>
          <el-alert v-if="chartData.notice" type="warning" :title="chartData.notice" :closable="false" show-icon
                    style="margin-bottom: 8px" />

          <el-empty v-if="chartData.empty" description="当前配置无可用数据，请调整字段或过滤条件" :image-size="90" />
          <div v-else ref="chartRef" class="chart-container"></div>
        </el-card>

        <!-- 已保存图表 -->
        <el-card shadow="never" class="config-card">
          <template #header>
            <div class="card-header">
              <span>已保存图表</span>
              <el-button type="primary" link size="small" @click="loadSavedCharts">
                <el-icon><Refresh /></el-icon>刷新
              </el-button>
            </div>
          </template>
          <el-table :data="savedCharts" size="small" border empty-text="暂无保存的图表">
            <el-table-column prop="title" label="标题" min-width="140" />
            <el-table-column prop="chartType" label="类型" width="100" />
            <el-table-column label="数据版本" width="120">
              <template #default="{ row }">
                V{{ row.savedVersionNo || '-' }} / 当前 V{{ row.currentVersionNo || '-' }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-tag v-if="row.stale" size="small" type="warning">数据已更新</el-tag>
                <el-tag v-else size="small" type="success">最新</el-tag>
                <el-tag v-if="row.missingFields?.length" size="small" type="danger" style="margin-left: 4px">
                  字段失效 {{ row.missingFields.length }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="230">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="loadSavedChart(row.id)">载入</el-button>
                <el-button link type="primary" size="small" @click="generateSaved(row)">生成</el-button>
                <el-button link type="danger" size="small" @click="removeSavedChart(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- 统计详情 -->
        <el-card shadow="never" class="detail-stats-card" v-loading="statsLoading">
          <template #header>
            <div class="stats-header">
              <span>字段统计详情</span>
              <el-button type="primary" link size="small" @click="loadStatistics">
                <el-icon><Refresh /></el-icon>刷新统计
              </el-button>
            </div>
          </template>
          <el-table :data="statistics?.columns || []" border stripe size="small" max-height="400">
            <el-table-column prop="columnName" label="字段名" min-width="120" fixed />
            <el-table-column prop="dataType" label="类型" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="row.dataType?.includes('INT') || row.dataType?.includes('DECIMAL') ? 'warning' : 'info'">
                  {{ row.dataType }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="nullCount" label="空值数" width="90" sortable />
            <el-table-column label="空值率" width="90" sortable>
              <template #default="{ row }">{{ row.nullRate || '-' }}</template>
            </el-table-column>
            <el-table-column prop="uniqueCount" label="唯一值" width="90" sortable />
            <el-table-column prop="avg_val" label="均值" width="110" sortable v-if="hasNumericStats">
              <template #default="{ row }">{{ formatNumber(row.avg_val) }}</template>
            </el-table-column>
            <el-table-column prop="min_val" label="最小值" width="110" v-if="hasNumericStats">
              <template #default="{ row }">{{ formatNumber(row.min_val) }}</template>
            </el-table-column>
            <el-table-column prop="max_val" label="最大值" width="110" v-if="hasNumericStats">
              <template #default="{ row }">{{ formatNumber(row.max_val) }}</template>
            </el-table-column>
            <el-table-column prop="std_val" label="标准差" width="110" v-if="hasNumericStats">
              <template #default="{ row }">{{ formatNumber(row.std_val) }}</template>
            </el-table-column>
            <el-table-column prop="q1" label="Q1" width="100" v-if="hasNumericStats">
              <template #default="{ row }">{{ formatNumber(row.q1) }}</template>
            </el-table-column>
            <el-table-column prop="median" label="中位数" width="100" v-if="hasNumericStats">
              <template #default="{ row }">{{ formatNumber(row.median) }}</template>
            </el-table-column>
            <el-table-column prop="q3" label="Q3" width="100" v-if="hasNumericStats">
              <template #default="{ row }">{{ formatNumber(row.q3) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!statistics?.columns?.length" description="暂无统计信息" :image-size="80" />
        </el-card>
      </el-col>

      <!-- AI 洞察面板 -->
      <el-col :span="8" v-show="showAiPanel">
        <el-card class="ai-panel" shadow="never">
          <template #header>
            <div class="ai-panel-header">
              <el-icon color="#667eea"><ChatDotRound /></el-icon>
              <span>AI 图表洞察</span>
            </div>
          </template>
          <div class="ai-panel-content markdown-body" v-if="aiContent" v-html="renderedAiContent"></div>
          <el-skeleton :rows="6" animated v-else />
          <div class="ai-panel-footer">
            <el-button type="primary" link size="small" @click="generateAiInsight" :loading="aiLoading" :disabled="!aiAvailable">
              <el-icon><Refresh /></el-icon>重新分析
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 保存图表 -->
    <el-dialog v-model="saveDialogVisible" title="保存图表" width="420px">
      <el-form label-width="80px">
        <el-form-item label="标题">
          <el-input v-model="saveTitle" placeholder="请输入图表标题" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="数据集">
          <span>{{ dataset?.name }}</span>
        </el-form-item>
        <el-form-item label="当前版本">
          <span>V{{ chartData?.dataVersionNo || '-' }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saveLoading" @click="saveChart">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { renderMarkdown } from '@/utils/markdown'
import { createChart } from '@/utils/echarts'
import { getDatasetById, getColumns } from '@/api/dataset'
import {
  generateChart,
  getStatistics,
  recommendCharts,
  listChartViews,
  getChartView,
  createChartView,
  updateChartView,
  deleteChartView,
  generateSavedChart,
  exportSavedChartCsv,
} from '@/api/analysis'
import { generatePublic } from '@/api/ai'
import { appMode } from '@/stores/appMode'

const COLOR_PALETTES = {
  default: ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de', '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc'],
  warm: ['#ff6b6b', '#feca57', '#ff9ff3', '#54a0ff', '#5f27cd', '#01a3a4', '#f368e0', '#ff6348', '#7bed9f'],
  cool: ['#0984e3', '#00cec9', '#6c5ce7', '#00b894', '#74b9ff', '#a29bfe', '#55efc4', '#81ecec', '#dfe6e9'],
  dark: ['#dd6b66', '#759aa0', '#e69d87', '#8dc1a9', '#ea7e53', '#eedd78', '#73a373', '#73b9bc', '#b6a2de'],
  pastel: ['#fab1a0', '#a29bfe', '#fd79a8', '#fdcb6e', '#00b894', '#e17055', '#0984e3', '#6c5ce7', '#dfe6e9'],
}

const route = useRoute()
const router = useRouter()
const datasetId = route.params.id

const loading = ref(true)
const chartLoading = ref(false)
const pngExporting = ref(false)
const csvExporting = ref(false)
const showAiPanel = ref(false)
const aiLoading = ref(false)
const aiContent = ref('')
const renderedAiContent = computed(() => renderMarkdown(aiContent.value))

const dataset = ref(null)
const aiAvailable = computed(() => !appMode.publicMode || appMode.config?.aiEnabled === true)
const columns = ref([])
const numericColumns = computed(() => columns.value.filter(c =>
  c.dataType?.includes('INT') || c.dataType?.includes('DECIMAL') ||
  c.dataType?.includes('DOUBLE') || c.dataType?.includes('FLOAT')
))
const dateColumns = computed(() => columns.value.filter(c =>
  c.dataType?.includes('DATETIME') || c.dataType?.includes('DATE') ||
  c.columnName?.toLowerCase().includes('date') || c.columnName?.toLowerCase().includes('time') ||
  c.columnName?.toLowerCase().includes('day')
))
const statistics = ref(null)
const chartData = ref(null)
const chartRef = ref(null)
const statsLoading = ref(false)
let chartHandle = null
let chartInstance = null

const recommendations = ref([])
const recommendNotice = ref('')
const savedCharts = ref([])
const currentSavedId = ref(null)
const saveDialogVisible = ref(false)
const saveTitle = ref('')
const saveLoading = ref(false)

const chartConfig = ref({
  chartType: 'bar',
  xAxis: '',
  yAxis: '',
  groupBy: '',
  aggregation: 'SUM',
  chartTitle: '',
  colorTheme: 'default',
  dataLimit: 500,
  timeGranularity: 'AUTO',
  topN: 50,
  bucketCount: 20,
  sortField: '',
  sortDirection: 'DESC',
})

const xAxisColumns = computed(() => (chartConfig.value.chartType === 'distribution' ? numericColumns.value : columns.value))
const isDateAxis = computed(() => dateColumns.value.some(c => c.columnAlias === chartConfig.value.xAxis))
const supportsTopN = computed(() => ['bar', 'pie', 'area'].includes(chartConfig.value.chartType))

const basicStats = computed(() => {
  if (!statistics.value) return []
  return [
    { label: '总行数', value: (statistics.value.rowCount || 0).toLocaleString() },
    { label: '总列数', value: statistics.value.columnCount || 0 },
    { label: '数值列数', value: numericColumns.value.length || 0 },
    { label: '日期列数', value: dateColumns.value.length || 0 },
    {
      label: '数据完整度',
      value: (() => {
        if (!statistics.value.columns?.length || !statistics.value.rowCount) return '100%'
        const totalCells = statistics.value.rowCount * statistics.value.columns.length
        const nullCells = statistics.value.columns.reduce((sum, c) => sum + (c.nullCount || 0), 0)
        return ((1 - nullCells / totalCells) * 100).toFixed(1) + '%'
      })(),
    },
  ]
})

const hasNumericStats = computed(() => {
  return statistics.value?.columns?.some(c => c.avg_val !== undefined || c.min_val !== undefined)
})

const needsYAxis = computed(() => !['pie', 'distribution'].includes(chartConfig.value.chartType))

const confidenceText = (level) => ({ high: '强烈推荐', medium: '推荐', low: '可选' }[level] || '可选')
const confidenceType = (level) => ({ high: 'success', medium: 'warning', low: 'info' }[level] || 'info')

const buildRequestConfig = () => ({
  datasetId: parseInt(datasetId),
  // distribution 是模式而非图表类型：后端以柱状图承载分桶结果
  chartType: chartConfig.value.chartType === 'distribution' ? 'bar' : chartConfig.value.chartType,
  xAxis: chartConfig.value.xAxis,
  yAxis: needsYAxis.value ? chartConfig.value.yAxis : '',
  groupBy: chartConfig.value.groupBy,
  aggregation: chartConfig.value.aggregation,
  limit: chartConfig.value.dataLimit,
  title: chartConfig.value.chartTitle,
  colorTheme: chartConfig.value.colorTheme,
  mode: chartConfig.value.chartType === 'distribution' ? 'distribution' : 'normal',
  timeGranularity: chartConfig.value.timeGranularity,
  topN: chartConfig.value.topN,
  bucketCount: chartConfig.value.bucketCount,
  sort: chartConfig.value.sortField
    ? { field: chartConfig.value.sortField, direction: chartConfig.value.sortDirection }
    : null,
})

const onConfigChange = () => generateChartData()

const generateChartData = async () => {
  if (!chartConfig.value.xAxis) {
    ElMessage.warning('请选择 X 轴字段')
    return
  }
  if (needsYAxis.value && !chartConfig.value.yAxis) {
    ElMessage.warning('请选择 Y 轴字段')
    return
  }
  if (chartConfig.value.chartType === 'distribution') {
    const numeric = numericColumns.value.some(c => c.columnAlias === chartConfig.value.xAxis)
    if (!numeric) {
      ElMessage.warning('分布图的 X 轴必须是数值字段')
      return
    }
  }

  chartLoading.value = true
  try {
    chartData.value = await generateChart(buildRequestConfig())
    await nextTick()
    renderChart()
  } catch (e) {
    ElMessage.error('生成图表失败: ' + (e.message || '未知错误'))
  } finally {
    chartLoading.value = false
  }
}

const renderChart = () => {
  if (!chartRef.value || !chartData.value || chartData.value.empty) return
  if (chartHandle) {
    chartHandle.dispose()
    chartHandle = null
  }
  chartHandle = createChart(chartRef.value)
  chartInstance = chartHandle.getInstance()
  chartHandle.setOption(buildChartOption(chartConfig.value.chartType))
}

const getColors = () => COLOR_PALETTES[chartConfig.value.colorTheme] || COLOR_PALETTES.default

const buildChartOption = (type) => {
  const series = (chartData.value.series || []).map((s, i) => {
    const colors = getColors()
    return { ...s, itemStyle: { color: colors[i % colors.length] } }
  })
  const xData = chartData.value.xAxisData || []
  const colors = getColors()
  const title = chartConfig.value.chartTitle || `${chartConfig.value.yAxis || '值'} (${chartConfig.value.aggregation})`

  const baseOption = {
    title: { text: title, left: 'center', top: 5, textStyle: { fontSize: 16, color: '#1a1a2e' } },
    color: colors,
    tooltip: { trigger: type === 'pie' ? 'item' : 'axis', axisPointer: { type: 'shadow' } },
    toolbox: {
      feature: {
        saveAsImage: { title: '保存图片' },
        dataView: { title: '数据视图', readOnly: true, lang: ['数据视图', '关闭', '刷新'] },
      },
      right: 10,
    },
    legend: { bottom: 5, type: 'scroll' },
    grid: { left: '3%', right: '4%', bottom: '15%', top: type === 'heatmap' ? '15%' : '65', containLabel: true },
  }

  switch (type) {
    case 'bar':
      return {
        ...baseOption,
        xAxis: { type: 'category', data: xData, axisLabel: { rotate: xData.length > 10 ? 45 : 0, interval: 0, overflow: 'truncate', width: 120 } },
        yAxis: { type: 'value' },
        series: series.map(s => ({ ...s, type: 'bar', barMaxWidth: 50, emphasis: { focus: 'series' } })),
        dataZoom: xData.length > 30 ? [{ type: 'slider', start: 0, end: 50 }] : undefined,
      }

    case 'line':
    case 'area':
      return {
        ...baseOption,
        xAxis: { type: 'category', data: xData, boundaryGap: false, axisLabel: { rotate: xData.length > 10 ? 45 : 0 } },
        yAxis: { type: 'value' },
        series: series.map(s => ({
          ...s,
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 6,
          areaStyle: type === 'area' ? { opacity: 0.25 } : { opacity: 0.08 },
        })),
        dataZoom: xData.length > 30 ? [{ type: 'slider', start: 0, end: 50 }] : undefined,
      }

    case 'pie':
      return {
        ...baseOption,
        series: series.map(s => ({
          ...s,
          type: 'pie',
          radius: ['40%', '70%'],
          center: ['50%', '50%'],
          label: { formatter: '{b}: {d}%', fontSize: 12 },
          emphasis: { label: { fontSize: 18, fontWeight: 'bold' } },
        })),
      }

    case 'distribution':
      return {
        ...baseOption,
        xAxis: { type: 'category', data: xData, axisLabel: { rotate: xData.length > 8 ? 30 : 0 } },
        yAxis: { type: 'value', name: '频数' },
        series: series.map(s => ({ ...s, type: 'bar', barMaxWidth: 40 })),
      }

    case 'scatter':
      return {
        ...baseOption,
        xAxis: { type: 'value', name: chartConfig.value.xAxis, nameLocation: 'center', nameGap: 30 },
        yAxis: { type: 'value', name: chartConfig.value.yAxis, nameLocation: 'center', nameGap: 35 },
        series: series.map(s => ({ ...s, type: 'scatter', symbolSize: 10 })),
      }

    case 'heatmap': {
      const yAxisData = chartData.value.statistics?.yAxisData || []
      const heatValues = series.flatMap(s => (s.data || []).map(d => Array.isArray(d) ? (d[2] || 0) : 0))
      const maxVal = heatValues.length > 0 ? Math.max(...heatValues) : 100
      return {
        ...baseOption,
        xAxis: { type: 'category', data: xData, axisLabel: { rotate: xData.length > 10 ? 45 : 0 } },
        yAxis: { type: 'category', data: yAxisData },
        visualMap: { min: 0, max: maxVal, calculable: true, orient: 'horizontal', left: 'center', bottom: 5 },
        series: series.map(s => ({ ...s, type: 'heatmap', label: { show: heatValues.length < 200 } })),
      }
    }

    default:
      return baseOption
  }
}

// 导出图片
const exportChart = () => {
  if (pngExporting.value) return
  if (!chartInstance) { ElMessage.warning('请先生成图表'); return }
  pngExporting.value = true
  try {
    const url = chartInstance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' })
    const a = document.createElement('a')
    a.href = url
    a.download = `chart_${chartConfig.value.chartType}_${Date.now()}.png`
    a.click()
    ElMessage.success('图片已下载')
  } finally {
    pngExporting.value = false
  }
}

// 导出 CSV：已保存图表走后端受限导出，否则回退到当前结果
const exportCSV = async () => {
  if (csvExporting.value) return
  csvExporting.value = true
  try {
    if (currentSavedId.value) {
      try {
        const response = await exportSavedChartCsv(currentSavedId.value)
        downloadBlob(response, `chart-${currentSavedId.value}.csv`)
        const truncated = response.headers?.['x-export-truncated'] === 'true'
        ElMessage.success(truncated ? 'CSV 已下载（结果已按上限截断）' : 'CSV 已下载')
        return
      } catch (e) {
        ElMessage.error('导出失败：' + (e.message || '未知错误'))
        return
      }
    }
    if (!chartData.value?.rawData?.length) { ElMessage.warning('无数据可导出'); return }
    const rawData = chartData.value.rawData
    const headers = Object.keys(rawData[0])
    const escapeFormula = (text) => (/^[=+\-@\t\r]/.test(text) ? `'${text}` : text)
    const csvContent = [
      headers.map(h => escapeFormula(h)).join(','),
      ...rawData.map(row => headers.map(h => {
        const val = row[h]
        if (val === null || val === undefined) return ''
        const str = escapeFormula(String(val))
        return str.includes(',') || str.includes('"') || str.includes('\n') ? `"${str.replace(/"/g, '""')}"` : str
      }).join(','))
    ].join('\n')
    const blob = new Blob(['\ufeff' + csvContent], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `data_${chartConfig.value.chartType}_${Date.now()}.csv`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('CSV 已下载')
  } finally {
    csvExporting.value = false
  }
}

const downloadBlob = (response, fallbackName) => {
  const disposition = response.headers?.['content-disposition'] || ''
  const match = /filename="?([^";]+)"?/.exec(disposition)
  const blob = new Blob([response.data], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = match ? match[1] : fallbackName
  a.click()
  URL.revokeObjectURL(url)
}

// 推荐
const loadRecommendations = async () => {
  try {
    const result = await recommendCharts(datasetId)
    recommendations.value = result?.items || []
    recommendNotice.value = result?.notice || ''
  } catch (e) {
    recommendations.value = []
  }
}

const applyRecommendation = async (item) => {
  const cfg = item.config || {}
  chartConfig.value.chartType = item.mode === 'distribution' ? 'distribution' : (item.chartType || 'bar')
  chartConfig.value.xAxis = item.xAxis || chartConfig.value.xAxis
  chartConfig.value.yAxis = item.yAxis || chartConfig.value.yAxis
  chartConfig.value.aggregation = item.aggregation || chartConfig.value.aggregation
  chartConfig.value.timeGranularity = item.timeGranularity || 'AUTO'
  chartConfig.value.topN = item.topN || chartConfig.value.topN
  chartConfig.value.bucketCount = cfg.bucketCount || chartConfig.value.bucketCount
  currentSavedId.value = null
  await nextTick()
  generateChartData()
}

// 保存/更新
const openSaveDialog = () => {
  if (!chartData.value) { ElMessage.warning('请先生成图表'); return }
  saveTitle.value = chartConfig.value.chartTitle || `${chartConfig.value.yAxis || chartConfig.value.xAxis} 图表`
  saveDialogVisible.value = true
}

const saveChart = async () => {
  if (!saveTitle.value.trim()) { ElMessage.warning('请输入标题'); return }
  saveLoading.value = true
  try {
    const payload = {
      datasetId: parseInt(datasetId),
      title: saveTitle.value.trim(),
      chartType: buildRequestConfig().chartType,
      chartConfig: { ...buildRequestConfig(), datasetId: undefined },
    }
    if (currentSavedId.value) {
      await updateChartView(currentSavedId.value, payload)
      ElMessage.success('图表已更新')
    } else {
      const created = await createChartView(payload)
      currentSavedId.value = created.id
      ElMessage.success('图表已保存')
    }
    chartConfig.value.chartTitle = saveTitle.value.trim()
    saveDialogVisible.value = false
    await loadSavedCharts()
  } catch (e) {
    ElMessage.error('保存失败：' + (e.message || '未知错误'))
  } finally {
    saveLoading.value = false
  }
}

const loadSavedCharts = async () => {
  try {
    savedCharts.value = await listChartViews(datasetId)
  } catch (e) {
    savedCharts.value = []
  }
}

const loadSavedChart = async (id) => {
  try {
    const detail = await getChartView(id)
    const cfg = detail.chartConfig || {}
    chartConfig.value.chartType = cfg.mode === 'distribution'
      ? 'distribution'
      : (detail.chartType || cfg.chartType || 'bar')
    chartConfig.value.xAxis = cfg.xAxis || ''
    chartConfig.value.yAxis = cfg.yAxis || ''
    chartConfig.value.groupBy = cfg.groupBy || ''
    chartConfig.value.aggregation = cfg.aggregation || 'SUM'
    chartConfig.value.dataLimit = cfg.limit || 500
    chartConfig.value.colorTheme = cfg.colorTheme || 'default'
    chartConfig.value.chartTitle = detail.title || ''
    chartConfig.value.timeGranularity = cfg.timeGranularity || 'AUTO'
    chartConfig.value.topN = cfg.topN || 50
    chartConfig.value.bucketCount = cfg.bucketCount || 20
    chartConfig.value.sortField = cfg.sort?.field || ''
    chartConfig.value.sortDirection = cfg.sort?.direction || 'DESC'
    currentSavedId.value = id
    await generateSaved(detail)
  } catch (e) {
    ElMessage.error('载入图表失败：' + (e.message || '未知错误'))
  }
}

const generateSaved = async (row) => {
  try {
    chartData.value = await generateSavedChart(row.id)
    currentSavedId.value = row.id
    chartConfig.value.chartType = row.chartType || chartConfig.value.chartType
    chartConfig.value.chartTitle = row.title || chartConfig.value.chartTitle
    await nextTick()
    renderChart()
    ElMessage.success('已按保存配置生成')
  } catch (e) {
    const status = e?.response?.data?.data
    if (e?.response?.status === 409 && status) {
      if (status.missingFields?.length) {
        ElMessageBox.alert(
          `以下字段在当前数据版本中已不存在：${status.missingFields.join('、')}，请重新选择字段后再保存。`,
          '字段已失效',
          { type: 'warning' }
        )
        return
      }
      try {
        await ElMessageBox.confirm(
          `数据已更新（保存于 V${status.savedVersionNo}，当前 V${status.currentVersionNo}），是否按最新数据重新生成？`,
          '数据已更新，需要刷新',
          { type: 'warning', confirmButtonText: '刷新', cancelButtonText: '取消' }
        )
      } catch {
        return
      }
      try {
        chartData.value = await generateSavedChart(row.id, status.currentVersionId)
        await nextTick()
        renderChart()
        ElMessage.success('已按最新数据刷新')
        await loadSavedCharts()
      } catch (err) {
        ElMessage.error('刷新失败：' + (err.message || '未知错误'))
      }
      return
    }
    ElMessage.error('生成失败：' + (e.message || '未知错误'))
  }
}

const removeSavedChart = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除图表「${row.title}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteChartView(row.id)
    if (currentSavedId.value === row.id) currentSavedId.value = null
    ElMessage.success('已删除')
    await loadSavedCharts()
  } catch (e) {
    ElMessage.error('删除失败：' + (e.message || '未知错误'))
  }
}

const openDashboardList = () => {
  router.push('/dashboard')
}

// 加载更多统计详情
const loadStatistics = async () => {
  statsLoading.value = true
  try {
    statistics.value = await getStatistics(datasetId)
  } catch (e) {
    ElMessage.error('加载统计信息失败')
  } finally {
    statsLoading.value = false
  }
}

// AI 洞察（私有模式走 SSE，公开模式走同步 + 人机挑战）
const generateAiInsight = async () => {
  if (!aiAvailable.value) {
    ElMessage.warning('公开演示环境暂未开放 AI 分析')
    return
  }
  aiLoading.value = true
  aiContent.value = ''
  showAiPanel.value = true

  if (appMode.publicMode) {
    try {
      aiContent.value = await generatePublic({
        datasetId: Number(datasetId),
        templateKey: 'chart_insight',
        contextType: 'chart_analysis',
        extraContext: {
          chartType: chartConfig.value.chartType,
          xAxis: chartConfig.value.xAxis || '',
          yAxis: chartConfig.value.yAxis || '',
          aggregation: chartConfig.value.aggregation || '',
        },
      })
    } catch (e) {
      aiContent.value = 'AI 分析暂不可用，请稍后再试。'
    } finally {
      aiLoading.value = false
    }
    return
  }

  const params = new URLSearchParams({
    datasetId: datasetId,
    templateKey: 'chart_insight',
    contextType: 'chart_analysis',
  })
  const url = `/api/ai/generate/stream?${params.toString()}`

  const eventSource = new EventSource(url)
  let buffer = ''

  eventSource.addEventListener('message', (event) => {
    try {
      const data = JSON.parse(event.data)
      if (data.content) {
        buffer += data.content
        aiContent.value = buffer
      }
    } catch { /* skip non-JSON frames */ }
  })

  eventSource.addEventListener('error', () => {
    eventSource.close()
    aiLoading.value = false
    if (!aiContent.value) {
      aiContent.value = [
        '## 图表洞察',
        '',
        '当前图表展示了数据的关键分布特征，建议关注以下方面：',
        '',
        '1. **数据分布**：观察各数据点的分布是否均匀，是否存在明显的偏斜',
        '2. **异常值**：注意远离主体的数据点，它们可能代表特殊情况或数据质量问题',
        '3. **趋势**：分析数据的整体走势，关注上升或下降的变化趋势',
        '4. **对比关系**：对比不同类别之间的差异，找出显著的特征',
      ].join('\n')
    }
  })

  eventSource.addEventListener('complete', () => {
    eventSource.close()
    aiLoading.value = false
  })
}

const formatNumber = (val) => {
  if (val === undefined || val === null) return '-'
  const n = parseFloat(val)
  if (isNaN(n)) return '-'
  if (Math.abs(n) >= 1e8) return n.toExponential(2)
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

onMounted(async () => {
  try {
    dataset.value = await getDatasetById(datasetId)
    columns.value = await getColumns(datasetId)
    statistics.value = await getStatistics(datasetId)
  } catch (e) {
    ElMessage.error('加载数据失败: ' + (e.message || '未知错误'))
  }

  if (columns.value.length > 0) {
    chartConfig.value.xAxis = columns.value[0].columnAlias
  }
  if (numericColumns.value.length > 0) {
    chartConfig.value.yAxis = numericColumns.value[0].columnAlias
  }

  loading.value = false

  await Promise.all([loadRecommendations(), loadSavedCharts()])

  if (chartConfig.value.xAxis) {
    generateChartData()
  }
})

onUnmounted(() => {
  if (chartHandle) {
    chartHandle.dispose()
    chartHandle = null
    chartInstance = null
  }
})
</script>

<style scoped>
.analysis-page {
  padding: 8px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #e4e7ed;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-left h2 {
  font-size: 20px;
  font-weight: 600;
  color: #1a1a2e;
}

.stats-row {
  margin-bottom: 20px;
}

.stat-card {
  text-align: center;
  padding: 20px;
  border-radius: 12px;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: #1a1a2e;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.main-row {
  margin: 0 !important;
}

.config-card,
.chart-card,
.detail-stats-card {
  margin-bottom: 20px;
  border-radius: 12px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chart-config-form {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  align-items: center;
}

.chart-actions {
  display: flex;
  gap: 10px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #e4e7ed;
}

.chart-container {
  width: 100%;
  height: 520px;
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chart-stats {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.chart-explain {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.recommend-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.recommend-item {
  flex: 1 1 220px;
  min-width: 200px;
  padding: 10px 12px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  background: #fafbff;
}

.recommend-item:hover {
  border-color: #667eea;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.15);
}

.recommend-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #1a1a2e;
}

.recommend-reason {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
}

.recommend-notice {
  margin-top: 10px;
  font-size: 12px;
  color: #b88230;
}

.stats-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.ai-panel {
  border-radius: 12px;
  border: 1px solid #e0d4fc;
  background: linear-gradient(135deg, #faf7ff 0%, #f0f5ff 100%);
  position: sticky;
  top: 20px;
}

.ai-panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.ai-panel-content {
  max-height: 600px;
  overflow-y: auto;
  padding: 8px;
  min-height: 200px;
}

.ai-panel-footer {
  display: flex;
  justify-content: center;
  padding-top: 12px;
  border-top: 1px solid #e4e7ed;
}
</style>
