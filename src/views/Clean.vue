<template>
  <div class="clean-page" v-loading="loading">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-left">
        <el-button @click="$router.back()" text>
          <el-icon><ArrowLeft /></el-icon>返回
        </el-button>
        <h2>数据清洗 - {{ dataset?.name }}</h2>
      </div>
      <div class="header-actions">
        <el-button @click="showAiPanel = !showAiPanel" :type="showAiPanel ? 'primary' : 'default'">
          <el-icon><ChatDotRound /></el-icon>AI 建议
        </el-button>
      </div>
    </div>

    <el-row :gutter="20">
      <!-- 左侧：规则配置 -->
      <el-col :span="showAiPanel ? 16 : 20">
        <!-- 规则列表 -->
        <el-card shadow="never" class="rules-card">
          <template #header>
            <div class="card-header">
              <span>清洗规则配置</span>
              <el-button type="primary" size="small" @click="showAddRuleDialog = true">
                <el-icon><Plus /></el-icon>添加规则
              </el-button>
            </div>
          </template>

          <!-- 空状态 -->
          <el-empty v-if="rules.length === 0" description="暂无清洗规则，请添加规则">
            <el-button type="primary" size="small" @click="showAddRuleDialog = true">添加规则</el-button>
          </el-empty>

          <!-- 规则列表(可拖拽排序) -->
          <div class="rules-list" ref="rulesListRef" v-else>
            <div
              v-for="(rule, index) in rules"
              :key="rule.id"
              class="rule-item"
              :data-id="rule.id"
            >
              <div class="rule-drag-handle">
                <el-icon><Sort /></el-icon>
              </div>
              <div class="rule-info">
                <div class="rule-name">{{ rule.ruleName }}</div>
                <div class="rule-type">{{ getRuleTypeLabel(rule.ruleType) }}</div>
              </div>
              <div class="rule-config">
                <el-tag size="small" type="info">{{ formatRuleConfig(rule) }}</el-tag>
              </div>
              <div class="rule-status">
                <el-switch v-model="rule.status" :active-value="1" :inactive-value="0" @change="toggleRuleStatus(rule)" />
              </div>
              <div class="rule-actions">
                <el-button type="primary" link size="small" @click="editRule(rule)">
                  <el-icon><Edit /></el-icon>
                </el-button>
                <el-popconfirm title="确定删除此规则?" @confirm="handleDeleteRule(rule.id)">
                  <template #reference>
                    <el-button type="danger" link size="small">
                      <el-icon><Delete /></el-icon>
                    </el-button>
                  </template>
                </el-popconfirm>
              </div>
            </div>
          </div>

          <!-- 执行按钮 -->
          <div class="execute-actions" v-if="rules.length > 0">
            <el-button type="primary" size="large" @click="executeClean" :loading="executing">
              <el-icon><Brush /></el-icon>执行清洗
            </el-button>
            <el-button @click="previewCleanEffect" :loading="previewLoading">
              <el-icon><View /></el-icon>预览效果
            </el-button>
          </div>
        </el-card>

        <!-- 清洗结果 -->
        <el-card v-if="cleanResult" shadow="never" class="result-card" :class="{ 'result-success': cleanResult.affectedRows >= 0 }">
          <template #header>
            <div class="result-header">
              <el-icon :size="20" color="#67c23a"><CircleCheck /></el-icon>
              <span>清洗结果</span>
            </div>
          </template>
          <el-descriptions :column="4" border>
            <el-descriptions-item label="清洗前行数">{{ cleanResult.beforeCount?.toLocaleString() }}</el-descriptions-item>
            <el-descriptions-item label="清洗后行数">{{ cleanResult.afterCount?.toLocaleString() }}</el-descriptions-item>
            <el-descriptions-item label="受影响行数">
              <el-tag :type="cleanResult.affectedRows > 0 ? 'warning' : 'success'">{{ cleanResult.affectedRows?.toLocaleString() }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="执行耗时">{{ cleanResult.executeTimeMs }} ms</el-descriptions-item>
          </el-descriptions>
          <div class="executed-rules" v-if="cleanResult.executedRules?.length > 0">
            <el-text type="info">执行的规则: {{ cleanResult.executedRules.join(' → ') }}</el-text>
          </div>
          <div class="result-actions">
            <el-button type="danger" size="small" @click="handleRollbackClean" v-if="cleanResult.backupTable">
              <el-icon><RefreshLeft /></el-icon>回滚到清洗前
            </el-button>
          </div>
        </el-card>

        <!-- 清洗日志 -->
        <el-card v-if="logs.length > 0" shadow="never" class="logs-card">
          <template #header>
            <span>清洗历史</span>
          </template>
          <el-timeline>
            <el-timeline-item
              v-for="log in logs"
              :key="log.id"
              :type="log.status === 1 ? 'success' : 'danger'"
              :timestamp="formatDate(log.createdAt)"
            >
              <el-card shadow="never" size="small">
                <p>规则: {{ log.ruleIds }}</p>
                <p>影响行数: {{ log.affectedRows }} / 耗时: {{ log.executeTimeMs }}ms</p>
              </el-card>
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-col>

      <!-- 右侧：AI 面板 -->
      <el-col :span="8" v-show="showAiPanel">
        <el-card class="ai-panel" shadow="never">
          <template #header>
            <div class="ai-panel-header">
              <el-icon color="#667eea"><ChatDotRound /></el-icon>
              <span>AI 清洗建议</span>
            </div>
          </template>
          <div class="ai-panel-content markdown-body" v-if="aiContent" v-html="renderedAiContent"></div>
          <el-skeleton :rows="6" animated v-else />
          <div class="ai-panel-footer">
            <el-button type="primary" link size="small" @click="generateAiSuggestion" :loading="aiLoading">
              <el-icon><Refresh /></el-icon>重新生成建议
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 添加/编辑规则对话框 -->
    <el-dialog v-model="showAddRuleDialog" :title="isEdit ? '编辑规则' : '添加规则'" width="600px">
      <el-form :model="ruleForm" label-width="120px" :rules="ruleRules" ref="ruleFormRef">
        <el-form-item label="规则名称" prop="ruleName">
          <el-input v-model="ruleForm.ruleName" placeholder="例如：删除空值行" />
        </el-form-item>
        <el-form-item label="规则类型" prop="ruleType">
          <el-select v-model="ruleForm.ruleType" placeholder="选择规则类型" style="width: 100%" @change="onRuleTypeChange">
            <el-option label="缺失值处理" value="missing_value_handle" />
            <el-option label="删除重复行" value="remove_duplicate" />
            <el-option label="类型转换" value="type_convert" />
            <el-option label="列名标准化" value="column_standardize" />
            <el-option label="异常值处理" value="outlier_handle" />
            <el-option label="自定义过滤" value="custom_filter" />
          </el-select>
        </el-form-item>

        <!-- 缺失值处理配置 -->
        <template v-if="ruleForm.ruleType === 'missing_value_handle'">
          <el-form-item label="目标列">
            <el-select v-model="ruleForm.targetColumn" placeholder="选择列" style="width: 100%">
              <el-option v-for="col in columns" :key="col.columnAlias" :label="col.columnName" :value="col.columnAlias" />
            </el-select>
          </el-form-item>
          <el-form-item label="处理方式">
            <el-radio-group v-model="ruleForm.handleType">
              <el-radio label="delete">删除该行</el-radio>
              <el-radio label="mean">均值填充</el-radio>
              <el-radio label="median">中位数填充</el-radio>
              <el-radio label="fixed">固定值填充</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="填充值" v-if="ruleForm.handleType === 'fixed'">
            <el-input v-model="ruleForm.fillValue" placeholder="输入填充值" />
          </el-form-item>
        </template>

        <!-- 重复行配置 -->
        <template v-if="ruleForm.ruleType === 'remove_duplicate'">
          <el-form-item label="目标列">
            <el-input v-model="ruleForm.targetColumn" placeholder="留空则比较所有列，多列用逗号分隔" />
          </el-form-item>
        </template>

        <!-- 类型转换配置 -->
        <template v-if="ruleForm.ruleType === 'type_convert'">
          <el-form-item label="目标列">
            <el-select v-model="ruleForm.targetColumn" placeholder="选择列" style="width: 100%">
              <el-option v-for="col in columns" :key="col.columnAlias" :label="col.columnName" :value="col.columnAlias" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标类型">
            <el-select v-model="ruleForm.targetType" placeholder="选择类型" style="width: 100%">
              <el-option label="整数 (INT)" value="INT" />
              <el-option label="长整数 (BIGINT)" value="BIGINT" />
              <el-option label="小数 (DECIMAL)" value="DECIMAL(20,6)" />
              <el-option label="字符串 (VARCHAR)" value="VARCHAR(255)" />
              <el-option label="日期时间 (DATETIME)" value="DATETIME" />
            </el-select>
          </el-form-item>
        </template>

        <!-- 异常值处理配置 -->
        <template v-if="ruleForm.ruleType === 'outlier_handle'">
          <el-form-item label="目标列">
            <el-select v-model="ruleForm.targetColumn" placeholder="选择数值列" style="width: 100%">
              <el-option
                v-for="col in numericColumns"
                :key="col.columnAlias"
                :label="col.columnName"
                :value="col.columnAlias"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="检测方法">
            <el-radio-group v-model="ruleForm.detectMethod">
              <el-radio label="z_score">Z-Score (|z| > 3)</el-radio>
              <el-radio label="iqr">IQR (1.5倍四分位距)</el-radio>
            </el-radio-group>
          </el-form-item>
        </template>

        <!-- 自定义过滤配置 -->
        <template v-if="ruleForm.ruleType === 'custom_filter'">
          <el-form-item label="过滤表达式">
            <el-input
              v-model="ruleForm.filterExpression"
              type="textarea"
              :rows="3"
              placeholder="例如: `age` > 18 AND `age` < 100"
            />
          </el-form-item>
        </template>

        <el-form-item label="执行顺序">
          <el-input-number v-model="ruleForm.sortOrder" :min="0" :max="100" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="ruleForm.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddRuleDialog = false">取消</el-button>
        <el-button type="primary" @click="saveRule" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Sortable from 'sortablejs'
import { marked } from 'marked'
import { getDatasetById, getColumns } from '@/api/dataset'
import { getCleanRules, createRule, updateRule, deleteRule as deleteRuleApi, executeClean as executeCleanApi, previewClean as previewCleanApi, rollbackClean as rollbackCleanApi, getCleanLogs } from '@/api/clean'
import { generateStream } from '@/api/ai'

const route = useRoute()
const router = useRouter()
const datasetId = route.params.id

const loading = ref(true)
const showAiPanel = ref(false)
const showAddRuleDialog = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const executing = ref(false)
const previewLoading = ref(false)
const aiLoading = ref(false)
const aiContent = ref('')
const renderedAiContent = computed(() => aiContent.value ? marked.parse(aiContent.value) : '')

const dataset = ref(null)
const columns = ref([])
const numericColumns = computed(() => columns.value.filter(c =>
  c.dataType?.includes('INT') || c.dataType?.includes('DECIMAL') ||
  c.dataType?.includes('DOUBLE') || c.dataType?.includes('FLOAT')
))
const rules = ref([])
const logs = ref([])
const cleanResult = ref(null)
const rulesListRef = ref(null)

const ruleFormRef = ref(null)
const ruleForm = ref({
  id: null,
  datasetId: datasetId,
  ruleName: '',
  ruleType: 'missing_value_handle',
  targetColumn: '',
  handleType: 'delete',
  fillValue: '',
  targetType: '',
  detectMethod: 'z_score',
  filterExpression: '',
  sortOrder: 0,
  status: 1,
})

const ruleRules = {
  ruleName: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
  ruleType: [{ required: true, message: '请选择规则类型', trigger: 'change' }],
}

const ruleTypeLabels = {
  missing_value_handle: '缺失值处理',
  remove_duplicate: '删除重复行',
  type_convert: '类型转换',
  column_standardize: '列名标准化',
  outlier_handle: '异常值处理',
  custom_filter: '自定义过滤',
}

const getRuleTypeLabel = (type) => ruleTypeLabels[type] || type

const formatRuleConfig = (rule) => {
  try {
    const config = JSON.parse(rule.ruleConfig)
    const parts = []
    if (config.targetColumn) parts.push(`列: ${config.targetColumn}`)
    if (config.handleType) parts.push(`方式: ${config.handleType}`)
    if (config.targetType) parts.push(`类型: ${config.targetType}`)
    if (config.detectMethod) parts.push(`方法: ${config.detectMethod}`)
    return parts.join(' | ') || '无配置'
  } catch {
    return '无配置'
  }
}

const resetRuleForm = () => {
  ruleForm.value = {
    id: null,
    datasetId: datasetId,
    ruleName: '',
    ruleType: 'missing_value_handle',
    targetColumn: '',
    handleType: 'delete',
    fillValue: '',
    targetType: '',
    detectMethod: 'z_score',
    filterExpression: '',
    sortOrder: rules.value.length,
    status: 1,
  }
}

const onRuleTypeChange = () => {
  ruleForm.value.targetColumn = ''
  ruleForm.value.handleType = 'delete'
  ruleForm.value.fillValue = ''
  ruleForm.value.targetType = ''
  ruleForm.value.detectMethod = 'z_score'
  ruleForm.value.filterExpression = ''
}

const saveRule = async () => {
  const valid = await ruleFormRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    if (isEdit.value) {
      await updateRule(ruleForm.value.id, ruleForm.value)
    } else {
      await createRule(ruleForm.value)
    }
    ElMessage.success('保存成功')
    showAddRuleDialog.value = false
    loadRules()
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

const editRule = (rule) => {
  isEdit.value = true
  ruleForm.value = {
    id: rule.id,
    datasetId: rule.datasetId,
    ruleName: rule.ruleName,
    ruleType: rule.ruleType,
    sortOrder: rule.sortOrder,
    status: rule.status,
    targetColumn: '',
    handleType: 'delete',
    fillValue: '',
    targetType: '',
    detectMethod: 'z_score',
    filterExpression: '',
  }

  // 解析已有配置
  try {
    const config = JSON.parse(rule.ruleConfig)
    ruleForm.value.targetColumn = config.targetColumn || ''
    ruleForm.value.handleType = config.handleType || 'delete'
    ruleForm.value.fillValue = config.fillValue || ''
    ruleForm.value.targetType = config.targetType || ''
    ruleForm.value.detectMethod = config.detectMethod || 'z_score'
    ruleForm.value.filterExpression = config.expression || ''
  } catch {
    // ignore
  }

  showAddRuleDialog.value = true
}

const handleDeleteRule = async (id) => {
  try {
    await deleteRuleApi(id)
    ElMessage.success('删除成功')
    loadRules()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

const toggleRuleStatus = async (rule) => {
  try {
    const dto = { ...rule, status: rule.status }
    await updateRule(rule.id, dto)
  } catch (e) {
    ElMessage.error('更新失败')
    rule.status = rule.status === 1 ? 0 : 1
  }
}

const executeClean = async () => {
  executing.value = true
  try {
    const ruleIds = rules.value.filter(r => r.status === 1).map(r => r.id)
    if (ruleIds.length === 0) {
      ElMessage.warning('没有启用的规则')
      return
    }
    cleanResult.value = await executeCleanApi({ datasetId, ruleIds })
    ElMessage.success('清洗完成')
    loadLogs()
  } catch (e) {
    ElMessage.error('清洗失败: ' + (e.message || '未知错误'))
  } finally {
    executing.value = false
  }
}

const previewCleanEffect = async () => {
  previewLoading.value = true
  try {
    const ruleIds = rules.value.filter(r => r.status === 1).map(r => r.id)
    if (ruleIds.length === 0) {
      ElMessage.warning('没有启用的规则')
      return
    }
    cleanResult.value = await previewCleanApi({ datasetId, ruleIds })
    ElMessage.success('预览完成')
  } catch (e) {
    ElMessage.error('预览失败')
  } finally {
    previewLoading.value = false
  }
}

const handleRollbackClean = async () => {
  if (!cleanResult.value?.backupTable) return
  try {
    // 需要找到对应的logId
    ElMessage.info('回滚功能需要在历史记录中操作')
  } catch (e) {
    ElMessage.error('回滚失败')
  }
}

const generateAiSuggestion = async () => {
  aiLoading.value = true
  aiContent.value = ''

  try {
    const eventSource = generateStream({
      datasetId: datasetId,
      templateKey: 'clean_suggestion',
      contextType: 'clean_result',
    })

    let buffer = ''
    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        if (data.content) {
          buffer += data.content
          aiContent.value = buffer
        }
      } catch {
        // ignore non-JSON
      }
    }

    eventSource.onerror = () => {
      eventSource.close()
      aiLoading.value = false
      if (!aiContent.value) {
        aiContent.value = '## 清洗建议\n\n1. **缺失值处理**：检查数值列中的空值，建议使用均值或中位数填充\n2. **重复数据**：检查是否有完全重复的记录行\n3. **异常值检测**：使用 Z-Score 或 IQR 方法检测异常值\n4. **类型统一**：确保同一列的数据类型一致\n5. **列名标准化**：统一列名格式，去除特殊字符'
      }
    }
  } catch {
    aiLoading.value = false
  }
}

const initSortable = () => {
  if (!rulesListRef.value) return
  Sortable.create(rulesListRef.value, {
    handle: '.rule-drag-handle',
    animation: 200,
    onEnd: async (evt) => {
      const newIndex = evt.newIndex
      const oldIndex = evt.oldIndex
      if (newIndex === oldIndex) return

      const movedRule = rules.value.splice(oldIndex, 1)[0]
      rules.value.splice(newIndex, 0, movedRule)

      // 更新所有规则的排序
      for (let i = 0; i < rules.value.length; i++) {
        rules.value[i].sortOrder = i
        await updateRule(rules.value[i].id, {
          ...rules.value[i],
          sortOrder: i,
        })
      }
    },
  })
}

const loadDataset = async () => {
  dataset.value = await getDatasetById(datasetId)
}

const loadColumns = async () => {
  columns.value = await getColumns(datasetId)
}

const loadRules = async () => {
  rules.value = await getCleanRules(datasetId)
  nextTick(() => initSortable())
}

const loadLogs = async () => {
  logs.value = await getCleanLogs(datasetId)
}

const formatDate = (dateStr) => {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('zh-CN')
}

onMounted(async () => {
  await Promise.all([loadDataset(), loadColumns(), loadRules(), loadLogs()])
  loading.value = false

  // 默认生成AI建议
  generateAiSuggestion()
})
</script>

<style scoped>
.clean-page {
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

.rules-card,
.result-card,
.logs-card {
  margin-bottom: 20px;
  border-radius: 12px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.rules-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rule-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: #fafafa;
  border-radius: 10px;
  border: 1px solid #f0f0f0;
  transition: all 0.2s;
}

.rule-item:hover {
  background: #f5f7fa;
  border-color: #dcdfe6;
}

.rule-drag-handle {
  cursor: move;
  color: #c0c4cc;
  padding: 4px;
}

.rule-info {
  flex: 1;
  min-width: 0;
}

.rule-name {
  font-weight: 500;
  color: #1a1a2e;
  margin-bottom: 4px;
}

.rule-type {
  font-size: 12px;
  color: #909399;
}

.rule-config {
  margin-right: 16px;
}

.rule-status {
  margin-right: 12px;
}

.rule-actions {
  display: flex;
  gap: 4px;
}

.execute-actions {
  display: flex;
  gap: 12px;
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #e4e7ed;
}

.result-card {
  border-radius: 12px;
}

.result-card.result-success {
  border-color: #d1edc4;
}

.result-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.executed-rules {
  margin-top: 12px;
  padding: 8px 0;
}

.result-actions {
  margin-top: 16px;
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
  max-height: 500px;
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
