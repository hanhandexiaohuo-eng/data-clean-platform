<template>
  <div class="dc-page" v-loading="loading">
    <div class="dc-page-head">
      <div>
        <h1>数据集管理</h1>
        <p>
          管理数据集的导入、预览、质量与版本
          <span v-if="appMode.publicMode">
            （公开模式：最多 {{ configValue('maxDatasets') }} 个数据集，单文件不超过
            {{ configValue('maxFileSizeMb') }}MB）
          </span>
        </p>
      </div>
      <div class="dc-page-actions">
        <el-button @click="loadData()" :loading="loading">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="primary" @click="openImport">
          <el-icon><Plus /></el-icon>导入数据
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="appMode.publicMode"
      type="warning"
      :closable="false"
      show-icon
      title="公开临时工作区"
      :description="`仅可访问本匿名会话资源；匿名会话数据将在约 ${ttlText} 后到期自动清理。演示数据为只读。`"
    />

    <div class="dc-card">
      <div class="dc-card-head ds-toolbar">
        <div class="dc-card-title">
          <el-icon><FolderOpened /></el-icon>
          数据集
          <span class="dc-muted ds-count">共 {{ filteredList.length }} 个</span>
          <span v-if="datasetTotal > datasetList.length" class="dc-muted ds-count">
            （已加载 {{ datasetList.length }} / {{ datasetTotal }}）
          </span>
        </div>
        <div class="dc-page-actions">
          <el-input
            v-model="keyword"
            placeholder="搜索名称 / 描述 / 文件名"
            clearable
            style="width: 230px"
            @keyup.enter="applySearch"
            @clear="applySearch"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select v-model="sortKey" style="width: 170px">
            <el-option label="创建时间（新→旧）" value="createdAt_desc" />
            <el-option label="创建时间（旧→新）" value="createdAt_asc" />
            <el-option label="行数（多→少）" value="rowCount_desc" />
            <el-option label="行数（少→多）" value="rowCount_asc" />
            <el-option label="名称（A→Z）" value="name_asc" />
          </el-select>
          <el-radio-group v-model="viewMode">
            <el-radio-button label="card">卡片</el-radio-button>
            <el-radio-button label="table">表格</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <!-- 卡片视图 -->
      <div v-if="viewMode === 'card' && filteredList.length" class="ds-card-grid">
        <div v-for="item in pagedList" :key="item.id" class="ds-card">
          <div class="ds-card-top">
            <div class="ds-card-name" :title="item.name">{{ item.name }}</div>
            <div class="dc-tag-row">
              <el-tag size="small" effect="plain" :type="fileTypeTag(item.fileType)">
                {{ (item.fileType || '未知').toUpperCase() }}
              </el-tag>
              <el-tag v-if="item.ownerType === 'demo'" size="small" type="success" effect="dark">演示数据</el-tag>
              <el-tag v-if="item.ownerType === 'anon'" size="small" type="warning" effect="plain">匿名会话资源</el-tag>
              <el-tag v-if="item.status === 0" size="small" type="info" effect="plain">导入中</el-tag>
              <el-tag v-else-if="item.status === 2" size="small" type="danger" effect="plain">导入失败</el-tag>
              <el-tag v-else-if="item.status === 1 && Number(item.rowCount) === 0" size="small" type="warning" effect="plain">空数据集</el-tag>
            </div>
          </div>

          <p class="ds-desc">{{ item.description || '暂无描述' }}</p>

          <div class="ds-meta-grid">
            <div>
              <span>来源文件</span>
              <b :title="item.fileName || ''">{{ item.fileName || '未记录' }}</b>
            </div>
            <div>
              <span>数据版本</span>
              <b>{{ item.currentVersionId ? `#${item.currentVersionId}` : '未生成' }}</b>
            </div>
            <div>
              <span>行列数</span>
              <b>{{ formatNumber(item.rowCount) }} 行 × {{ item.columnCount ?? '未采集' }} 列</b>
            </div>
            <div>
              <span>创建时间</span>
              <b>{{ formatDateTime(item.createdAt) }}</b>
            </div>
            <div>
              <span>质量摘要</span>
              <b>{{ qualityText(item) }}</b>
            </div>
            <div>
              <span>当前任务</span>
              <b>{{ jobText(item) }}</b>
            </div>
          </div>

          <div v-if="appMode.publicMode && item.ownerType === 'anon'" class="ds-note">
            到期自动清理（约 {{ ttlText }}）
          </div>

          <div class="ds-actions">
            <el-tooltip :content="previewTip(item)">
              <span>
                <el-button text size="small" type="primary" :disabled="item.status !== 1" @click="goToPreview(item.id)">
                  <el-icon><View /></el-icon>预览
                </el-button>
              </span>
            </el-tooltip>
            <el-button text size="small" type="primary" @click="openFields(item)">字段</el-button>
            <el-button text size="small" type="info" @click="openQuality(item)">质量</el-button>
            <el-tooltip :content="cleanTip(item)">
              <span>
                <el-button text size="small" type="success" :disabled="!canClean(item)" @click="goToClean(item.id)">
                  <el-icon><Brush /></el-icon>清洗
                </el-button>
              </span>
            </el-tooltip>
            <el-tooltip :content="item.status === 1 ? '可视化分析' : '数据未就绪，暂不可分析'">
              <span>
                <el-button text size="small" type="warning" :disabled="item.status !== 1" @click="goToAnalysis(item.id)">
                  <el-icon><DataAnalysis /></el-icon>分析
                </el-button>
              </span>
            </el-tooltip>
            <el-tooltip :content="exportTip(item)">
              <span>
                <el-button text size="small" type="info" :loading="exportingId === item.id" :disabled="!canExport(item)" @click="handleExport(item)">
                  <el-icon><Download /></el-icon>导出
                </el-button>
              </span>
            </el-tooltip>
            <el-tooltip :content="item.ownerType === 'demo' ? '演示数据为只读' : '查看并恢复历史版本'">
              <span>
                <el-button text size="small" type="primary" :disabled="item.ownerType === 'demo'" @click="openVersions(item)">
                  <el-icon><Clock /></el-icon>版本
                </el-button>
              </span>
            </el-tooltip>
            <el-tooltip :content="deleteTip(item)">
              <span>
                <el-button text size="small" type="danger" :disabled="item.ownerType === 'demo'" @click="handleDelete(item)">
                  <el-icon><Delete /></el-icon>删除
                </el-button>
              </span>
            </el-tooltip>
          </div>
        </div>
      </div>

      <!-- 表格视图 -->
      <el-table v-else-if="viewMode === 'table' && filteredList.length" :data="pagedList" size="default">
        <el-table-column label="名称" min-width="190">
          <template #default="{ row }">
            <div class="ds-table-name" :title="row.name">{{ row.name }}</div>
            <div class="dc-tag-row" style="margin-top: 4px">
              <el-tag v-if="row.ownerType === 'demo'" size="small" type="success" effect="dark">演示数据</el-tag>
              <el-tag v-if="row.ownerType === 'anon'" size="small" type="warning" effect="plain">匿名会话资源</el-tag>
              <el-tag v-if="row.status === 0" size="small" type="info" effect="plain">导入中</el-tag>
              <el-tag v-else-if="row.status === 1 && Number(row.rowCount) === 0" size="small" type="warning" effect="plain">空数据集</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="来源文件" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.fileName || '未记录' }}</template>
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ (row.fileType || '未知').toUpperCase() }}</template>
        </el-table-column>
        <el-table-column label="行列数" width="140">
          <template #default="{ row }">{{ formatNumber(row.rowCount) }} × {{ row.columnCount ?? '未采集' }}</template>
        </el-table-column>
        <el-table-column label="数据版本" width="110">
          <template #default="{ row }">{{ row.currentVersionId ? `#${row.currentVersionId}` : '未生成' }}</template>
        </el-table-column>
        <el-table-column label="质量摘要" min-width="150">
          <template #default="{ row }">{{ qualityText(row) }}</template>
        </el-table-column>
        <el-table-column label="当前任务" min-width="150">
          <template #default="{ row }">{{ jobText(row) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="row.status !== 1" @click="goToPreview(row.id)">预览</el-button>
            <el-button link type="primary" size="small" @click="openFields(row)">字段</el-button>
            <el-button link type="info" size="small" @click="openQuality(row)">质量</el-button>
            <el-button link type="success" size="small" :disabled="!canClean(row)" :title="cleanTip(row)" @click="goToClean(row.id)">清洗</el-button>
            <el-button link type="warning" size="small" :disabled="row.status !== 1" @click="goToAnalysis(row.id)">分析</el-button>
            <el-button link type="primary" size="small" :disabled="row.ownerType === 'demo'" @click="openVersions(row)">版本</el-button>
            <el-button link type="danger" size="small" :disabled="row.ownerType === 'demo'" :title="deleteTip(row)" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-else-if="!loading" class="dc-empty">
        <el-icon class="dc-empty-icon"><FolderOpened /></el-icon>
        <h4>{{ keyword ? '没有匹配的数据集' : '还没有数据集' }}</h4>
        <p>
          {{ keyword ? '可以调整搜索关键词后重试。' : '导入 CSV / Excel 文件开始使用；所有数据仅在当前工作区可见。' }}
        </p>
        <div class="dc-page-actions" style="justify-content: center">
          <el-button type="primary" @click="openImport">
            <el-icon><Plus /></el-icon>导入数据
          </el-button>
          <el-button v-if="!keyword && demoDataEnabled" @click="handleUseDemo">使用演示数据</el-button>
        </div>
        <p v-if="!keyword && demoDataEnabled" class="dc-muted ds-demo-hint">
          演示数据由管理员预先准备且为只读，不会自动生成。
        </p>
      </div>

      <div v-if="filteredList.length" class="ds-pagination">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[9, 18, 36]"
          :total="filteredList.length"
          layout="total, sizes, prev, pager, next"
          @size-change="handleSizeChange"
        />
      </div>
    </div>

    <!-- 步骤式导入 -->
    <el-dialog v-model="importVisible" title="导入数据" width="760px" :close-on-click-modal="false">
      <el-steps :active="importStep" finish-status="success" simple>
        <el-step title="选择文件" />
        <el-step title="格式校验" />
        <el-step title="解析选项" />
        <el-step title="字段预览" />
        <el-step title="导入结果" />
      </el-steps>

      <div class="import-body">
        <el-form v-if="importStep === 0" :model="importForm" label-width="100px">
          <el-form-item label="数据集名称" required>
            <el-input v-model="importForm.name" placeholder="请输入数据集名称" maxlength="100" show-word-limit />
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="importForm.description" type="textarea" :rows="2" placeholder="可选" maxlength="200" show-word-limit />
          </el-form-item>
          <el-form-item label="文件" required>
            <el-upload
              class="upload-area"
              drag
              action="#"
              :auto-upload="false"
              :show-file-list="false"
              accept=".csv,.xls,.xlsx"
              :on-change="handleFileChange"
            >
              <el-icon class="el-icon--upload" :size="44"><UploadFilled /></el-icon>
              <div class="el-upload__text">拖拽文件到此处或 <em>点击选择</em></div>
              <template #tip>
                <div class="el-upload__tip">
                  支持 .csv / .xls / .xlsx；前端仅做扩展名与大小提示，真实校验以服务端为准。
                </div>
              </template>
            </el-upload>
            <div v-if="importForm.file" class="ds-file-picked">
              已选择：<b>{{ importForm.file.name }}</b>
              <span class="dc-muted">（{{ formatBytes(importForm.file.size) }}）</span>
            </div>
          </el-form-item>
        </el-form>

        <div v-else-if="importStep === 1" class="check-list">
          <div class="check-item">
            <el-icon :class="extOk ? 'is-ok' : 'is-warn'">
              <CircleCheck v-if="extOk" />
              <Warning v-else />
            </el-icon>
            <div>
              <b>扩展名检查</b>
              <p>{{ extMessage }}</p>
            </div>
          </div>
          <div class="check-item">
            <el-icon :class="sizeOk ? 'is-ok' : 'is-warn'">
              <CircleCheck v-if="sizeOk" />
              <Warning v-else />
            </el-icon>
            <div>
              <b>文件大小检查</b>
              <p>{{ sizeMessage }}</p>
            </div>
          </div>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="前端校验仅作提示"
            description="文件格式、编码、恶意文件扫描与配额均由服务端最终判定；即使前端提示通过，也可能在导入阶段被拒绝。"
          />
        </div>

        <div v-else-if="importStep === 2">
          <el-form label-width="110px">
            <el-form-item label="包含表头">
              <el-switch v-model="importForm.hasHeader" active-text="是" inactive-text="否" />
            </el-form-item>
            <el-form-item label="编码格式">
              <el-select v-model="importForm.encoding" style="width: 200px">
                <el-option label="UTF-8" value="UTF-8" />
                <el-option label="GBK" value="GBK" />
                <el-option label="GB2312" value="GB2312" />
              </el-select>
            </el-form-item>
            <el-form-item label="工作表">
              <span class="dc-muted">由服务端自动识别（当前未接入工作表选择）</span>
            </el-form-item>
          </el-form>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="工作表由服务端自动识别"
            description="如需指定工作表，请等待后续版本接入；此处不展示未读取到的表头或工作表名称。"
          />
        </div>

        <div v-else-if="importStep === 3">
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="字段类型将在导入后自动识别"
            description="上传成功前无法获取真实字段列表与类型，因此这里不展示模拟预览。导入完成后可在“预览”页查看字段类型、空值率与统计信息。"
          />
          <div class="dc-divider" />
          <div class="dc-list">
            <div class="dc-list-item">
              <div class="dc-list-main">
                <div class="dc-list-title">当前导入参数</div>
                <div class="dc-list-meta">
                  文件名：{{ importForm.file?.name || '未选择' }} ·
                  表头：{{ importForm.hasHeader ? '包含' : '不包含' }} ·
                  编码：{{ importForm.encoding }}
                </div>
              </div>
            </div>
          </div>
        </div>

        <div v-else class="import-result">
          <div v-if="importing" class="import-progress">
            <div class="dc-notice">
              <el-icon class="is-loading" :size="18"><Loading /></el-icon>
              正在上传并解析，请勿关闭窗口…
            </div>
            <el-progress :percentage="60" :indeterminate="true" :duration="2" :show-text="false" />
          </div>

          <el-alert
            v-else-if="importError"
            type="error"
            :closable="false"
            show-icon
            title="导入失败"
            :description="importError"
          />

          <template v-else-if="importResult">
            <el-alert
              v-if="importResult.jobId"
              type="success"
              :closable="false"
              show-icon
              title="已提交后台导入"
              :description="`任务 #${importResult.jobId} 已创建，可在任务中心查看进度；数据集 #${importResult.datasetId} 为导入占位记录。`"
            />
            <el-alert
              v-else
              type="success"
              :closable="false"
              show-icon
              title="导入成功"
              :description="`数据集 #${importResult.id} 已创建，可在预览页查看数据与字段信息。`"
            />
            <div class="dc-divider" />
            <div class="dc-list">
              <div class="dc-list-item">
                <div class="dc-list-main">
                  <div class="dc-list-title">{{ importResult.jobId ? '后台导入中' : importResult.name }}</div>
                  <div class="dc-list-meta">
                    <template v-if="importResult.jobId">字段与行列数将在后台导入完成后可查看</template>
                    <template v-else>
                      {{ importResult.rowCount?.toLocaleString?.() ?? '未采集' }} 行 ×
                      {{ importResult.columnCount ?? '未采集' }} 列 ·
                      {{ (importResult.fileType || '未知').toUpperCase() }}
                    </template>
                  </div>
                </div>
              </div>
            </div>
          </template>
        </div>
      </div>

      <template #footer>
        <el-button v-if="importStep > 0 && importStep < 4" @click="importStep--">上一步</el-button>
        <el-button v-if="importStep < 4" @click="importVisible = false">取消</el-button>
        <el-button v-if="importStep < 3" type="primary" @click="nextImportStep">下一步</el-button>
        <el-button v-if="importStep === 3" type="primary" @click="startImport">开始导入</el-button>
        <template v-if="importStep === 4">
          <el-button v-if="importError" type="primary" :loading="importing" @click="doUpload">重试导入</el-button>
          <el-button v-if="importResult?.jobId" type="primary" @click="goTasks">前往任务中心</el-button>
          <el-button v-else-if="importResult?.id" type="primary" @click="goPreviewResult">预览数据</el-button>
          <el-button @click="importVisible = false">完成</el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 字段信息 -->
    <el-dialog v-model="fieldDialogVisible" :title="`字段信息 · ${fieldDataset?.name || ''}`" width="720px">
      <el-table :data="fieldColumns" v-loading="fieldLoading" empty-text="暂无字段信息">
        <el-table-column prop="sortOrder" label="#" width="60" />
        <el-table-column prop="columnName" label="字段名" min-width="140" />
        <el-table-column prop="columnAlias" label="显示名" min-width="140" />
        <el-table-column prop="dataType" label="数据类型" width="110" />
        <el-table-column label="可空" width="80">
          <template #default="{ row }">
            {{ row.nullable === 1 ? '是' : row.nullable === 0 ? '否' : '未采集' }}
          </template>
        </el-table-column>
        <el-table-column label="唯一值" width="90">
          <template #default="{ row }">{{ row.uniqueCount ?? '未采集' }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 质量摘要 -->
    <el-dialog v-model="qualityDialogVisible" :title="`质量摘要 · ${qualityDataset?.name || ''}`" width="760px">
      <div v-loading="qualityLoading" class="quality-body">
        <el-alert
          v-if="qualityError"
          type="warning"
          :closable="false"
          show-icon
          :title="qualityError"
          description="统计可能仍在计算，可稍后重试；此处不展示任何模拟数据。"
        />
        <template v-else-if="qualityStats">
          <div class="dc-notice">
            <span>行数：{{ formatNumber(qualityStats.rowCount) }}</span>
            <span>字段：{{ qualityStats.columnCount ?? qualityStats.columns?.length ?? '未采集' }}</span>
            <el-tag v-if="qualityStats.approximate" size="small" type="info" effect="plain">
              抽样 {{ formatNumber(qualityStats.sampleRows) }} 行
            </el-tag>
          </div>
          <el-table :data="qualityStats.columns || []" empty-text="暂无字段统计" style="margin-top: 12px">
            <el-table-column prop="columnName" label="字段" min-width="130" />
            <el-table-column prop="dataType" label="类型" width="100" />
            <el-table-column label="空值数" width="90">
              <template #default="{ row }">{{ formatNumber(row.nullCount) }}</template>
            </el-table-column>
            <el-table-column prop="nullRate" label="空值率" width="90" />
            <el-table-column label="唯一值" width="90">
              <template #default="{ row }">{{ formatNumber(row.uniqueCount) }}</template>
            </el-table-column>
            <el-table-column label="范围" min-width="150">
              <template #default="{ row }">{{ row.min ?? '—' }} ~ {{ row.max ?? '—' }}</template>
            </el-table-column>
          </el-table>
        </template>
        <div v-else class="dc-muted">正在读取统计…</div>
      </div>
    </el-dialog>

    <!-- 版本历史 -->
    <el-dialog v-model="showVersionDialog" title="版本历史" width="760px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="恢复版本会切换当前数据指针，不覆盖任何历史版本"
        description="清洗每次成功都会生成新版本；可随时切换到任一历史版本。"
        style="margin-bottom: 12px"
      />
      <el-table :data="versions" v-loading="versionLoading" empty-text="暂无版本记录">
        <el-table-column prop="versionNo" label="版本" width="70" />
        <el-table-column label="来源" width="100">
          <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.id === currentVersionId ? 'success' : 'info'">
              {{ row.id === currentVersionId ? '当前版本' : row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rowCount" label="行数" width="90" />
        <el-table-column prop="columnCount" label="列数" width="70" />
        <el-table-column prop="createdAt" label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button
              v-if="row.id !== currentVersionId && row.status !== 'BUILDING'"
              type="primary"
              link
              size="small"
              @click="handleRestore(row)"
            >恢复</el-button>
            <el-button
              v-if="row.id !== currentVersionId && row.status !== 'BUILDING'"
              type="danger"
              link
              size="small"
              @click="handleDeleteVersion(row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Brush,
  CircleCheck,
  Clock,
  DataAnalysis,
  Delete,
  Download,
  FolderOpened,
  Loading,
  Plus,
  Refresh,
  Search,
  UploadFilled,
  View,
  Warning,
} from '@element-plus/icons-vue'
import {
  getDatasetList,
  uploadFile,
  getColumns,
  getDatasetStatistics,
  deleteDataset,
  exportDataset,
  getVersions,
  restoreVersion,
  deleteVersion,
} from '@/api/dataset'
import { getJobsByDataset } from '@/api/job'
import { appMode } from '@/stores/appMode'

const router = useRouter()

const loading = ref(false)
const datasetList = ref([])
const datasetTotal = ref(0)
const keyword = ref('')
const appliedKeyword = ref('')
const sortKey = ref('createdAt_desc')
const viewMode = ref('card')
const page = ref(1)
const pageSize = ref(9)
let statusTimer = null

const statsMap = reactive({})
const jobsMap = reactive({})
let enrichToken = 0

const importVisible = ref(false)
const importStep = ref(0)
const importing = ref(false)
const exportingId = ref(null)
const importResult = ref(null)
const importError = ref('')
const importForm = ref({
  name: '',
  description: '',
  file: null,
  hasHeader: true,
  encoding: 'UTF-8',
})

const showVersionDialog = ref(false)
const versionLoading = ref(false)
const versions = ref([])
const currentVersionId = ref(null)
const currentDatasetId = ref(null)

const fieldDialogVisible = ref(false)
const fieldLoading = ref(false)
const fieldColumns = ref([])
const fieldDataset = ref(null)

const qualityDialogVisible = ref(false)
const qualityLoading = ref(false)
const qualityStats = ref(null)
const qualityError = ref('')
const qualityDataset = ref(null)

const demoDataEnabled = computed(() => appMode.publicMode && appMode.config?.demoDataEnabled === true)
const ttlText = computed(() => {
  const hours = appMode.config?.sessionTtlHours
  return hours === undefined || hours === null ? '未采集' : `${hours} 小时`
})

const configValue = (key) => {
  const value = appMode.config?.[key]
  return value === undefined || value === null ? '未采集' : value
}

const filteredList = computed(() => {
  let list = datasetList.value.slice()
  const kw = appliedKeyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter((item) =>
      [item.name, item.description, item.fileName].some((v) =>
        String(v || '').toLowerCase().includes(kw)
      )
    )
  }
  const [field, direction] = sortKey.value.split('_')
  const factor = direction === 'asc' ? 1 : -1
  list.sort((a, b) => {
    if (field === 'rowCount') {
      return (Number(a.rowCount) || 0) > (Number(b.rowCount) || 0) ? factor : (Number(a.rowCount) || 0) < (Number(b.rowCount) || 0) ? -factor : 0
    }
    if (field === 'name') {
      return String(a.name || '').localeCompare(String(b.name || ''), 'zh-CN') * factor
    }
    const av = a.createdAt ? new Date(a.createdAt).getTime() : 0
    const bv = b.createdAt ? new Date(b.createdAt).getTime() : 0
    return av > bv ? factor : av < bv ? -factor : 0
  })
  return list
})

const pagedList = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredList.value.slice(start, start + pageSize.value)
})

const loadEnrichment = (list) => {
  const token = ++enrichToken
  list.forEach((item) => {
    const cached = statsMap[item.id]
    if (item.status === 1) {
      if (!cached || cached.versionId !== item.currentVersionId) {
        statsMap[item.id] = { versionId: item.currentVersionId, text: '统计中…' }
        getDatasetStatistics(item.id)
          .then((stats) => {
            if (token !== enrichToken) return
            const columns = Array.isArray(stats?.columns) ? stats.columns : []
            const rows = Number(stats?.rowCount) || 0
            if (!columns.length || rows <= 0) {
              statsMap[item.id] = { versionId: item.currentVersionId, text: '统计未完成' }
              return
            }
            const nullCells = columns.reduce((sum, col) => sum + Number(col.nullCount || 0), 0)
            const rate = (nullCells / (rows * columns.length)) * 100
            statsMap[item.id] = {
              versionId: item.currentVersionId,
              text: `空值率 ${rate.toFixed(2)}%${stats.approximate ? '（近似）' : ''}`,
            }
          })
          .catch(() => {
            if (token === enrichToken) {
              statsMap[item.id] = { versionId: item.currentVersionId, text: '统计未完成' }
            }
          })
      }
    } else {
      statsMap[item.id] = { text: '统计未完成' }
    }

    jobsMap[item.id] = '加载中…'
    getJobsByDataset(item.id)
      .then((jobs) => {
        if (token !== enrichToken) return
        const list = Array.isArray(jobs) ? jobs : []
        if (!list.length) {
          jobsMap[item.id] = '暂无任务'
          return
        }
        const latest = list[0]
        jobsMap[item.id] = `${jobTypeLabel(latest.jobType)} · ${jobStatusLabel(latest.status)}`
      })
      .catch(() => {
        if (token === enrichToken) jobsMap[item.id] = '—'
      })
  })
}

const fetchAllDatasets = async () => {
  const size = 200
  const first = await getDatasetList({ page: 1, size })
  const records = first?.records || []
  const total = Number(first?.total) || records.length
  const pageCount = Math.min(Math.ceil(total / size), 5)
  for (let p = 2; p <= pageCount; p += 1) {
    const res = await getDatasetList({ page: p, size })
    records.push(...(res?.records || []))
  }
  return { records, total }
}

const loadData = async (silent = false) => {
  if (!silent) loading.value = true
  try {
    const { records, total } = await fetchAllDatasets()
    datasetList.value = records
    datasetTotal.value = total
    const maxPage = Math.max(1, Math.ceil(filteredList.value.length / pageSize.value))
    if (page.value > maxPage) page.value = maxPage
    if (statusTimer) clearTimeout(statusTimer)
    if (datasetList.value.some((item) => item.status === 0)) {
      statusTimer = setTimeout(() => loadData(true), 5000)
    }
  } catch (e) {
    // 拦截器已提示后端 message
  } finally {
    if (!silent) loading.value = false
  }
}

watch(pagedList, (list) => loadEnrichment(list), { immediate: true })

const applySearch = () => {
  appliedKeyword.value = keyword.value
  page.value = 1
}

const handleSizeChange = () => {
  page.value = 1
}

const openImport = () => {
  importStep.value = 0
  importing.value = false
  importResult.value = null
  importError.value = ''
  importForm.value = { name: '', description: '', file: null, hasHeader: true, encoding: 'UTF-8' }
  importVisible.value = true
}

const handleFileChange = (file) => {
  importForm.value.file = file?.raw || null
  if (!importForm.value.name && file?.name) {
    importForm.value.name = file.name.replace(/\.[^.]+$/, '')
  }
}

const nextImportStep = () => {
  if (importStep.value === 0) {
    if (!importForm.value.name.trim()) {
      ElMessage.warning('请输入数据集名称')
      return
    }
    if (!importForm.value.file) {
      ElMessage.warning('请选择文件')
      return
    }
  }
  importStep.value += 1
}

const startImport = () => {
  importStep.value = 4
  doUpload()
}

const doUpload = async () => {
  if (importing.value || importResult.value) return
  importing.value = true
  importError.value = ''
  const formData = new FormData()
  formData.append('file', importForm.value.file)
  formData.append('name', importForm.value.name.trim())
  formData.append('description', importForm.value.description || '')
  formData.append('hasHeader', importForm.value.hasHeader)
  formData.append('encoding', importForm.value.encoding)
  try {
    const res = await uploadFile(formData)
    importResult.value = res || {}
    if (res?.jobId) {
      ElMessage.success('文件较大，已提交后台导入，可在任务中心查看进度')
    } else {
      ElMessage.success('导入成功')
    }
    loadData(true)
  } catch (e) {
    importError.value = e?.message || '导入失败，请稍后重试'
  } finally {
    importing.value = false
  }
}

const goTasks = () => router.push('/tasks')
const goPreviewResult = () => {
  if (importResult.value?.id) router.push(`/preview/${importResult.value.id}`)
}

const goToPreview = (id) => router.push(`/preview/${id}`)
const goToClean = (id) => router.push(`/clean/${id}`)
const goToAnalysis = (id) => router.push(`/analysis/${id}`)

const fileTypeTag = (type) => (type === 'csv' ? 'success' : 'warning')
const canClean = (item) => item.ownerType !== 'demo' && item.status === 1
const canExport = (item) => item.ownerType !== 'demo' && item.status === 1

const previewTip = (item) => {
  if (item.status !== 1) return '数据未就绪，暂不可预览'
  return item.ownerType === 'demo' ? '演示数据只读预览' : '预览数据'
}
const cleanTip = (item) => {
  if (item.ownerType === 'demo') return '演示数据只读，禁止清洗'
  if (item.status !== 1) return '数据未就绪，暂不可清洗'
  return '配置清洗规则'
}
const exportTip = (item) => {
  if (item.ownerType === 'demo') return '演示数据只读，禁止导出'
  if (item.status !== 1) return '数据未就绪，暂不可导出'
  return '导出 CSV（需二次确认）'
}
const deleteTip = (item) => {
  if (item.ownerType === 'demo') return '演示数据只读，禁止删除'
  return '删除数据集及其版本、图表与看板引用'
}

const qualityText = (item) => statsMap[item.id]?.text || (item.status === 1 ? '统计中…' : '统计未完成')
const jobText = (item) => jobsMap[item.id] || '—'

const jobTypeLabels = { IMPORT: '导入', CLEAN: '清洗', RESTORE: '恢复' }
const jobStatusLabels = {
  PENDING: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCEL_REQUESTED: '取消中',
  CANCELLED: '已取消',
  RECOVERABLE: '可恢复',
}
const jobTypeLabel = (type) => jobTypeLabels[type] || type || '任务'
const jobStatusLabel = (status) => jobStatusLabels[status] || status || '未知'

const escapeHtml = (value) =>
  String(value ?? '').replace(/[&<>"']/g, (ch) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  }[ch]))

const handleDelete = async (item) => {
  if (item.ownerType === 'demo') {
    ElMessage.warning('演示数据为只读，禁止删除')
    return
  }
  let versionCount = '未采集'
  let jobCount = '未采集'
  try {
    const res = await getVersions(item.id)
    versionCount = Array.isArray(res?.versions) ? res.versions.length : '未采集'
  } catch (e) {
    versionCount = '未采集'
  }
  try {
    const jobs = await getJobsByDataset(item.id)
    jobCount = Array.isArray(jobs) ? jobs.length : '未采集'
  } catch (e) {
    jobCount = '未采集'
  }
  const message = `
    <div style="line-height:1.9">
      <div>确定删除数据集 <b>${escapeHtml(item.name)}</b>？</div>
      <div>影响范围：数据集 1 个 · 版本 ${versionCount} 个 · 关联任务 ${jobCount} 个。</div>
      <div>该数据集保存的图表及其在看板中的卡片将一并移除（历史清洗/任务记录保留）。</div>
      <div style="color:#dc2626">删除后不可恢复。</div>
      ${appMode.publicMode ? '<div>公开模式下仅能删除本匿名会话资源。</div>' : ''}
    </div>`
  try {
    await ElMessageBox.confirm(message, '删除数据集', {
      dangerouslyUseHTMLString: true,
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteDataset(item.id)
    ElMessage.success('删除成功')
    delete statsMap[item.id]
    delete jobsMap[item.id]
    loadData()
  } catch (e) {
    // 拦截器已提示后端 message；此处保持列表不变
  }
}

const handleExport = async (item) => {
  if (!canExport(item) || exportingId.value) return
  exportingId.value = item.id
  try {
    try {
      await ElMessageBox.confirm(
        '导出为 CSV 可能包含数据集内容，请确认已获得授权，且不包含个人隐私数据。',
        '确认导出',
        { confirmButtonText: '确认导出', cancelButtonText: '取消', type: 'warning' }
      )
    } catch {
      return
    }
    try {
      const response = await exportDataset(item.id)
      const truncated =
        response.headers?.get?.('x-export-truncated') === 'true' ||
        response.headers?.['x-export-truncated'] === 'true'
      const blob = new Blob([response.data], { type: 'text/csv;charset=utf-8' })
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `dataset-${item.id}.csv`
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      window.URL.revokeObjectURL(url)
      ElMessage.success(truncated ? '导出完成（超出上限部分已截断）' : '导出完成')
    } catch (e) {
      ElMessage.error(e?.message || '导出失败')
    }
  } finally {
    exportingId.value = null
  }
}

const handleUseDemo = async () => {
  await loadData()
  const hasDemo = datasetList.value.some((item) => item.ownerType === 'demo')
  if (hasDemo) {
    keyword.value = ''
    appliedKeyword.value = ''
    ElMessage.success('已展示可用的演示数据（只读）')
  } else {
    ElMessage.info('当前没有可用的演示数据；演示数据由管理员预先准备，系统不会自动生成')
  }
}

const openFields = async (item) => {
  fieldDataset.value = item
  fieldColumns.value = []
  fieldDialogVisible.value = true
  fieldLoading.value = true
  try {
    const res = await getColumns(item.id)
    fieldColumns.value = Array.isArray(res) ? res : []
  } catch (e) {
    fieldColumns.value = []
  } finally {
    fieldLoading.value = false
  }
}

const openQuality = async (item) => {
  qualityDataset.value = item
  qualityStats.value = null
  qualityError.value = ''
  qualityDialogVisible.value = true
  qualityLoading.value = true
  try {
    const stats = await getDatasetStatistics(item.id)
    if (!Array.isArray(stats?.columns) || stats.columns.length === 0) {
      qualityError.value = '统计未完成'
    } else {
      qualityStats.value = stats
    }
  } catch (e) {
    qualityError.value = '统计未完成'
  } finally {
    qualityLoading.value = false
  }
}

const sourceLabel = (source) =>
  ({ IMPORT: '导入', CLEAN: '清洗', RESTORE: '恢复', BACKFILL: '存量初始化' }[source] || source || '-')

const openVersions = async (item) => {
  currentDatasetId.value = item.id
  showVersionDialog.value = true
  await loadVersions()
}

const loadVersions = async () => {
  versionLoading.value = true
  try {
    const res = await getVersions(currentDatasetId.value)
    versions.value = res?.versions || []
    currentVersionId.value = res?.currentVersionId ?? null
  } catch (e) {
    versions.value = []
  } finally {
    versionLoading.value = false
  }
}

const handleRestore = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定恢复到版本 v${row.versionNo} 吗？当前版本不会被删除，可再次切换回来。`,
      '恢复版本',
      { confirmButtonText: '确认恢复', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await restoreVersion(currentDatasetId.value, row.id)
    ElMessage.success('已提交版本恢复任务，可在任务中心查看进度')
    showVersionDialog.value = false
    loadData()
  } catch (e) {
    // 拦截器已提示
  }
}

const handleDeleteVersion = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定删除版本 v${row.versionNo} 吗？该版本的物理数据将被删除且不可恢复。`,
      '删除版本',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'error' }
    )
  } catch {
    return
  }
  try {
    await deleteVersion(currentDatasetId.value, row.id)
    ElMessage.success('版本已删除')
    loadVersions()
  } catch (e) {
    // 拦截器已提示
  }
}

const fileExt = computed(() => {
  const name = importForm.value.file?.name || ''
  return name.includes('.') ? name.split('.').pop().toLowerCase() : ''
})
const extOk = computed(() => ['csv', 'xls', 'xlsx'].includes(fileExt.value))

const maxFileSizeMb = computed(() => {
  if (!appMode.publicMode) return null
  const value = Number(appMode.config?.maxFileSizeMb)
  return Number.isFinite(value) && value > 0 ? value : null
})

const sizeOk = computed(() => {
  if (!importForm.value.file || !maxFileSizeMb.value) return true
  return importForm.value.file.size <= maxFileSizeMb.value * 1024 * 1024
})

const extMessage = computed(() => {
  if (!importForm.value.file) return '尚未选择文件'
  if (!fileExt.value) return '无法识别扩展名，服务端将再次校验'
  return extOk.value ? `.${fileExt.value} 属于支持的扩展名` : `.${fileExt.value} 不在 .csv / .xls / .xlsx 提示范围内，服务端可能拒绝`
})

const sizeMessage = computed(() => {
  if (!importForm.value.file) return '尚未选择文件'
  const size = formatBytes(importForm.value.file.size)
  if (!maxFileSizeMb.value) return `${size}；私有模式具体上限由服务端校验`
  return sizeOk.value
    ? `${size}，未超过公开模式单文件上限 ${maxFileSizeMb.value}MB`
    : `${size}，超过公开模式单文件上限 ${maxFileSizeMb.value}MB，服务端将拒绝`
})

const formatBytes = (bytes) => {
  const value = Number(bytes)
  if (!Number.isFinite(value) || value < 0) return '未采集'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(2)} MB`
}

const formatNumber = (value) => {
  if (value === null || value === undefined || value === '') return '未采集'
  const num = Number(value)
  return Number.isFinite(num) ? num.toLocaleString('zh-CN') : '未采集'
}

const formatDateTime = (value) => {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN')
}

onMounted(() => loadData())
onUnmounted(() => {
  if (statusTimer) clearTimeout(statusTimer)
  enrichToken += 1
})
</script>

<style scoped>
.ds-toolbar {
  flex-wrap: wrap;
}
.ds-count {
  font-size: 12px;
  font-weight: 400;
}
.ds-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px;
}
.ds-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--dc-border);
  border-radius: 10px;
  background: #fff;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}
.ds-card:hover {
  border-color: #c7cdf8;
  box-shadow: 0 8px 20px -14px rgba(79, 70, 229, 0.5);
}
.ds-card-top {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ds-card-name {
  font-size: 14.5px;
  font-weight: 650;
  color: var(--dc-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ds-desc {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--dc-text-3);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 20px;
}
.ds-meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 12px;
}
.ds-meta-grid span {
  display: block;
  font-size: 11.5px;
  color: var(--dc-text-3);
  margin-bottom: 2px;
}
.ds-meta-grid b {
  display: block;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--dc-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ds-note {
  font-size: 11.5px;
  color: #92400e;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 6px;
  padding: 5px 8px;
}
.ds-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px dashed var(--dc-border);
}
.ds-actions :deep(.el-button) {
  padding: 4px 6px;
}
.ds-table-name {
  font-weight: 600;
  font-size: 13.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ds-pagination {
  display: flex;
  justify-content: center;
  margin-top: 18px;
}
.ds-demo-hint {
  font-size: 12px;
  margin-top: 10px;
}
.import-body {
  min-height: 230px;
  padding-top: 18px;
}
.upload-area {
  width: 100%;
}
.upload-area :deep(.el-upload-dragger) {
  width: 100%;
  padding: 26px 0;
}
.ds-file-picked {
  margin-top: 8px;
  font-size: 12.5px;
  color: var(--dc-text-2);
}
.check-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.check-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--dc-border);
  border-radius: 9px;
}
.check-item b {
  font-size: 13.5px;
}
.check-item p {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--dc-text-3);
  line-height: 1.6;
}
.check-item .is-ok {
  color: var(--dc-success);
  font-size: 20px;
}
.check-item .is-warn {
  color: var(--dc-warning);
  font-size: 20px;
}
.import-progress {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 20px 4px;
}
.import-result {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.quality-body {
  min-height: 120px;
}
@media (max-width: 640px) {
  .ds-card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
