<template>
  <div class="dashboard-detail" v-loading="loading">
    <div class="page-header">
      <div class="header-left">
        <el-button text @click="$router.push('/dashboard')">
          <el-icon><ArrowLeft /></el-icon>返回
        </el-button>
        <h2>{{ dashboard?.title || '看板' }}</h2>
      </div>
      <div class="header-actions">
        <el-button @click="renameDashboard">
          <el-icon><EditPen /></el-icon>重命名
        </el-button>
        <el-button @click="loadDashboard">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="primary" @click="openAddDialog">
          <el-icon><Plus /></el-icon>添加图表
        </el-button>
      </div>
    </div>

    <el-empty v-if="dashboard && !items.length" description="还没有图表，点击右上角添加" />

    <div class="grid-container" v-else>
      <div
        v-for="(item, index) in items"
        :key="item.id"
        class="grid-item"
        :class="{ dragging: dragIndex === index }"
        draggable="true"
        :style="{ '--w': item.gridW || 6 }"
        @dragstart="onDragStart(index)"
        @dragover.prevent
        @drop="onDrop(index)"
        @dragend="dragIndex = -1"
      >
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="chart-card-header">
              <span class="chart-title">{{ item.title || '图表' }}</span>
              <div class="chart-controls">
                <el-button link size="small" @click="changeWidth(item, -2)" :disabled="layoutBusy || (item.gridW || 6) <= 2">－</el-button>
                <span class="width-label">{{ item.gridW || 6 }}/12</span>
                <el-button link size="small" @click="changeWidth(item, 2)" :disabled="layoutBusy || (item.gridW || 6) >= 12">＋</el-button>
                <el-button link size="small" @click="move(item, index, -1)" :disabled="layoutBusy || index === 0">上移</el-button>
                <el-button link size="small" @click="move(item, index, 1)" :disabled="layoutBusy || index === items.length - 1">下移</el-button>
                <el-button link size="small" @click="exportPng(item)" :loading="exportingIds.includes(item.id)" :disabled="item.invalid">PNG</el-button>
                <el-button link type="danger" size="small" @click="removeItem(item)">移除</el-button>
              </div>
            </div>
          </template>

          <el-alert v-if="item.invalid" type="error" :closable="false" show-icon style="margin-bottom: 8px">
            <template #title>
              {{ item.invalidReason || '图表已失效' }}
              <el-button link type="danger" size="small" @click="removeItem(item)">移除该卡片</el-button>
            </template>
          </el-alert>
          <el-alert v-if="!item.invalid && states[item.id]?.stale" type="warning" :closable="false" show-icon style="margin-bottom: 8px">
            <template #title>
              数据已更新，需要刷新
              <el-button link type="primary" size="small" @click="refreshItem(item)">立即刷新</el-button>
            </template>
          </el-alert>
          <el-alert v-if="!item.invalid && states[item.id]?.error" type="error" :title="states[item.id].error" :closable="false" show-icon />
          <el-empty v-else-if="!item.invalid && states[item.id]?.empty" description="暂无数据" :image-size="60" />
          <div
            :id="`dash-chart-${item.id}`"
            class="chart-body"
            v-show="!item.invalid && !states[item.id]?.empty && !states[item.id]?.error"
          ></div>
        </el-card>
      </div>
    </div>

    <el-dialog v-model="addDialogVisible" title="添加图表" width="520px">
      <el-form label-width="90px">
        <el-form-item label="数据集">
          <el-select v-model="selectedDatasetId" placeholder="选择数据集" style="width: 100%" @change="loadDatasetCharts">
            <el-option v-for="ds in datasets" :key="ds.id" :label="ds.name" :value="ds.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="已保存图表">
          <el-select v-model="selectedChartId" placeholder="选择图表" style="width: 100%" :disabled="!selectedDatasetId">
            <el-option v-for="chart in datasetCharts" :key="chart.id" :label="chart.title" :value="chart.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="宽度">
          <el-radio-group v-model="newItemGridW">
            <el-radio-button :label="4">1/3</el-radio-button>
            <el-radio-button :label="6">1/2</el-radio-button>
            <el-radio-button :label="12">整行</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="高度">
          <el-input-number v-model="newItemGridH" :min="2" :max="12" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="adding" @click="addItem">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createChart } from '@/utils/echarts'
import {
  getDashboard,
  renameDashboard as renameDashboardApi,
  addDashboardItem,
  updateDashboardItem,
  removeDashboardItem,
  reorderDashboard,
} from '@/api/dashboard'
import { getDatasetList } from '@/api/dataset'
import { listChartViews, generateSavedChart, exportSavedChartPng } from '@/api/analysis'

const route = useRoute()
const dashboardId = route.params.id

const loading = ref(false)
const dashboard = ref(null)
const items = computed(() => dashboard.value?.items || [])
const states = reactive({})
const handles = {}
const dragIndex = ref(-1)

const addDialogVisible = ref(false)
const adding = ref(false)
const exportingIds = ref([])
const layoutBusy = ref(false)
const datasets = ref([])
const datasetCharts = ref([])
const selectedDatasetId = ref(null)
const selectedChartId = ref(null)
const newItemGridW = ref(6)
const newItemGridH = ref(4)

const loadDashboard = async () => {
  loading.value = true
  try {
    dashboard.value = await getDashboard(dashboardId)
    await nextTick()
    for (const item of items.value) {
      await loadItem(item)
    }
  } catch (e) {
    ElMessage.error('加载看板失败：' + (e.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

const loadItem = async (item) => {
  // 失效引用（图表/数据集已被删除）：直接展示异常态，不请求图表接口，也不影响其他卡片
  if (item.invalid) {
    states[item.id] = { loading: false, empty: true, invalid: true }
    return
  }
  states[item.id] = { loading: true }
  try {
    const data = await generateSavedChart(item.savedChartId)
    states[item.id] = { loading: false, empty: data?.empty, data }
    await nextTick()
    renderItem(item.id, data, item.chartType)
  } catch (e) {
    const status = e?.response?.data?.data
    if (e?.response?.status === 409 && status) {
      states[item.id] = {
        loading: false,
        stale: true,
        status,
        empty: true,
      }
      return
    }
    states[item.id] = { loading: false, error: '图表生成失败', empty: true }
  }
}

const refreshItem = async (item) => {
  const status = states[item.id]?.status
  if (!status?.currentVersionId) {
    await loadItem(item)
    return
  }
  try {
    const data = await generateSavedChart(item.savedChartId, status.currentVersionId)
    states[item.id] = { loading: false, empty: data?.empty, data }
    await nextTick()
    renderItem(item.id, data, item.chartType)
  } catch (e) {
    const latest = e?.response?.data?.data
    if (e?.response?.status === 409 && latest) {
      // 数据版本在确认后又变化：保留最新状态，要求用户再次确认
      states[item.id] = { ...(states[item.id] || {}), loading: false, stale: true, status: latest }
    }
    ElMessage.error(e.message || '刷新失败')
  }
}

const renderItem = (itemId, data, chartType) => {
  const el = document.getElementById(`dash-chart-${itemId}`)
  if (!el || !data || data.empty) return
  if (handles[itemId]) {
    handles[itemId].dispose()
    delete handles[itemId]
  }
  const handle = createChart(el)
  handles[itemId] = handle
  handle.setOption(buildOption(data, chartType))
}

const buildOption = (data, chartType) => {
  const colors = ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de', '#3ba272', '#fc8452', '#9a60b4']
  const xData = data.xAxisData || []
  const series = (data.series || []).map((s, i) => ({
    ...s,
    itemStyle: { color: colors[i % colors.length] },
  }))
  const base = {
    color: colors,
    tooltip: { trigger: chartType === 'pie' ? 'item' : 'axis' },
    legend: { bottom: 0, type: 'scroll' },
    grid: { left: '3%', right: '4%', bottom: '15%', top: 40, containLabel: true },
  }
  const type = chartType || 'bar'
  if (type === 'pie') {
    return {
      ...base,
      series: series.map(s => ({ ...s, type: 'pie', radius: ['40%', '70%'], label: { formatter: '{b}: {d}%' } })),
    }
  }
  if (type === 'scatter') {
    return {
      ...base,
      xAxis: { type: 'value' },
      yAxis: { type: 'value' },
      series: series.map(s => ({ ...s, type: 'scatter', symbolSize: 10 })),
    }
  }
  if (type === 'heatmap') {
    return {
      ...base,
      xAxis: { type: 'category', data: xData },
      yAxis: { type: 'category', data: data.statistics?.yAxisData || [] },
      visualMap: { min: 0, max: 100, calculable: true, orient: 'horizontal', left: 'center', bottom: 0 },
      series: series.map(s => ({ ...s, type: 'heatmap' })),
    }
  }
  if (type === 'line' || type === 'area') {
    return {
      ...base,
      xAxis: { type: 'category', data: xData, boundaryGap: false },
      yAxis: { type: 'value' },
      series: series.map(s => ({ ...s, type: 'line', smooth: true, areaStyle: { opacity: 0.2 } })),
    }
  }
  return {
    ...base,
    xAxis: { type: 'category', data: xData },
    yAxis: { type: 'value' },
    series: series.map(s => ({ ...s, type: 'bar', barMaxWidth: 50 })),
  }
}

const changeWidth = async (item, delta) => {
  if (layoutBusy.value) return
  const next = Math.min(12, Math.max(2, (item.gridW || 6) + delta))
  if (next === item.gridW) return
  layoutBusy.value = true
  try {
    await updateDashboardItem(dashboardId, item.id, { gridW: next })
    item.gridW = next
    await nextTick()
    const handle = handles[item.id]
    if (handle) handle.getInstance()?.resize()
  } catch (e) {
    ElMessage.error('调整宽度失败：' + (e.message || '未知错误'))
  } finally {
    layoutBusy.value = false
  }
}

const move = async (item, index, delta) => {
  if (layoutBusy.value) return
  const target = index + delta
  if (target < 0 || target >= items.value.length) return
  const ids = items.value.map(i => i.id)
  const tmp = ids[index]
  ids[index] = ids[target]
  ids[target] = tmp
  layoutBusy.value = true
  try {
    dashboard.value = await reorderDashboard(dashboardId, ids)
  } catch (e) {
    ElMessage.error('排序失败：' + (e.message || '未知错误'))
  } finally {
    layoutBusy.value = false
  }
}

const removeItem = async (item) => {
  try {
    await ElMessageBox.confirm('确定从看板移除该图表卡片？', '移除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await removeDashboardItem(dashboardId, item.id)
    if (handles[item.id]) {
      handles[item.id].dispose()
      delete handles[item.id]
    }
    ElMessage.success('已移除')
    await loadDashboard()
  } catch (e) {
    ElMessage.error('移除失败：' + (e.message || '未知错误'))
  }
}

// utils/request 对 responseType=blob 返回原始 axios response，因此这里取 response.data（Blob）
const exportPng = async (item) => {
  if (exportingIds.value.includes(item.id)) return
  exportingIds.value = [...exportingIds.value, item.id]
  try {
    const response = await exportSavedChartPng(item.savedChartId, { width: 1200, height: 720 })
    const blob = new Blob([response.data], { type: 'image/png' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${(item.title || 'chart').replace(/[\\/:*?"<>|]/g, '_')}.png`
    anchor.click()
    URL.revokeObjectURL(url)
    ElMessage.success('PNG 导出完成，记录已写入任务中心')
  } catch (e) {
    ElMessage.error(e?.message || 'PNG 导出失败')
  } finally {
    exportingIds.value = exportingIds.value.filter(id => id !== item.id)
  }
}

const openAddDialog = async () => {
  addDialogVisible.value = true
  selectedDatasetId.value = null
  selectedChartId.value = null
  datasetCharts.value = []
  if (!datasets.value.length) {
    try {
      const result = await getDatasetList({ page: 1, size: 200 })
      datasets.value = result?.records || result?.items || result || []
    } catch (e) {
      ElMessage.error('加载数据集失败')
    }
  }
}

const loadDatasetCharts = async (datasetId) => {
  selectedChartId.value = null
  try {
    datasetCharts.value = await listChartViews(datasetId)
  } catch (e) {
    datasetCharts.value = []
  }
}

const addItem = async () => {
  if (!selectedChartId.value) {
    ElMessage.warning('请选择要添加的图表')
    return
  }
  adding.value = true
  try {
    await addDashboardItem(dashboardId, {
      savedChartId: selectedChartId.value,
      sortOrder: items.value.length,
      gridX: 0,
      gridY: 0,
      gridW: newItemGridW.value,
      gridH: newItemGridH.value,
    })
    addDialogVisible.value = false
    ElMessage.success('已添加')
    await loadDashboard()
  } catch (e) {
    ElMessage.error('添加失败：' + (e.message || '未知错误'))
  } finally {
    adding.value = false
  }
}

onMounted(loadDashboard)

onUnmounted(() => {
  Object.keys(handles).forEach(key => {
    handles[key].dispose()
    delete handles[key]
  })
})
</script>

<style scoped>
.dashboard-detail {
  padding: 8px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-left h2 {
  font-size: 20px;
  font-weight: 600;
}

.grid-container {
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  gap: 16px;
}

.grid-item {
  grid-column: span var(--w, 6);
  min-width: 0;
}

.grid-item.dragging {
  opacity: 0.5;
}

.chart-card {
  border-radius: 12px;
}

.chart-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.chart-title {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart-controls {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.width-label {
  font-size: 12px;
  color: #909399;
}

.chart-body {
  width: 100%;
  height: 320px;
}

@media (max-width: 1100px) {
  .grid-item {
    grid-column: span 12 !important;
  }
}
</style>
