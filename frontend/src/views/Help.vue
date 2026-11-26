<template>
  <div class="dc-page">
    <div class="dc-page-head">
      <div>
        <h1>帮助与示例</h1>
        <p>合成演示场景与流程引导；示例仅说明分析思路，不会自动生成数据</p>
      </div>
      <div class="dc-page-actions">
        <el-button @click="router.push('/datasets')">
          <el-icon><FolderOpened /></el-icon>前往数据集
        </el-button>
      </div>
    </div>

    <div class="dc-card">
      <div class="dc-card-head">
        <div>
          <div class="dc-card-title">
            <el-icon><QuestionFilled /></el-icon>演示场景
          </div>
          <div class="dc-card-sub">以下均为合成的业务场景说明，不包含真实数据，也不代表系统中已存在对应数据集</div>
        </div>
      </div>
      <div class="scenario-grid">
        <div v-for="(scenario, index) in scenarios" :key="scenario.key" class="scenario">
          <div class="scenario-head">
            <div class="scenario-icon">
              <el-icon :size="18"><component :is="scenario.icon" /></el-icon>
            </div>
            <div>
              <div class="scenario-title">{{ scenario.title }}</div>
              <div class="dc-muted scenario-index">场景 {{ index + 1 }}</div>
            </div>
          </div>
          <p class="scenario-desc">{{ scenario.desc }}</p>
          <div class="scenario-block">
            <div class="scenario-label">适用字段</div>
            <div class="dc-tag-row">
              <el-tag v-for="field in scenario.fields" :key="field" size="small" type="info" effect="plain">
                {{ field }}
              </el-tag>
            </div>
          </div>
          <div class="scenario-block">
            <div class="scenario-label">推荐步骤</div>
            <ol class="scenario-steps">
              <li v-for="step in scenario.steps" :key="step">{{ step }}</li>
            </ol>
          </div>
          <el-button plain type="primary" size="small" @click="router.push('/datasets')">
            前往数据集
          </el-button>
        </div>
      </div>
    </div>

    <div class="dc-card">
      <div class="dc-card-head">
        <div>
          <div class="dc-card-title">
            <el-icon><DataAnalysis /></el-icon>从导入到看板
          </div>
          <div class="dc-card-sub">四步完成一次完整的数据分析闭环</div>
        </div>
      </div>
      <div class="flow-grid">
        <div v-for="(step, index) in flowSteps" :key="step.title" class="flow-step">
          <div class="flow-index">{{ index + 1 }}</div>
          <div class="flow-title">{{ step.title }}</div>
          <p>{{ step.desc }}</p>
          <div v-if="step.hint" class="dc-muted flow-hint">{{ step.hint }}</div>
          <el-button text type="primary" size="small" @click="router.push(step.path)">
            {{ step.action }}
            <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
      </div>
    </div>

    <div class="dc-grid-2">
      <div class="dc-card">
        <div class="dc-card-head">
          <div class="dc-card-title">
            <el-icon><Lock /></el-icon>公开临时工作区
          </div>
        </div>

        <template v-if="appMode.publicMode">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="临时资源会到期自动清理"
            description="公开工作区适合体验功能，不用于长期保存数据；请在清理前自行保留必要结果。"
          />
          <div class="limit-grid">
            <div v-for="limit in limits" :key="limit.label" class="limit-item">
              <span>{{ limit.label }}</span>
              <b>{{ limit.value }}</b>
            </div>
          </div>
          <div class="dc-divider" />
          <ul class="privacy-list">
            <li>匿名会话之间相互隔离，无法访问其他会话的数据。</li>
            <li>请勿上传个人隐私、商业秘密或其他敏感数据。</li>
            <li>演示数据为只读样例，不可清洗、修改、删除或导出。</li>
            <li>临时工作区不可恢复，请在会话到期前保存必要结果。</li>
          </ul>
        </template>
        <div v-else class="dc-muted">
          当前为私有工作区，公开临时工作区的会话 TTL 与上传限制未启用。
        </div>
      </div>

      <div class="dc-card">
        <div class="dc-card-head">
          <div class="dc-card-title">
            <el-icon><QuestionFilled /></el-icon>常见问题
          </div>
        </div>
        <el-collapse v-model="faqActive">
          <el-collapse-item v-for="(faq, index) in faqs" :key="index" :title="faq.q" :name="String(index)">
            <p class="faq-answer">{{ faq.a }}</p>
          </el-collapse-item>
        </el-collapse>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  Box,
  ChatDotRound,
  DataAnalysis,
  FolderOpened,
  Histogram,
  Lock,
  QuestionFilled,
  TrendCharts,
} from '@element-plus/icons-vue'
import { appMode } from '@/stores/appMode'

const router = useRouter()
const faqActive = ref(['0'])

const scenarios = [
  {
    key: 'sales',
    title: '销售分析',
    icon: TrendCharts,
    desc: '按时间、区域与品类拆解销售额和订单量，观察增长来源与结构变化。',
    fields: ['订单日期', '区域', '品类', '销售额', '订单量'],
    steps: [
      '导入销售明细，确认日期与金额字段类型正确。',
      '在质量检查中关注日期缺失、金额异常与重复订单。',
      '以日期/区域为维度、销售额为度量生成趋势或对比图。',
      '保存图表视图，再进入看板组合展示。',
    ],
  },
  {
    key: 'operations',
    title: '运营分析',
    icon: DataAnalysis,
    desc: '围绕活跃、留存与渠道来源，观察关键运营指标的周期波动。',
    fields: ['统计日期', '渠道', '新增用户', '活跃用户', '留存率'],
    steps: [
      '导入运营日报或明细数据，确认日期粒度。',
      '检查渠道字段的空值与枚举值是否规范。',
      '生成渠道对比、活跃趋势与留存变化图表。',
      '保存图表视图并加入运营看板。',
    ],
  },
  {
    key: 'survey',
    title: '问卷分析',
    icon: ChatDotRound,
    desc: '统计题目选项分布、评分均值，以及不同人群的作答差异。',
    fields: ['题目', '选项', '评分', '人群标签'],
    steps: [
      '导入问卷结果，确认每列对应一个题目或标签。',
      '检查选项空值与异常评分范围。',
      '使用分布图与分组柱状图对比不同人群。',
      '保存关键题目图表，组合成问卷分析看板。',
    ],
  },
  {
    key: 'inventory',
    title: '库存分析',
    icon: Box,
    desc: '查看库存水位、周转情况与缺货/积压风险，辅助补货判断。',
    fields: ['SKU', '仓库', '库存数量', '安全库存', '更新时间'],
    steps: [
      '导入库存快照，确认 SKU 与数量字段类型。',
      '检查负库存、缺失 SKU 与重复记录。',
      '按仓库/SKU 汇总库存，并与安全库存对比。',
      '保存图表视图，构建库存监控看板。',
    ],
  },
  {
    key: 'quality',
    title: '数据质量治理',
    icon: Histogram,
    desc: '跟踪字段空值率、唯一值、异常分布与清洗前后的版本变化。',
    fields: ['字段名', '空值率', '唯一值', '数据类型', '版本'],
    steps: [
      '导入待治理数据并查看质量摘要。',
      '定位空值率高的字段，配置清洗规则。',
      '执行清洗生成新版本，对比清洗前后统计。',
      '保存质量对比图表，形成治理看板。',
    ],
  },
]

const flowSteps = [
  { title: '导入数据', desc: '上传 CSV / Excel，确认表头与编码，等待解析完成。', path: '/datasets', action: '前往导入' },
  { title: '质量检查', desc: '查看字段类型、空值率与唯一值，识别异常数据。', path: '/quality', action: '打开质量中心' },
  { title: '清洗草稿', desc: '为数据集配置清洗规则并执行，生成新的数据版本。', path: '/datasets', action: '选择数据集', hint: '在数据集卡片上点击“清洗”进入规则配置。' },
  { title: '图表看板', desc: '在分析页生成并保存图表，再组合为看板。', path: '/dashboards', action: '打开看板' },
]

const configText = (key, unit = '') => {
  const value = appMode.config?.[key]
  return value === undefined || value === null ? '未采集' : `${value}${unit}`
}

const limits = computed(() => [
  { label: '数据保留', value: configText('sessionTtlHours', ' 小时') },
  { label: '单文件上限', value: configText('maxFileSizeMb', ' MB') },
  { label: '单数据集行数', value: configText('maxRows', ' 行') },
  { label: '单数据集列数', value: configText('maxColumns', ' 列') },
  { label: '数据集数量', value: configText('maxDatasets', ' 个') },
])

const faqs = [
  {
    q: '公开工作区的数据会保留多久？',
    a: '公开临时工作区会按配置的保留时长自动清理；具体时长以页面顶部或本页显示的会话 TTL 为准。私有工作区的保留策略由管理员管理，前端不展示。',
  },
  {
    q: '为什么演示数据不能清洗、删除或导出？',
    a: '演示数据是人工构造的只读样例，用于体验预览与可视化分析。导入自己的数据后，即可使用清洗、版本恢复与导出等能力。',
  },
  {
    q: '支持哪些文件格式？',
    a: '当前支持 CSV 与 Excel（.xls / .xlsx）。前端只做扩展名与大小提示，真实格式、编码与安全校验以服务端结果为准。',
  },
  {
    q: '如何把图表放到看板里？',
    a: '先在可视化分析页生成并保存图表视图；打开看板详情后，可选择数据集与已保存图表添加，并拖拽排序、调整宽高或导出 PNG。',
  },
  {
    q: '任务失败后如何排查？',
    a: '前往任务中心查看失败任务的失败摘要。为避免暴露内部实现，摘要已对 SQL、堆栈与路径等信息做脱敏处理。',
  },
  {
    q: '可以上传个人隐私或敏感数据吗？',
    a: '不可以。请勿上传个人隐私、商业秘密或其他敏感数据；公开工作区仅用于功能演示与体验。',
  },
]
</script>

<style scoped>
.scenario-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}
.scenario {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--dc-border);
  border-radius: 10px;
  background: #fff;
}
.scenario-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.scenario-icon {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--dc-brand);
  background: var(--dc-brand-soft);
  flex-shrink: 0;
}
.scenario-title {
  font-size: 14px;
  font-weight: 650;
  color: var(--dc-text);
}
.scenario-index {
  font-size: 11.5px;
  margin-top: 1px;
}
.scenario-desc {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--dc-text-2);
}

.scenario-block {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.scenario-label {
  font-size: 11.5px;
  color: var(--dc-text-3);
}
.scenario-steps {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.75;
  color: var(--dc-text-2);
}
.scenario :deep(.el-button) {
  margin-top: auto;
  align-self: flex-start;
}
.flow-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}
.flow-step {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px;
  border: 1px solid var(--dc-border);
  border-radius: 10px;
  background: #fff;
}
.flow-index {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
  color: var(--dc-brand);
  background: var(--dc-brand-soft);
}
.flow-title {
  font-size: 14px;
  font-weight: 650;
  color: var(--dc-text);
}
.flow-step p {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--dc-text-3);
}
.flow-hint {
  font-size: 11.5px;
  background: #f8fafc;
  border-radius: 6px;
  padding: 6px 8px;
}
.flow-step :deep(.el-button) {
  align-self: flex-start;
  padding-left: 0;
}
.limit-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 10px;
  margin-top: 12px;
}
.limit-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px 12px;
  border: 1px solid var(--dc-border);
  border-radius: 8px;
  background: #f8fafc;
}
.limit-item span {
  font-size: 11.5px;
  color: var(--dc-text-3);
}
.limit-item b {
  font-size: 14px;
  color: var(--dc-text);
  font-variant-numeric: tabular-nums;
}
.privacy-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--dc-text-2);
}
.faq-answer {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--dc-text-2);
}
</style>
