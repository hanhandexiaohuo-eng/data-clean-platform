<template>
  <div class="ai-config-page">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-left">
        <h2 class="page-title">
          <el-icon><ChatDotRound /></el-icon>
          AI 配置管理
        </h2>
        <p class="page-subtitle">配置大模型 API 和 Prompt 模板</p>
      </div>
    </div>

    <!-- AI 配置列表 -->
    <el-card shadow="never" class="config-card">
      <template #header>
        <div class="card-header">
          <span>大模型 API 配置</span>
          <el-button type="primary" size="small" @click="openConfigDialog()">
            <el-icon><Plus /></el-icon>添加配置
          </el-button>
        </div>
      </template>

      <el-table :data="configs" border stripe>
        <el-table-column prop="name" label="配置名称" min-width="150" />
        <el-table-column prop="apiUrl" label="API 地址" min-width="250" show-overflow-tooltip>
          <template #default="{ row }">
            <el-text type="info" size="small">{{ row.apiUrl }}</el-text>
          </template>
        </el-table-column>
        <el-table-column prop="modelName" label="模型" width="150" />
        <el-table-column prop="temperature" label="温度" width="80" />
        <el-table-column prop="maxTokens" label="Max Tokens" width="120" />
        <el-table-column prop="isDefault" label="默认" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault === 1" type="success" size="small" effect="dark">是</el-tag>
            <el-tag v-else type="info" size="small">否</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openConfigDialog(row)">
              <el-icon><Edit /></el-icon>编辑
            </el-button>
            <el-popconfirm title="确定删除此配置?" @confirm="deleteConfigHandler(row.id)">
              <template #reference>
                <el-button type="danger" link size="small">
                  <el-icon><Delete /></el-icon>删除
                </el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Prompt 模板列表 -->
    <el-card shadow="never" class="template-card">
      <template #header>
        <div class="card-header">
          <span>Prompt 模板管理</span>
          <el-button type="primary" size="small" @click="openTemplateDialog()">
            <el-icon><Plus /></el-icon>添加模板
          </el-button>
        </div>
      </template>

      <el-table :data="templates" border stripe>
        <el-table-column prop="templateKey" label="模板标识" min-width="150" />
        <el-table-column prop="name" label="模板名称" min-width="150" />
        <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
        <el-table-column prop="isBuiltin" label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.isBuiltin === 1 ? 'warning' : 'info'" size="small">
              {{ row.isBuiltin === 1 ? '内置' : '自定义' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openTemplateDialog(row)">
              <el-icon><Edit /></el-icon>编辑
            </el-button>
            <el-popconfirm title="确定删除此模板?" @confirm="deleteTemplateHandler(row.id)" v-if="row.isBuiltin !== 1">
              <template #reference>
                <el-button type="danger" link size="small">
                  <el-icon><Delete /></el-icon>删除
                </el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 使用说明 -->
    <el-card shadow="never" class="help-card">
      <template #header>
        <span>配置说明</span>
      </template>
      <el-collapse>
        <el-collapse-item title="支持的 API 服务商" name="1">
          <ul>
            <li><strong>OpenAI</strong>：https://api.openai.com/v1/chat/completions，模型：gpt-3.5-turbo / gpt-4</li>
            <li><strong>阿里云通义千问</strong>：https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions，模型：qwen-turbo / qwen-plus</li>
            <li><strong>本地 Ollama</strong>：http://localhost:11434/v1/chat/completions，模型：llama2 / mistral 等</li>
          </ul>
        </el-collapse-item>
        <el-collapse-item title="Prompt 模板变量" name="2">
          <p v-pre>模板中可以使用 <code>{{变量名}}</code> 语法插入变量，系统会自动替换为实际值：</p>
          <ul v-pre>
            <li><code>{{datasetName}}</code> - 数据集名称</li>
            <li><code>{{rowCount}}</code> - 数据行数</li>
            <li><code>{{columnCount}}</code> - 列数</li>
            <li><code>{{columnStats}}</code> - 列统计信息(JSON格式)</li>
          </ul>
        </el-collapse-item>
        <el-collapse-item title="数据安全说明" name="3">
          <p>AI 模块采用以下安全措施：</p>
          <ul>
            <li>仅发送数据集元信息和统计摘要，不传输原始数据行</li>
            <li>支持本地部署的大模型，数据不出域</li>
            <li>API Key 加密存储在数据库中</li>
            <li>所有 AI 调用记录审计日志</li>
          </ul>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <!-- 配置对话框 -->
    <el-dialog v-model="configDialogVisible" :title="isEditConfig ? '编辑配置' : '添加配置'" width="600px">
      <el-form :model="configForm" label-width="120px" :rules="configRules" ref="configFormRef">
        <el-form-item label="配置名称" prop="name">
          <el-input v-model="configForm.name" placeholder="例如：OpenAI GPT-4" />
        </el-form-item>
        <el-form-item label="API 地址" prop="apiUrl">
          <el-input v-model="configForm.apiUrl" placeholder="https://api.openai.com/v1/chat/completions" />
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input v-model="configForm.apiKey" type="password" show-password placeholder="sk-..." />
        </el-form-item>
        <el-form-item label="模型名称" prop="modelName">
          <el-input v-model="configForm.modelName" placeholder="gpt-3.5-turbo" />
        </el-form-item>
        <el-form-item label="温度参数">
          <el-slider v-model="configForm.temperature" :min="0" :max="2" :step="0.1" show-input />
        </el-form-item>
        <el-form-item label="Max Tokens">
          <el-slider v-model="configForm.maxTokens" :min="256" :max="4096" :step="256" show-input />
        </el-form-item>
        <el-form-item label="超时时间(ms)">
          <el-input-number v-model="configForm.timeout" :min="5000" :max="120000" :step="5000" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="configForm.isDefault" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="configForm.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveConfigHandler" :loading="savingConfig">保存</el-button>
      </template>
    </el-dialog>

    <!-- 模板对话框 -->
    <el-dialog v-model="templateDialogVisible" :title="isEditTemplate ? '编辑模板' : '添加模板'" width="700px">
      <el-form :model="templateForm" label-width="120px" :rules="templateRules" ref="templateFormRef">
        <el-form-item label="模板标识" prop="templateKey">
          <el-input v-model="templateForm.templateKey" placeholder="例如：custom_analysis" :disabled="isEditTemplate && templateForm.isBuiltin === 1" />
        </el-form-item>
        <el-form-item label="模板名称" prop="name">
          <el-input v-model="templateForm.name" placeholder="显示名称" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="templateForm.description" type="textarea" :rows="2" placeholder="模板用途说明" />
        </el-form-item>
        <el-form-item label="模板内容" prop="content">
          <el-input
            v-model="templateForm.content"
            type="textarea"
            :rows="12"
            placeholder="支持 Markdown 和 {{变量}} 占位符"
          />
        </el-form-item>
        <el-form-item label="变量定义">
          <el-input v-model="templateForm.variables" placeholder='JSON格式，例如：["datasetName", "rowCount"]' />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="templateForm.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="templateDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveTemplateHandler" :loading="savingTemplate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getAiConfigs, saveAiConfig, deleteAiConfig, getPromptTemplates, savePromptTemplate, deletePromptTemplate } from '@/api/ai'

const configs = ref([])
const templates = ref([])
const loading = ref(false)

// 配置对话框
const configDialogVisible = ref(false)
const isEditConfig = ref(false)
const savingConfig = ref(false)
const configFormRef = ref(null)
const configForm = ref({
  id: null,
  name: '',
  apiUrl: '',
  apiKey: '',
  modelName: 'gpt-3.5-turbo',
  temperature: 0.7,
  maxTokens: 2048,
  timeout: 30000,
  isDefault: 0,
  status: 1,
})
const configRules = {
  name: [{ required: true, message: '请输入配置名称', trigger: 'blur' }],
  apiUrl: [{ required: true, message: '请输入API地址', trigger: 'blur' }],
  apiKey: [{ required: true, message: '请输入API Key', trigger: 'blur' }],
  modelName: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
}

// 模板对话框
const templateDialogVisible = ref(false)
const isEditTemplate = ref(false)
const savingTemplate = ref(false)
const templateFormRef = ref(null)
const templateForm = ref({
  id: null,
  templateKey: '',
  name: '',
  description: '',
  content: '',
  variables: '',
  isBuiltin: 0,
  status: 1,
})
const templateRules = {
  templateKey: [{ required: true, message: '请输入模板标识', trigger: 'blur' }],
  name: [{ required: true, message: '请输入模板名称', trigger: 'blur' }],
  content: [{ required: true, message: '请输入模板内容', trigger: 'blur' }],
}

const loadConfigs = async () => {
  try {
    configs.value = await getAiConfigs()
  } catch (e) {
    ElMessage.error('加载配置失败')
  }
}

const loadTemplates = async () => {
  try {
    templates.value = await getPromptTemplates()
  } catch (e) {
    ElMessage.error('加载模板失败')
  }
}

const openConfigDialog = (row) => {
  if (row) {
    isEditConfig.value = true
    configForm.value = { ...row }
  } else {
    isEditConfig.value = false
    configForm.value = {
      id: null,
      name: '',
      apiUrl: '',
      apiKey: '',
      modelName: 'gpt-3.5-turbo',
      temperature: 0.7,
      maxTokens: 2048,
      timeout: 30000,
      isDefault: 0,
      status: 1,
    }
  }
  configDialogVisible.value = true
}

const saveConfigHandler = async () => {
  const valid = await configFormRef.value.validate().catch(() => false)
  if (!valid) return

  savingConfig.value = true
  try {
    await saveAiConfig(configForm.value)
    ElMessage.success('保存成功')
    configDialogVisible.value = false
    loadConfigs()
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    savingConfig.value = false
  }
}

const deleteConfigHandler = async (id) => {
  try {
    await deleteAiConfig(id)
    ElMessage.success('删除成功')
    loadConfigs()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

const openTemplateDialog = (row) => {
  if (row) {
    isEditTemplate.value = true
    templateForm.value = { ...row }
  } else {
    isEditTemplate.value = false
    templateForm.value = {
      id: null,
      templateKey: '',
      name: '',
      description: '',
      content: '',
      variables: '',
      isBuiltin: 0,
      status: 1,
    }
  }
  templateDialogVisible.value = true
}

const saveTemplateHandler = async () => {
  const valid = await templateFormRef.value.validate().catch(() => false)
  if (!valid) return

  savingTemplate.value = true
  try {
    await savePromptTemplate(templateForm.value)
    ElMessage.success('保存成功')
    templateDialogVisible.value = false
    loadTemplates()
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    savingTemplate.value = false
  }
}

const deleteTemplateHandler = async (id) => {
  try {
    await deletePromptTemplate(id)
    ElMessage.success('删除成功')
    loadTemplates()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

onMounted(() => {
  loadConfigs()
  loadTemplates()
})
</script>

<style scoped>
.ai-config-page {
  padding: 8px;
}

.page-header {
  margin-bottom: 20px;
}

.page-title {
  font-size: 22px;
  font-weight: 600;
  color: #1a1a2e;
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}

.page-subtitle {
  color: #909399;
  font-size: 13px;
}

.config-card,
.template-card,
.help-card {
  margin-bottom: 20px;
  border-radius: 12px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.help-card ul {
  padding-left: 24px;
  line-height: 2;
}

.help-card code {
  background: #f4f4f5;
  padding: 2px 8px;
  border-radius: 4px;
  font-family: monospace;
  color: #d73a49;
}
</style>
