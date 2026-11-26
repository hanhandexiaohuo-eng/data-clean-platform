<template>
  <div class="governance-page">
    <div class="page-header">
      <h2>AI 治理</h2>
      <el-tag type="info">API Key 只写不读，所有写操作需二次确认并留痕</el-tag>
    </div>

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- 供应商 -->
      <el-tab-pane label="供应商" name="providers">
        <div class="toolbar">
          <el-button type="primary" @click="openProviderDialog()">
            <el-icon><Plus /></el-icon>新增供应商
          </el-button>
        </div>
        <el-table :data="providers" border size="small" empty-text="暂无供应商">
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="apiUrl" label="API 地址" min-width="220" show-overflow-tooltip />
          <el-table-column label="API Key" width="160">
            <template #default="{ row }">
              <el-tag size="small" :type="row.keyConfigured ? 'success' : 'info'">
                {{ row.keyConfigured ? (row.apiKeyMask || '已配置') : '未配置' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="模型数" width="90">
            <template #default="{ row }">{{ row.modelCount || 0 }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
                {{ row.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openProviderDialog(row)">编辑</el-button>
              <el-button link type="danger" size="small" @click="removeProvider(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 模型 -->
      <el-tab-pane label="模型" name="models">
        <div class="toolbar">
          <el-button type="primary" @click="openModelDialog()">
            <el-icon><Plus /></el-icon>新增模型
          </el-button>
        </div>
        <el-table :data="models" border size="small" empty-text="暂无模型">
          <el-table-column label="供应商" width="140">
            <template #default="{ row }">{{ providerName(row.providerId) }}</template>
          </el-table-column>
          <el-table-column prop="modelCode" label="模型标识" min-width="150" />
          <el-table-column prop="displayName" label="显示名" min-width="120" />
          <el-table-column prop="temperature" label="温度" width="80" />
          <el-table-column prop="maxOutputTokens" label="最大输出" width="100" />
          <el-table-column prop="timeoutMs" label="超时(ms)" width="100" />
          <el-table-column prop="retryCount" label="重试" width="70" />
          <el-table-column prop="concurrencyLimit" label="并发" width="70" />
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openModelDialog(row)">编辑</el-button>
              <el-button link type="danger" size="small" @click="removeModel(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 场景路由 -->
      <el-tab-pane label="场景路由" name="scenes">
        <div class="toolbar">
          <el-button type="primary" @click="openRouteDialog()">
            <el-icon><Plus /></el-icon>新增路由
          </el-button>
          <span class="toolbar-tip">未配置路由的场景将回退到既有默认 AI 配置</span>
        </div>
        <el-table :data="routes" border size="small" empty-text="暂无场景路由">
          <el-table-column prop="scene" label="场景" width="170" />
          <el-table-column label="主模型" min-width="150">
            <template #default="{ row }">{{ modelLabel(row.primaryModelId) }}</template>
          </el-table-column>
          <el-table-column label="备用模型" min-width="150">
            <template #default="{ row }">{{ modelLabel(row.fallbackModelId) }}</template>
          </el-table-column>
          <el-table-column prop="temperature" label="温度" width="80" />
          <el-table-column prop="maxOutputTokens" label="最大输出" width="100" />
          <el-table-column prop="timeoutMs" label="超时(ms)" width="100" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
                {{ row.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openRouteDialog(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 调用审计 -->
      <el-tab-pane label="调用审计" name="calls">
        <div class="toolbar filters">
          <el-date-picker v-model="callFilters.range" type="daterange" value-format="YYYY-MM-DD"
                          start-placeholder="开始日期" end-placeholder="结束日期" style="width: 260px" />
          <el-select v-model="callFilters.scene" placeholder="场景" clearable style="width: 170px">
            <el-option v-for="scene in sceneOptions" :key="scene" :label="scene" :value="scene" />
          </el-select>
          <el-select v-model="callFilters.result" placeholder="结果" clearable style="width: 130px">
            <el-option label="SUCCESS" value="SUCCESS" />
            <el-option label="FAILED" value="FAILED" />
            <el-option label="DEGRADED" value="DEGRADED" />
            <el-option label="REJECTED" value="REJECTED" />
          </el-select>
          <el-button type="primary" @click="loadCalls">查询</el-button>
        </div>
        <el-table :data="calls.items" border size="small" empty-text="暂无调用记录">
          <el-table-column prop="requestAt" label="时间" width="170" />
          <el-table-column prop="scene" label="场景" width="160" />
          <el-table-column prop="providerName" label="供应商" width="120" />
          <el-table-column prop="modelCode" label="模型" min-width="140" show-overflow-tooltip />
          <el-table-column prop="latencyMs" label="耗时(ms)" width="90" />
          <el-table-column label="Token" width="100">
            <template #default="{ row }">{{ (row.inputTokens || 0) + (row.outputTokens || 0) }}</template>
          </el-table-column>
          <el-table-column prop="cost" label="成本" width="90" />
          <el-table-column label="结果" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="resultType(row.result)">{{ row.result }}</el-tag>
              <el-tag v-if="row.degraded" size="small" type="warning" style="margin-left: 4px">降级</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="errorType" label="错误类型" width="110" />
          <el-table-column prop="quotaScope" label="配额范围" width="100" />
        </el-table>
        <el-pagination class="pager" layout="total, prev, pager, next" :total="calls.total"
                       :page-size="callFilters.size" :current-page="callFilters.page"
                       @current-change="onPageChange" />
      </el-tab-pane>

      <!-- 用量 -->
      <el-tab-pane label="配额用量" name="usage">
        <div class="toolbar">
          <el-date-picker v-model="usageDate" type="date" value-format="YYYY-MM-DD" @change="loadUsage" />
          <el-button @click="loadUsage">刷新</el-button>
        </div>
        <el-table :data="usage.items" border size="small" empty-text="当日暂无调用">
          <el-table-column prop="scopeType" label="配额范围" width="140" />
          <el-table-column prop="scene" label="场景" min-width="160" />
          <el-table-column prop="modelCode" label="模型" min-width="140" />
          <el-table-column prop="calls" label="调用次数" width="110" />
          <el-table-column prop="tokens" label="Token" width="120" />
          <el-table-column prop="cost" label="成本" width="100" />
        </el-table>
      </el-tab-pane>

      <!-- 熔断 -->
      <el-tab-pane label="熔断状态" name="circuits">
        <div class="toolbar">
          <el-button @click="loadCircuits">
            <el-icon><Refresh /></el-icon>刷新
          </el-button>
        </div>
        <el-table :data="circuits" border size="small" empty-text="当前无熔断记录">
          <el-table-column prop="circuitKey" label="熔断键" min-width="180" />
          <el-table-column prop="state" label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.state === 'OPEN' ? 'danger' : row.state === 'HALF_OPEN' ? 'warning' : 'success'">
                {{ row.state }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="failureCount" label="失败次数" width="100" />
          <el-table-column prop="errorType" label="错误类型" width="110" />
          <el-table-column prop="openedAt" label="打开时间" width="170" />
          <el-table-column prop="cooldownUntil" label="冷却截止" width="170" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="resetCircuit(row)">重置</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 供应商弹窗 -->
    <el-dialog v-model="providerDialogVisible" :title="providerForm.id ? '编辑供应商' : '新增供应商'" width="520px">
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="providerForm.name" maxlength="80" />
        </el-form-item>
        <el-form-item label="API 地址" required>
          <el-input v-model="providerForm.apiUrl" placeholder="https://api.example.com/v1/chat/completions" />
        </el-form-item>
        <el-form-item label="API Key" :required="!providerForm.id">
          <el-input v-model="providerForm.apiKey" type="password" show-password
                    :placeholder="providerForm.id ? '留空表示保持原密钥' : '请输入 API Key'" />
          <div class="form-tip">密钥仅写入后端加密存储，保存后只返回掩码</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="providerForm.status" :active-value="1" :inactive-value="0" active-text="启用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="providerDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveProvider">保存</el-button>
      </template>
    </el-dialog>

    <!-- 模型弹窗 -->
    <el-dialog v-model="modelDialogVisible" :title="modelForm.id ? '编辑模型' : '新增模型'" width="580px">
      <el-form label-width="110px">
        <el-form-item label="供应商" required>
          <el-select v-model="modelForm.providerId" style="width: 100%">
            <el-option v-for="p in providers" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型标识" required>
          <el-input v-model="modelForm.modelCode" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="modelForm.displayName" />
        </el-form-item>
        <el-form-item label="温度">
          <el-input-number v-model="modelForm.temperature" :min="0" :max="2" :step="0.1" />
        </el-form-item>
        <el-form-item label="最大输出">
          <el-input-number v-model="modelForm.maxOutputTokens" :min="1" :max="32768" :step="128" />
        </el-form-item>
        <el-form-item label="超时(ms)">
          <el-input-number v-model="modelForm.timeoutMs" :min="1000" :max="120000" :step="1000" />
        </el-form-item>
        <el-form-item label="重试次数">
          <el-input-number v-model="modelForm.retryCount" :min="0" :max="2" />
        </el-form-item>
        <el-form-item label="并发上限">
          <el-input-number v-model="modelForm.concurrencyLimit" :min="1" :max="50" />
        </el-form-item>
        <el-form-item label="输入成本/1K">
          <el-input-number v-model="modelForm.costPer1kInput" :min="0" :precision="4" />
        </el-form-item>
        <el-form-item label="输出成本/1K">
          <el-input-number v-model="modelForm.costPer1kOutput" :min="0" :precision="4" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="modelForm.status" :active-value="1" :inactive-value="0" active-text="启用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveModel">保存</el-button>
      </template>
    </el-dialog>

    <!-- 路由弹窗 -->
    <el-dialog v-model="routeDialogVisible" :title="routeForm.id ? '编辑场景路由' : '新增场景路由'" width="560px">
      <el-form label-width="110px">
        <el-form-item label="场景" required>
          <el-select v-model="routeForm.scene" style="width: 100%" :disabled="!!routeForm.id">
            <el-option v-for="scene in sceneOptions" :key="scene" :label="scene" :value="scene" />
          </el-select>
        </el-form-item>
        <el-form-item label="主模型">
          <el-select v-model="routeForm.primaryModelId" clearable style="width: 100%">
            <el-option v-for="m in models" :key="m.id" :label="modelLabel(m.id)" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="备用模型">
          <el-select v-model="routeForm.fallbackModelId" clearable style="width: 100%">
            <el-option v-for="m in models" :key="m.id" :label="modelLabel(m.id)" :value="m.id" />
          </el-select>
          <div class="form-tip">主模型超时/429/熔断时自动切换备用模型</div>
        </el-form-item>
        <el-form-item label="温度覆盖">
          <el-input-number v-model="routeForm.temperature" :min="0" :max="2" :step="0.1" placeholder="留空继承模型" />
        </el-form-item>
        <el-form-item label="最大输出覆盖">
          <el-input-number v-model="routeForm.maxOutputTokens" :min="1" :max="32768" placeholder="留空继承模型" />
        </el-form-item>
        <el-form-item label="超时覆盖(ms)">
          <el-input-number v-model="routeForm.timeoutMs" :min="1000" :max="120000" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="routeForm.status" :active-value="1" :inactive-value="0" active-text="启用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="routeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRoute">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listProviders,
  createProvider,
  updateProvider,
  deleteProvider,
  listModels,
  createModel,
  updateModel,
  deleteModel,
  listSceneRoutes,
  createSceneRoute,
  updateSceneRoute,
  listCalls,
  getUsage,
  listCircuits,
  resetCircuit as resetCircuitApi,
} from '@/api/governance'

const sceneOptions = [
  'DATASET_SUMMARY',
  'FIELD_RELATION',
  'OUTLIER_ANALYSIS',
  'CHART_INSIGHT',
  'CLEAN_SUGGESTION',
]

const activeTab = ref('providers')
const saving = ref(false)

const providers = ref([])
const models = ref([])
const routes = ref([])
const circuits = ref([])
const usage = ref({ items: [] })
const usageDate = ref('')
const calls = ref({ total: 0, items: [] })
const callFilters = reactive({ range: [], scene: '', result: '', page: 1, size: 20 })

const providerDialogVisible = ref(false)
const providerForm = ref({})
const modelDialogVisible = ref(false)
const modelForm = ref({})
const routeDialogVisible = ref(false)
const routeForm = ref({})

const providerName = (id) => providers.value.find(p => p.id === id)?.name || '-'
const modelLabel = (id) => {
  if (!id) return '-'
  const model = models.value.find(m => m.id === id)
  return model ? `${model.displayName || model.modelCode}` : `#${id}`
}
const resultType = (result) => ({ SUCCESS: 'success', FAILED: 'danger', DEGRADED: 'warning', REJECTED: 'info' }[result] || 'info')

const loadProviders = async () => { providers.value = await listProviders() }
const loadModels = async () => { models.value = await listModels() }
const loadRoutes = async () => { routes.value = await listSceneRoutes() }
const loadCircuits = async () => { circuits.value = await listCircuits() }
const loadUsage = async () => {
  usage.value = await getUsage(usageDate.value || undefined)
}
const loadCalls = async () => {
  const params = { page: callFilters.page, size: callFilters.size }
  if (callFilters.range?.length === 2) {
    params.from = callFilters.range[0]
    params.to = callFilters.range[1]
  }
  if (callFilters.scene) params.scene = callFilters.scene
  if (callFilters.result) params.result = callFilters.result
  calls.value = await listCalls(params)
}

const onPageChange = (page) => {
  callFilters.page = page
  loadCalls()
}

const onTabChange = async (name) => {
  try {
    if (name === 'providers') await loadProviders()
    if (name === 'models') { await loadProviders(); await loadModels() }
    if (name === 'scenes') { await loadModels(); await loadRoutes() }
    if (name === 'calls') await loadCalls()
    if (name === 'usage') await loadUsage()
    if (name === 'circuits') await loadCircuits()
  } catch (e) {
    ElMessage.error('加载失败：' + (e.message || '未知错误'))
  }
}

// 供应商
const openProviderDialog = (row) => {
  providerForm.value = row
    ? { id: row.id, name: row.name, apiUrl: row.apiUrl, apiKey: '', status: row.status }
    : { name: '', apiUrl: '', apiKey: '', status: 1 }
  providerDialogVisible.value = true
}

const saveProvider = async () => {
  if (!providerForm.value.name?.trim() || !providerForm.value.apiUrl?.trim()) {
    ElMessage.warning('请填写名称与 API 地址')
    return
  }
  if (!providerForm.value.id && !providerForm.value.apiKey?.trim()) {
    ElMessage.warning('新增供应商必须填写 API Key')
    return
  }
  try {
    await ElMessageBox.confirm('保存后 API Key 将不可回读，是否需要继续？', '二次确认', { type: 'warning' })
  } catch {
    return
  }
  saving.value = true
  try {
    if (providerForm.value.id) {
      await updateProvider(providerForm.value)
    } else {
      await createProvider(providerForm.value)
    }
    ElMessage.success('已保存')
    providerDialogVisible.value = false
    await loadProviders()
  } catch (e) {
    ElMessage.error('保存失败：' + (e.message || '未知错误'))
  } finally {
    saving.value = false
  }
}

const removeProvider = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除供应商「${row.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteProvider(row.id)
    ElMessage.success('已删除')
    await loadProviders()
  } catch (e) {
    ElMessage.error('删除失败：' + (e.message || '未知错误'))
  }
}

// 模型
const openModelDialog = (row) => {
  modelForm.value = row
    ? { ...row }
    : {
        providerId: providers.value[0]?.id,
        modelCode: '',
        displayName: '',
        temperature: 0.7,
        maxOutputTokens: 1024,
        timeoutMs: 30000,
        retryCount: 1,
        concurrencyLimit: 4,
        costPer1kInput: 0,
        costPer1kOutput: 0,
        status: 1,
      }
  modelDialogVisible.value = true
}

const saveModel = async () => {
  if (!modelForm.value.providerId || !modelForm.value.modelCode?.trim()) {
    ElMessage.warning('请选择供应商并填写模型标识')
    return
  }
  saving.value = true
  try {
    if (modelForm.value.id) {
      await updateModel(modelForm.value)
    } else {
      await createModel(modelForm.value)
    }
    ElMessage.success('已保存')
    modelDialogVisible.value = false
    await loadModels()
  } catch (e) {
    ElMessage.error('保存失败：' + (e.message || '未知错误'))
  } finally {
    saving.value = false
  }
}

const removeModel = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除模型「${row.modelCode}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteModel(row.id)
    ElMessage.success('已删除')
    await loadModels()
  } catch (e) {
    ElMessage.error('删除失败：' + (e.message || '未知错误'))
  }
}

// 路由
const openRouteDialog = (row) => {
  routeForm.value = row
    ? { ...row }
    : {
        scene: sceneOptions[0],
        primaryModelId: models.value[0]?.id,
        fallbackModelId: null,
        temperature: null,
        maxOutputTokens: null,
        timeoutMs: null,
        retryCount: null,
        concurrencyLimit: null,
        status: 1,
      }
  routeDialogVisible.value = true
}

const saveRoute = async () => {
  if (!routeForm.value.primaryModelId && !routeForm.value.fallbackModelId) {
    ElMessage.warning('主模型与备用模型不能同时为空')
    return
  }
  saving.value = true
  try {
    if (routeForm.value.id) {
      await updateSceneRoute(routeForm.value)
    } else {
      await createSceneRoute(routeForm.value)
    }
    ElMessage.success('已保存')
    routeDialogVisible.value = false
    await loadRoutes()
  } catch (e) {
    ElMessage.error('保存失败：' + (e.message || '未知错误'))
  } finally {
    saving.value = false
  }
}

const resetCircuit = async (row) => {
  try {
    await ElMessageBox.confirm(`确定重置熔断键「${row.circuitKey}」？`, '重置确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await resetCircuitApi(row.id)
    ElMessage.success('已重置')
    await loadCircuits()
  } catch (e) {
    ElMessage.error('重置失败：' + (e.message || '未知错误'))
  }
}

onMounted(async () => {
  try {
    await loadProviders()
  } catch (e) {
    ElMessage.error('加载供应商失败：' + (e.message || '未知错误'))
  }
})
</script>

<style scoped>
.governance-page {
  padding: 8px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-header h2 {
  font-size: 20px;
  font-weight: 600;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.toolbar.filters {
  flex-wrap: wrap;
}

.toolbar-tip {
  font-size: 12px;
  color: #909399;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-top: 4px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
