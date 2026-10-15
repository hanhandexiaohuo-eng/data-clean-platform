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
        <!-- 图表配置卡片 -->
        <el-card shadow="never" class="config-card">
          <template #header>
            <span>图表配置</span>
          </template>
          <el-form :inline="true" class="chart-config-form">
            <el-form-item label="图表类型">
              <el-radio-group v-model="chartConfig.chartType" @change="generateChartData">
                <el-radio-button label="bar">柱状图</el-radio-button>
                <el-radio-button label="line">折线图</el-radio-button>
                <el-radio-button label="pie">饼图</el-radio-button>
                <el-radio-button label="scatter">散点图</el-radio-button>
                <el-radio-button label="heatmap">热力图</el-radio-button>
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
              <el-select v-model="chartConfig.xAxis" placeholder="选择X轴" style="width: 160px" @change="generateChartData">
                <el-option v-for="col in columns" :key="col.id" :label="col.columnName" :value="col.columnAlias" />
              </el-select>
            </el-form-item>
            <el-form-item label="Y轴字段" v-if="needsYAxis">
              <el-select v-model="chartConfig.yAxis" placeholder="选择Y轴" style="width: 160px" @change="generateChartData">
                <el-option v-for="col in numericColumns" :key="col.id" :label="col.columnName" :value="col.columnAlias" />
              </el-select>
            </el-form-item>
            <el-form-item label="聚合方式">
              <el-select v-model="chartConfig.aggregation" style="width: 120px" @change="generateChartData">
                <el-option label="求和 SUM" value="SUM" />
                <el-option label="平均 AVG" value="AVG" />
                <el-option label="计数 COUNT" value="COUNT" />
                <el-option label="最大 MAX" value="MAX" />
                <el-option label="最小 MIN" value="MIN" />
              </el-select>
            </el-form-item>
            <el-form-item label="分组字段" v-if="needsYAxis">
              <el-select v-model="chartConfig.groupBy" placeholder="可选" clearable style="width: 150px" @change="generateChartData">
                <el-option v-for="col in columns" :key="col.id" :label="col.columnName" :value="col.columnAlias" />
              </el-select>
            </el-form-item>
            <el-form-item label="图表标题">
              <el-input v-model="chartConfig.chartTitle" placeholder="可选自定义标题" style="width: 180px" clearable @change="renderChart" />
            </el-form-item>
            <el-form-item label="数据量">
              <el-input-number v-model="chartConfig.dataLimit" :min="10" :max="5000" :step="50" style="width: 130px" @change="generateChartData" />
            </el-form-item>
          </el-form>
          <div class="chart-actions">
            <el-button type="primary" @click="generateChartData" :loading="chartLoading">
              <el-icon><Refresh /></el-icon>生成图表
            </el-button>
            <el-button @click="exportChart" :disabled="!chartInstance">
              <el-icon><Picture /></el-icon>导出图片
            </el-button>
            <el-button @click="exportCSV" :disabled="!chartData?.rawData?.length">
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
          <div ref="chartRef" class="chart-container"></div>
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
            <el-button type="primary" link size="small" @click="generateAiInsight" :loading="aiLoading">
              <el-icon><Refresh /></el-icon>重新分析
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import * as echarts from 'echarts'
import { getDatasetById, getColumns } from '@/api/dataset'
import { generateChart, getStatistics } from '@/api/analysis'

const COLOR_PALETTES = {
  default: ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de', '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc'],
  warm: ['#ff6b6b', '#feca57', '#ff9ff3', '#54a0ff', '#5f27cd', '#01a3a4', '#f368e0', '#ff6348', '#7bed9f'],
  cool: ['#0984e3', '#00cec9', '#6c5ce7', '#00b894', '#74b9ff', '#a29bfe', '#55efc4', '#81ecec', '#dfe6e9'],
  dark: ['#dd6b66', '#759aa0', '#e69d87', '#8dc1a9', '#ea7e53', '#eedd78', '#73a373', '#73b9bc', '#b6a2de'],
  pastel: ['#fab1a0', '#a29bfe', '#fd79a8', '#fdcb6e', '#00b894', '#e17055', '#0984e3', '#6c5ce7', '#dfe6e9'],
}

const route = useRoute()
const datasetId = route.params.id

const loading = ref(true)
const chartLoading = ref(false)
const showAiPanel = ref(false)
const aiLoading = ref(false)
const aiContent = ref('')
const renderedAiContent = computed(() => aiContent.value ? marked.parse(aiContent.value) : '')

const dataset = ref(null)
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
let chartInstance = null
let resizeHandler = null

const chartConfig = ref({
  chartType: 'bar',
  xAxis: '',
  yAxis: '',
  groupBy: '',
  aggregation: 'SUM',
  chartTitle: '',
  colorTheme: 'default',
  dataLimit: 100,
})

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

// 图表类型是否需要 Y 轴
const needsYAxis = computed(() => !['pie'].includes(chartConfig.value.chartType))

const generateChartData = async () => {
  if (!chartConfig.value.xAxis) {
    ElMessage.warning('请选择 X 轴字段')
    return
  }
  if (needsYAxis.value && !chartConfig.value.yAxis) {
    ElMessage.warning('请选择 Y 轴字段')
    return
  }

  chartLoading.value = true
  try {
    chartData.value = await generateChart({
      datasetId: parseInt(datasetId),
      chartType: chartConfig.value.chartType,
      xAxis: chartConfig.value.xAxis,
      yAxis: chartConfig.value.yAxis,
      groupBy: chartConfig.value.groupBy,
      aggregation: chartConfig.value.aggregation,
      limit: chartConfig.value.dataLimit,
    })

    await nextTick()
    renderChart()
  } catch (e) {
    ElMessage.error('生成图表失败: ' + (e.message || '未知错误'))
  } finally {
    chartLoading.value = false
  }
}

const renderChart = () => {
  if (!chartRef.value || !chartData.value) return

  if (chartInstance) chartInstance.dispose()
  chartInstance = echarts.init(chartRef.value)

  const option = buildChartOption(chartConfig.value.chartType)
  chartInstance.setOption(option, true)
}

const getColors = () => COLOR_PALETTES[chartConfig.value.colorTheme] || COLOR_PALETTES.default

const buildChartOption = (type) => {
  const series = (chartData.value.series || []).map((s, i) => {
    const colors = getColors()
    return {
      ...s,
      itemStyle: { color: colors[i % colors.length] },
    }
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
      return {
        ...baseOption,
        xAxis: { type: 'category', data: xData, boundaryGap: false, axisLabel: { rotate: xData.length > 10 ? 45 : 0 } },
        yAxis: { type: 'value' },
        series: series.map(s => ({ ...s, type: 'line', smooth: true, symbol: 'circle', symbolSize: 6, areaStyle: { opacity: 0.08 } })),
        dataZoom: xData.length > 30 ? [{ type: 'slider', start: 0, end: 50 }] : undefined,
      }

    case 'pie':
      return {
        title: { text: title, left: 'center', top: 5, textStyle: { fontSize: 16 } },
        color: colors,
        tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
        toolbox: {
          feature: {
            saveAsImage: { title: '保存图片' },
            dataView: { title: '数据视图', readOnly: true, lang: ['数据视图', '关闭', '刷新'] },
          },
          right: 10,
        },
        legend: { bottom: 5, type: 'scroll' },
        series: series.map(s => ({
          ...s,
          type: 'pie',
          radius: ['40%', '70%'],
          center: ['50%', '50%'],
          label: { formatter: '{b}: {d}%', fontSize: 12 },
          emphasis: { label: { fontSize: 18, fontWeight: 'bold' } },
        })),
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
  if (!chartInstance) { ElMessage.warning('请先生成图表'); return }
  const url = chartInstance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' })
  const a = document.createElement('a')
  a.href = url
  a.download = `chart_${chartConfig.value.chartType}_${Date.now()}.png`
  a.click()
  ElMessage.success('图片已下载')
}

// 导出 CSV 数据
const exportCSV = () => {
  if (!chartData.value?.rawData?.length) { ElMessage.warning('无数据可导出'); return }
  const rawData = chartData.value.rawData
  const headers = Object.keys(rawData[0])
  const csvContent = [
    headers.join(','),
    ...rawData.map(row => headers.map(h => {
      const val = row[h]
      if (val === null || val === undefined) return ''
      const str = String(val)
      return str.includes(',') || str.includes('"') ? `"${str.replace(/"/g, '""')}"` : str
    }).join(','))
  ].join('\n')
  const blob = new Blob(['﻿' + csvContent], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `data_${chartConfig.value.chartType}_${Date.now()}.csv`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('CSV 已下载')
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

// AI 洞察（通过 EventSource SSE）
const generateAiInsight = () => {
  aiLoading.value = true
  aiContent.value = ''
  showAiPanel.value = true

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

  if (chartConfig.value.xAxis) {
    generateChartData()
  }

  resizeHandler = () => chartInstance?.resize()
  window.addEventListener('resize', resizeHandler)
})

onUnmounted(() => {
  if (chartInstance) chartInstance.dispose()
  if (resizeHandler) window.removeEventListener('resize', resizeHandler)
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
