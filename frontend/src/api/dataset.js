import request from '@/utils/request'

export function getDatasetList(params) {
  return request({
    url: '/api/dataset',
    method: 'get',
    params,
  })
}

export function getDatasetById(id) {
  return request({
    url: `/api/dataset/${id}`,
    method: 'get',
  })
}

export function uploadFile(data) {
  return request({
    url: '/api/dataset/upload',
    method: 'post',
    data,
  })
}

export function previewData(id, params) {
  return request({
    url: `/api/dataset/${id}/preview`,
    method: 'get',
    params,
  })
}

export function getColumns(id) {
  return request({
    url: `/api/dataset/${id}/columns`,
    method: 'get',
  })
}

export function getDatasetStatistics(id) {
  return request({
    url: `/api/dataset/${id}/statistics`,
    method: 'get',
  })
}

export function deleteDataset(id) {
  return request({
    url: `/api/dataset/${id}`,
    method: 'delete',
  })
}

/** 导出 CSV：需 X-Confirm 二次确认头 */
export function exportDataset(id) {
  return request({
    url: `/api/export/dataset/${id}`,
    method: 'post',
    headers: { 'X-Confirm': 'yes' },
    responseType: 'blob',
  })
}

/** 版本列表（含当前版本指针） */
export function getVersions(datasetId) {
  return request({
    url: `/api/dataset/${datasetId}/versions`,
    method: 'get',
  })
}

/** 恢复历史版本：破坏性操作，必须 confirm=true */
export function restoreVersion(datasetId, versionId) {
  return request({
    url: `/api/dataset/${datasetId}/versions/${versionId}/restore`,
    method: 'post',
    params: { confirm: true },
  })
}

/** 删除历史版本：破坏性操作，必须 confirm=true */
export function deleteVersion(datasetId, versionId) {
  return request({
    url: `/api/dataset/${datasetId}/versions/${versionId}`,
    method: 'delete',
    params: { confirm: true },
  })
}
