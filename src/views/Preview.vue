<template>
  <div class="preview-page" v-loading="loading">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-left">
        <el-button @click="$router.back()" text>
          <el-icon><ArrowLeft /></el-icon>返回
        </el-button>
        <div class="header-info">
          <h2>{{ dataset?.name }}</h2>
          <el-tag size="small" type="info">{{ dataset?.rowCount?.toLocaleString() }} 行 · {{ dataset?.columnCount }} 列</el-tag>
        </div>
      </div>
      <div class="header-actions">
        <el-button type="primary" @click="goToClean">
          <el-icon><Brush /></el-icon>数据清洗
        </el-button>
        <el-button type="warning" @click="goToAnalysis">
          <el-icon><DataAnalysis /></el-icon>可视化分析
        </el-button>
      </div>
    </div>

    <!-- AI 摘要面板 -->
    <el-card class="ai-summary-card" shadow="never" v-if="showAiPanel">
      <template #header>
        <div class="ai-header">
          <div class="ai-title">
            <el-icon :size="18" color="#667eea"><ChatDotRound /></el-icon>
            <span>AI 数据摘要</span>
            <el-tag v-if="aiLoading" type="primary" size="small" effect="dark">
              <el-icon class="is-loading"><Loading /></el-icon>生成中
            </el-tag>
          </div>
          <div class="ai-actions">
            <el-button type="primary" link size="small" @click="generateAiSummary" :loading="aiLoading">
              <el-icon><Refresh /></el-icon>重新生成
            </el-button>
            <el-button type="info" link size="small" @click="showAiPanel = false">
              <el-icon><Close /></el-icon>关闭
            </el-button>
          </div>
        </div>
      </template>
      <div class="ai-content markdown-body" v-if="aiContent" v-html="renderedAiContent"></div>
      <el-skeleton :rows="4" animated v-else />
    </el-card>

    <!-- 数据表格 -->
    <el-card class="data-table-card" shadow="never">
      <template #header>
        <div class="table-header">
          <div class="table-title">
            <el-icon><List /></el-icon>
            <span>数据预览</span>
            <el-tag type="info" size="small">共 {{ previewData?.total?.toLocaleString() }} 行</el-tag>
          </div>
          <div class="table-actions">
            <el-button type="primary" text size="small" @click="showAiPanel = !showAiPanel">
              <el-icon><ChatDotRound /></el-icon>AI 摘要
            </el-button>
          </div>
        </div>
      </template>
      <el-table
        :data="previewData?.rows"
        style="width: 100%"
        border
        stripe
        max-height="600"
        v-loading="tableLoading"
      >
        <el-table-column
          v-for="col in previewData?.columns"
          :key="col.name"
          :prop="col.name"
          :label="col.label"
          min-width="120"
          show-overflow-tooltip
        >
          <template #header>
            <div class="column-header">
              <span>{{ col.label }}</span>
              <el-tag size="small" type="info" effect="plain">{{ col.type }}</el-tag>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="previewData?.total"
          layout="total, prev, pager, next"
          @current-change="loadPreview"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { marked } from 'marked'
import { ElMessage } from 'element-plus'
import { getDatasetById, previewData as previewDataApi, getDatasetStatistics } from '@/api/dataset'
import { generate, generateStream } from '@/api/ai'

const route = useRoute()
const router = useRouter()
const datasetId = route.params.id

const loading = ref(true)
const tableLoading = ref(false)
const dataset = ref(null)
const previewData = ref(null)
const page = ref(1)
const pageSize = ref(20)
const showAiPanel = ref(false)
const aiLoading = ref(false)
const aiContent = ref('')
const renderedAiContent = computed(() => {
  return aiContent.value ? marked.parse(aiContent.value) : ''
})

const loadDataset = async () => {
  try {
    dataset.value = await getDatasetById(datasetId)
  } catch (e) {
    ElMessage.error('加载数据集失败')
  }
}

const loadPreview = async () => {
  tableLoading.value = true
  try {
    previewData.value = await previewDataApi(datasetId, { page: page.value, size: pageSize.value })
  } catch (e) {
    ElMessage.error('加载预览数据失败')
  } finally {
    tableLoading.value = false
  }
}

const generateAiSummary = async () => {
  aiLoading.value = true
  aiContent.value = ''
  showAiPanel.value = true

  try {
    // 使用 EventSource 流式获取
    const stats = await getDatasetStatistics(datasetId)
    const columnsStr = JSON.stringify(stats.columns || [], null, 2)

    const eventSource = generateStream({
      datasetId: datasetId,
      templateKey: 'dataset_summary',
      contextType: 'data_preview',
      customPrompt: '',
    })

    let buffer = ''
    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        if (data.content) {
          buffer += data.content
          aiContent.value = buffer
        }
        if (data.status === 'completed' || data.status === 'started') {
          // ignore
        }
      } catch (e) {
        // 非JSON数据可能是结束标记
      }
    }

    eventSource.onerror = (e) => {
      eventSource.close()
      aiLoading.value = false
      if (!aiContent.value) {
        // 如果流式获取失败，尝试同步获取
        fallbackSyncGenerate()
      }
    }
  } catch (e) {
    aiLoading.value = false
    fallbackSyncGenerate()
  }
}

const fallbackSyncGenerate = async () => {
  try {
    const res = await generate({
      datasetId: datasetId,
      templateKey: 'dataset_summary',
      contextType: 'data_preview',
    })
    aiContent.value = res
  } catch (e) {
    aiContent.value = 'AI 分析暂不可用，请检查 AI 配置。'
  } finally {
    aiLoading.value = false
  }
}

const goToClean = () => router.push(`/clean/${datasetId}`)
const goToAnalysis = () => router.push(`/analysis/${datasetId}`)

onMounted(async () => {
  await loadDataset()
  await loadPreview()
  loading.value = false
})
</script>

<style scoped>
.preview-page {
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

.header-info h2 {
  font-size: 20px;
  font-weight: 600;
  color: #1a1a2e;
  margin-bottom: 4px;
}

.header-actions {
  display: flex;
  gap: 12px;
}

.ai-summary-card {
  margin-bottom: 20px;
  border-radius: 12px;
  border: 1px solid #e0d4fc;
  background: linear-gradient(135deg, #faf7ff 0%, #f0f5ff 100%);
}

.ai-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.ai-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
  font-size: 15px;
  color: #1a1a2e;
}

.ai-actions {
  display: flex;
  gap: 8px;
}

.ai-content {
  max-height: 400px;
  overflow-y: auto;
  padding: 8px;
}

.data-table-card {
  border-radius: 12px;
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.table-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
  font-size: 15px;
  color: #1a1a2e;
}

.column-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}
</style>
