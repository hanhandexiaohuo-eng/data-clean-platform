<template>
  <div class="dc-page" v-loading="loading">
    <div class="dc-page-head">
      <div>
        <h1>数据看板</h1>
        <p>组合已保存的图表视图，形成面向业务的数据看板</p>
      </div>
      <div class="dc-page-actions">
        <el-button @click="load" :loading="loading">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>新建空白看板
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="appMode.publicMode"
      type="warning"
      :closable="false"
      show-icon
      title="公开临时工作区"
      description="看板与图表属于本匿名会话的临时资源，会话到期后将随工作区一并清理。"
    />

    <template v-if="!loading && dashboards.length === 0">
      <div class="dc-empty">
        <el-icon class="dc-empty-icon"><Grid /></el-icon>
        <h4>还没有看板</h4>
        <p>可以从模板创建空白看板，或先把图表保存到可视化分析页，再回来进行看板编排。</p>
        <div class="dc-page-actions" style="justify-content: center">
          <el-button type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>创建空白看板
          </el-button>
          <el-button @click="router.push('/datasets')">前往数据集</el-button>
        </div>
      </div>

      <div class="dc-card">
        <div class="dc-card-head">
          <div>
            <div class="dc-card-title">
              <el-icon><Files /></el-icon>使用模板
            </div>
            <div class="dc-card-sub">创建空白看板后，在详情页添加已保存图表并拖拽编排</div>
          </div>
        </div>
        <div class="tpl-grid">
          <div v-for="tpl in templates" :key="tpl.key" class="tpl-card">
            <div class="tpl-icon">
              <el-icon :size="20"><component :is="tpl.icon" /></el-icon>
            </div>
            <div class="tpl-title">{{ tpl.title }}</div>
            <p>{{ tpl.desc }}</p>
            <div class="dc-tag-row">
              <el-tag v-for="tag in tpl.tags" :key="tag" size="small" type="info" effect="plain">{{ tag }}</el-tag>
            </div>
            <el-button
              size="small"
              type="primary"
              plain
              :loading="creatingTemplate === tpl.key"
              @click="useTemplate(tpl)"
            >创建空白看板</el-button>
          </div>
        </div>
      </div>

      <div class="dc-grid-2">
        <div class="dc-card">
          <div class="dc-card-head">
            <div>
              <div class="dc-card-title">
                <el-icon><MagicStick /></el-icon>根据数据集推荐图表
              </div>
              <div class="dc-card-sub">读取后端真实推荐结果；不可用时如实提示</div>
            </div>
          </div>
          <div class="rec-toolbar">
            <el-select
              v-model="selectedDatasetId"
              placeholder="选择数据集"
              style="flex: 1"
              @change="loadRecommendations"
            >
              <el-option v-for="ds in datasets" :key="ds.id" :label="ds.name" :value="ds.id" />
            </el-select>
            <el-button :disabled="!selectedDatasetId" :loading="recommendLoading" @click="loadRecommendations">
              获取推荐
            </el-button>
          </div>

          <div v-if="recommendError" class="rec-error">{{ recommendError }}</div>
          <div v-else-if="recommendLoading" class="dc-muted rec-hint">正在读取推荐…</div>
          <template v-else-if="recommendations.length">
            <div class="dc-list">
              <div
                v-for="(item, index) in recommendations"
                :key="index"
                class="dc-list-item rec-item"
                @click="goAnalysis"
              >
                <div class="dc-list-main">
                  <div class="dc-list-title">{{ item.title || item.chartType || '推荐图表' }}</div>
                  <div class="dc-list-meta">{{ item.reason || '暂无推荐说明' }}</div>
                </div>
                <el-tag size="small" effect="plain">{{ confidenceLabel(item.confidence) }}</el-tag>
              </div>
            </div>
            <div v-if="recommendNotice" class="rec-note">{{ recommendNotice }}</div>
            <el-button type="primary" plain size="small" @click="goAnalysis">前往分析页生成图表</el-button>
          </template>
          <div v-else class="dc-muted rec-hint">
            {{ selectedDatasetId ? '暂无推荐结果，可前往分析页手动配置' : '选择数据集后可查看推荐图表' }}
          </div>
        </div>

        <div class="dc-card">
          <div class="dc-card-head">
            <div>
              <div class="dc-card-title">
                <el-icon><Grid /></el-icon>自由编排
              </div>
              <div class="dc-card-sub">在详情页添加图表、调整宽高、拖拽排序与导出 PNG</div>
            </div>
          </div>
          <div class="dc-list">
            <div class="dc-list-item">
              <div class="dc-list-main">
                <div class="dc-list-title">从已有图表添加</div>
                <div class="dc-list-meta">先打开一个看板，在详情页选择数据集与已保存图表</div>
              </div>
              <el-button size="small" :disabled="!dashboards.length" @click="openDashboard(dashboards[0].id)">
                打开首个看板
              </el-button>
            </div>
            <div class="dc-list-item">
              <div class="dc-list-main">
                <div class="dc-list-title">创建空白看板</div>
                <div class="dc-list-meta">真实创建看板，随后可在看板详情页继续查看</div>
              </div>
              <el-button size="small" type="primary" @click="openCreate">新建</el-button>
            </div>
          </div>
        </div>
      </div>
    </template>

    <div v-else-if="!loading" class="dash-grid">
      <div v-for="dashboard in dashboards" :key="dashboard.id" class="dash-card">
        <div class="dash-cover">
          <el-icon :size="30"><Grid /></el-icon>
          <div class="dash-cover-meta">
            <span>{{ dashboard.itemCount || 0 }} 个图表</span>
            <span>{{ formatDateTime(dashboard.updatedAt) }}</span>
          </div>
        </div>
        <div class="dash-title" :title="dashboard.title">{{ dashboard.title }}</div>
        <p class="dash-desc">{{ dashboard.description || '暂无描述' }}</p>
        <div class="dc-tag-row">
          <el-tag size="small" type="info" effect="plain">数据版本：打开后查看</el-tag>
          <el-tag v-if="dashboard.ownerType === 'anon'" size="small" type="warning" effect="plain">匿名会话</el-tag>
        </div>
        <div class="dash-actions">
          <el-button text type="primary" size="small" @click="openDashboard(dashboard.id)">打开</el-button>
          <el-button text size="small" @click="openRename(dashboard)">重命名</el-button>
          <el-button text type="danger" size="small" @click="removeDashboard(dashboard)">删除</el-button>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '重命名看板' : '创建空白看板'" width="440px">
      <el-form label-width="70px">
        <el-form-item label="名称" required>
          <el-input v-model="form.title" placeholder="请输入看板名称" maxlength="80" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Box,
  ChatDotRound,
  DataAnalysis,
  Files,
  Grid,
  Histogram,
  MagicStick,
  Plus,
  Refresh,
  TrendCharts,
} from '@element-plus/icons-vue'
import {
  listDashboards,
  createDashboard,
  renameDashboard,
  deleteDashboard,
} from '@/api/dashboard'
import { getDatasetList } from '@/api/dataset'
import { recommendCharts } from '@/api/analysis'
import { appMode } from '@/stores/appMode'

const router = useRouter()

const loading = ref(false)
const dashboards = ref([])
const dialogVisible = ref(false)
const editing = ref(null)
const saving = ref(false)
const creatingTemplate = ref('')
const form = ref({ title: '', description: '' })

const datasets = ref([])
const selectedDatasetId = ref(null)
const recommendLoading = ref(false)
const recommendError = ref('')
const recommendations = ref([])
const recommendNotice = ref('')

const templates = [
  { key: 'sales', title: '销售分析', desc: '按时间、区域与品类拆解销售额、订单量与转化情况。', tags: ['日期', '金额', '品类'], icon: TrendCharts },
  { key: 'ops', title: '运营分析', desc: '关注活跃、留存、渠道来源与关键运营指标的周期变化。', tags: ['日期', '渠道', '活跃'], icon: DataAnalysis },
  { key: 'survey', title: '问卷分析', desc: '统计题目选项分布、评分均值与不同人群的作答差异。', tags: ['题目', '评分', '人群'], icon: ChatDotRound },
  { key: 'inventory', title: '库存分析', desc: '查看库存水位、周转天数与缺货/积压风险。', tags: ['SKU', '库存', '周转'], icon: Box },
  { key: 'quality', title: '质量治理', desc: '跟踪空值率、唯一值、异常分布与清洗前后变化。', tags: ['字段', '空值率', '版本'], icon: Histogram },
]

const load = async () => {
  loading.value = true
  try {
    const res = await listDashboards()
    dashboards.value = Array.isArray(res) ? res : []
  } catch (e) {
    dashboards.value = []
  } finally {
    loading.value = false
  }
}

const loadDatasets = async () => {
  try {
    const res = await getDatasetList({ page: 1, size: 200 })
    datasets.value = res?.records || []
  } catch (e) {
    datasets.value = []
  }
}

const openCreate = () => {
  editing.value = null
  form.value = { title: '', description: '' }
  dialogVisible.value = true
}

const openRename = (dashboard) => {
  editing.value = dashboard
  form.value = { title: dashboard.title, description: dashboard.description || '' }
  dialogVisible.value = true
}

const submit = async () => {
  if (!form.value.title.trim()) {
    ElMessage.warning('请输入看板名称')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await renameDashboard(editing.value.id, form.value)
      ElMessage.success('已重命名')
      dialogVisible.value = false
      await load()
    } else {
      const created = await createDashboard(form.value)
      ElMessage.success('看板已创建')
      dialogVisible.value = false
      if (created?.id) router.push(`/dashboard/${created.id}`)
      else await load()
    }
  } catch (e) {
    ElMessage.error('保存失败：' + (e?.message || '未知错误'))
  } finally {
    saving.value = false
  }
}

const useTemplate = async (tpl) => {
  if (creatingTemplate.value) return
  creatingTemplate.value = tpl.key
  try {
    const created = await createDashboard({
      title: `${tpl.title}看板`,
      description: `空白看板（模板：${tpl.title}）。可在看板详情页添加已保存图表并拖拽编排。`,
    })
    ElMessage.success('已创建空白看板，可继续添加图表')
    if (created?.id) router.push(`/dashboard/${created.id}`)
    else await load()
  } catch (e) {
    ElMessage.error('创建失败：' + (e?.message || '未知错误'))
  } finally {
    creatingTemplate.value = ''
  }
}

const loadRecommendations = async () => {
  if (!selectedDatasetId.value) return
  recommendLoading.value = true
  recommendError.value = ''
  recommendNotice.value = ''
  recommendations.value = []
  try {
    const res = await recommendCharts(selectedDatasetId.value)
    recommendations.value = Array.isArray(res?.items) ? res.items : []
    recommendNotice.value = res?.notice || ''
    if (!recommendations.value.length) {
      recommendError.value = '推荐不可用：当前数据集暂无推荐结果'
    }
  } catch (e) {
    recommendError.value = '推荐不可用'
  } finally {
    recommendLoading.value = false
  }
}

const confidenceLabel = (level) =>
  ({ high: '强烈推荐', medium: '推荐', low: '可选' }[level] || '可选')

const goAnalysis = () => {
  if (!selectedDatasetId.value) {
    ElMessage.info('请先选择数据集')
    return
  }
  router.push(`/analysis/${selectedDatasetId.value}`)
  ElMessage.info('请按推荐配置生成图表并保存图表视图')
}

const removeDashboard = async (dashboard) => {
  try {
    await ElMessageBox.confirm(
      `确定删除看板「${dashboard.title}」及其 ${dashboard.itemCount || 0} 个图表配置？此操作不可恢复。`,
      '删除确认',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteDashboard(dashboard.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error('删除失败：' + (e?.message || '未知错误'))
  }
}

const openDashboard = (id) => router.push(`/dashboard/${id}`)

const formatDateTime = (value) => {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN')
}

onMounted(() => {
  load()
  loadDatasets()
})
</script>

<style scoped>
.tpl-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 12px;
}
.tpl-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--dc-border);
  border-radius: 10px;
  background: #fff;
}
.tpl-icon {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--dc-brand);
  background: var(--dc-brand-soft);
}
.tpl-title {
  font-size: 14px;
  font-weight: 650;
  color: var(--dc-text);
}
.tpl-card p {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--dc-text-3);
  min-height: 40px;
}
.tpl-card :deep(.el-button) {
  margin-top: auto;
  align-self: flex-start;
}
.rec-toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}
.rec-error {
  font-size: 13px;
  color: var(--dc-warning);
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 8px;
  padding: 10px 12px;
}
.rec-hint {
  font-size: 12.5px;
  padding: 6px 0;
}
.rec-item {
  cursor: pointer;
}
.rec-item:hover .dc-list-title {
  color: var(--dc-brand);
}
.rec-note {
  font-size: 12px;
  color: #92400e;
  margin: 6px 0 10px;
}
.dash-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}
.dash-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--dc-border);
  border-radius: 10px;
  background: #fff;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}
.dash-card:hover {
  border-color: #c7cdf8;
  box-shadow: 0 8px 20px -14px rgba(79, 70, 229, 0.5);
}
.dash-cover {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px dashed var(--dc-border);
  border-radius: 8px;
  background: #f8fafc;
  color: var(--dc-brand);
}
.dash-cover-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11.5px;
  color: var(--dc-text-3);
}
.dash-title {
  font-size: 14.5px;
  font-weight: 650;
  color: var(--dc-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dash-desc {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--dc-text-3);
  min-height: 20px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.dash-actions {
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px dashed var(--dc-border);
}
</style>
