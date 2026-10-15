<template>
  <el-card class="ai-panel" shadow="never">
    <template #header>
      <div class="ai-panel-header">
        <div class="ai-title">
          <el-icon :size="18" color="#667eea"><ChatDotRound /></el-icon>
          <span>{{ title }}</span>
          <el-tag v-if="loading" type="primary" size="small" effect="dark">
            <el-icon class="is-loading"><Loading /></el-icon>生成中
          </el-tag>
        </div>
        <div class="ai-actions">
          <el-button type="primary" link size="small" @click="$emit('regenerate')" :loading="loading">
            <el-icon><Refresh /></el-icon>重新生成
          </el-button>
          <el-button type="info" link size="small" @click="collapsed = !collapsed">
            <el-icon><component :is="collapsed ? ArrowDown : ArrowUp" /></el-icon>
          </el-button>
        </div>
      </div>
    </template>
    <el-collapse-transition>
      <div v-show="!collapsed">
        <div class="ai-panel-content markdown-body" v-if="content" v-html="renderedContent"></div>
        <el-skeleton :rows="6" animated v-else />
      </div>
    </el-collapse-transition>
  </el-card>
</template>

<script setup>
import { ref, computed } from 'vue'
import { marked } from 'marked'
import { ArrowDown, ArrowUp } from '@element-plus/icons-vue'

const props = defineProps({
  title: {
    type: String,
    default: 'AI 分析',
  },
  content: {
    type: String,
    default: '',
  },
  loading: {
    type: Boolean,
    default: false,
  },
})

defineEmits(['regenerate'])

const collapsed = ref(false)
const renderedContent = computed(() => {
  return props.content ? marked.parse(props.content) : ''
})
</script>

<style scoped>
.ai-panel {
  border-radius: 12px;
  border: 1px solid #e0d4fc;
  background: linear-gradient(135deg, #faf7ff 0%, #f0f5ff 100%);
}

.ai-panel-header {
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

.ai-panel-content {
  max-height: 500px;
  overflow-y: auto;
  padding: 8px;
  min-height: 100px;
}
</style>
