<template>
  <div class="dataset-page">
    <!-- 页面标题和操作栏 -->
    <div class="page-header">
      <div class="header-left">
        <h2 class="page-title">
          <el-icon><FolderOpened /></el-icon>
          数据集管理
        </h2>
        <p class="page-subtitle">管理和导入您的数据集</p>
      </div>
      <el-button type="primary" size="large" @click="showUploadDialog = true">
        <el-icon><Plus /></el-icon>导入数据
      </el-button>
    </div>

    <!-- 搜索栏 -->
    <el-card class="search-card" shadow="never">
      <el-input
        v-model="searchKeyword"
        placeholder="搜索数据集名称或描述..."
        clearable
        style="width: 400px"
        @keyup.enter="handleSearch"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-button type="primary" style="margin-left: 12px" @click="handleSearch">
        <el-icon><Search /></el-icon>搜索
      </el-button>
    </el-card>

    <!-- 数据集列表 -->
    <el-row :gutter="16" class="dataset-grid" v-loading="loading">
      <el-col :span="8" v-for="item in datasetList" :key="item.id">
        <el-card class="dataset-card" shadow="hover" :body-style="{ padding: '0' }">
          <div class="card-header">
            <div class="card-icon">
              <el-icon :size="32" color="#667eea">
                <Document v-if="item.fileType === 'csv'" />
                <Grid v-else />
              </el-icon>
            </div>
            <div class="card-info">
              <h4 class="card-title" :title="item.name">{{ item.name }}</h4>
              <el-tag size="small" :type="item.fileType === 'csv' ? 'success' : 'warning'" effect="plain">
                {{ item.fileType?.toUpperCase() }}
              </el-tag>
            </div>
          </div>
          <div class="card-body">
            <p class="card-desc" v-if="item.description">{{ item.description }}</p>
            <p class="card-desc placeholder" v-else>暂无描述</p>
            <div class="card-stats">
              <div class="stat-item">
                <el-icon><List /></el-icon>
                <span>{{ item.rowCount?.toLocaleString() }} 行</span>
              </div>
              <div class="stat-item">
                <el-icon><Grid /></el-icon>
                <span>{{ item.columnCount }} 列</span>
              </div>
              <div class="stat-item">
                <el-icon><Timer /></el-icon>
                <span>{{ formatDate(item.createdAt) }}</span>
              </div>
            </div>
          </div>
          <div class="card-footer">
            <el-button-group>
              <el-button type="primary" text size="small" @click="goToPreview(item.id)">
                <el-icon><View /></el-icon>预览
              </el-button>
              <el-button type="success" text size="small" @click="goToClean(item.id)">
                <el-icon><Brush /></el-icon>清洗
              </el-button>
              <el-button type="warning" text size="small" @click="goToAnalysis(item.id)">
                <el-icon><DataAnalysis /></el-icon>分析
              </el-button>
            </el-button-group>
            <el-popconfirm
              title="确定删除此数据集吗？此操作不可恢复！"
              confirm-button-text="确定"
              cancel-button-text="取消"
              @confirm="handleDelete(item.id)"
            >
              <template #reference>
                <el-button type="danger" link size="small">
                  <el-icon><Delete /></el-icon>
                </el-button>
              </template>
            </el-popconfirm>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 空状态 -->
    <el-empty v-if="!loading && datasetList.length === 0" description="暂无数据集，请先导入数据">
      <el-button type="primary" @click="showUploadDialog = true">导入数据</el-button>
    </el-empty>

    <!-- 分页 -->
    <div class="pagination-wrapper" v-if="total > 0">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[9, 18, 36]"
        :total="total"
        layout="total, sizes, prev, pager, next"
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </div>

    <!-- 上传对话框 -->
    <el-dialog
      v-model="showUploadDialog"
      title="导入数据"
      width="600px"
      :close-on-click-modal="false"
    >
      <el-form :model="uploadForm" label-width="100px" :rules="uploadRules" ref="uploadFormRef">
        <el-form-item label="数据集名称" prop="name">
          <el-input v-model="uploadForm.name" placeholder="请输入数据集名称" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="uploadForm.description"
            type="textarea"
            :rows="3"
            placeholder="请输入数据集描述（可选）"
          />
        </el-form-item>
        <el-form-item label="文件" prop="file">
          <el-upload
            class="upload-area"
            drag
            action="#"
            :auto-upload="false"
            :on-change="handleFileChange"
            :limit="1"
            accept=".csv,.xls,.xlsx"
          >
            <el-icon class="el-icon--upload" :size="48"><UploadFilled /></el-icon>
            <div class="el-upload__text">
              拖拽文件到此处或 <em>点击上传</em>
            </div>
            <template #tip>
              <div class="el-upload__tip">
                支持 CSV 和 Excel 文件，单个文件不超过 100MB
              </div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item label="包含表头">
          <el-switch v-model="uploadForm.hasHeader" active-text="是" inactive-text="否" />
        </el-form-item>
        <el-form-item label="编码格式">
          <el-select v-model="uploadForm.encoding" style="width: 200px">
            <el-option label="UTF-8" value="UTF-8" />
            <el-option label="GBK" value="GBK" />
            <el-option label="GB2312" value="GB2312" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showUploadDialog = false">取消</el-button>
        <el-button type="primary" @click="handleUpload" :loading="uploading">
          {{ uploading ? '上传中...' : '确认上传' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDatasetList, uploadFile, deleteDataset } from '@/api/dataset'

const router = useRouter()
const loading = ref(false)
const uploading = ref(false)
const showUploadDialog = ref(false)
const searchKeyword = ref('')
const page = ref(1)
const pageSize = ref(9)
const total = ref(0)
const datasetList = ref([])
const uploadFormRef = ref(null)
const uploadForm = ref({
  name: '',
  description: '',
  file: null,
  hasHeader: true,
  encoding: 'UTF-8',
})
const uploadRules = {
  name: [{ required: true, message: '请输入数据集名称', trigger: 'blur' }],
  file: [{ required: true, message: '请选择文件', trigger: 'change' }],
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await getDatasetList({
      page: page.value,
      size: pageSize.value,
      keyword: searchKeyword.value,
    })
    datasetList.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    console.error('加载数据失败', e)
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  page.value = 1
  loadData()
}

const handleSizeChange = (val) => {
  pageSize.value = val
  loadData()
}

const handlePageChange = (val) => {
  page.value = val
  loadData()
}

const handleFileChange = (file) => {
  uploadForm.value.file = file.raw
}

const handleUpload = async () => {
  if (!uploadForm.value.file) {
    ElMessage.warning('请选择文件')
    return
  }
  if (!uploadForm.value.name) {
    ElMessage.warning('请输入数据集名称')
    return
  }

  uploading.value = true
  const formData = new FormData()
  formData.append('file', uploadForm.value.file)
  formData.append('name', uploadForm.value.name)
  formData.append('description', uploadForm.value.description || '')
  formData.append('hasHeader', uploadForm.value.hasHeader)
  formData.append('encoding', uploadForm.value.encoding)

  try {
    await uploadFile(formData)
    ElMessage.success('上传成功')
    showUploadDialog.value = false
    uploadForm.value = { name: '', description: '', file: null, hasHeader: true, encoding: 'UTF-8' }
    loadData()
  } catch (e) {
    ElMessage.error('上传失败: ' + (e.message || '未知错误'))
  } finally {
    uploading.value = false
  }
}

const handleDelete = async (id) => {
  try {
    await deleteDataset(id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

const goToPreview = (id) => router.push(`/preview/${id}`)
const goToClean = (id) => router.push(`/clean/${id}`)
const goToAnalysis = (id) => router.push(`/analysis/${id}`)

const formatDate = (dateStr) => {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  return d.toLocaleDateString('zh-CN')
}

onMounted(loadData)
</script>

<style scoped>
.dataset-page {
  padding: 8px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-title {
  font-size: 22px;
  font-weight: 600;
  color: #1a1a2e;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.page-subtitle {
  color: #909399;
  font-size: 13px;
}

.search-card {
  margin-bottom: 20px;
  border-radius: 12px;
}

.dataset-grid {
  margin: 0 !important;
}

.dataset-card {
  margin-bottom: 16px;
  border-radius: 12px;
  transition: all 0.3s;
  overflow: hidden;
}

.dataset-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.1);
}

.card-header {
  padding: 20px 20px 12px;
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.card-icon {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  background: linear-gradient(135deg, #667eea20 0%, #764ba220 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.card-info {
  flex: 1;
  min-width: 0;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #1a1a2e;
  margin-bottom: 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-body {
  padding: 0 20px 16px;
}

.card-desc {
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
  margin-bottom: 12px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-desc.placeholder {
  color: #c0c4cc;
  font-style: italic;
}

.card-stats {
  display: flex;
  gap: 16px;
}

.stat-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #909399;
}

.card-footer {
  padding: 12px 20px;
  border-top: 1px solid #f0f0f0;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}

.upload-area :deep(.el-upload-dragger) {
  width: 100%;
  padding: 30px 0;
}
</style>
