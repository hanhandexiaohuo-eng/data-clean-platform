import request from '@/utils/request'

export function generateChart(data) {
  return request({
    url: '/api/analysis/chart',
    method: 'post',
    data,
  })
}

export function getStatistics(datasetId) {
  return request({
    url: `/api/analysis/statistics/${datasetId}`,
    method: 'get',
  })
}

export function recommendCharts(datasetId) {
  return request({
    url: '/api/analysis/recommend',
    method: 'get',
    params: { datasetId },
  })
}

export function listChartViews(datasetId) {
  return request({ url: '/api/chart-view', method: 'get', params: { datasetId } })
}

export function getChartView(id) {
  return request({ url: `/api/chart-view/${id}`, method: 'get' })
}

export function createChartView(data) {
  return request({ url: '/api/chart-view', method: 'post', data })
}

export function updateChartView(id, data) {
  return request({ url: `/api/chart-view/${id}`, method: 'put', data })
}

export function deleteChartView(id) {
  return request({
    url: `/api/chart-view/${id}`,
    method: 'delete',
    headers: { 'X-Confirm': 'yes' },
  })
}

export function getChartViewStatus(id) {
  return request({ url: `/api/chart-view/${id}/status`, method: 'get' })
}

/**
 * 生成已保存图表。版本已变化时需要显式传入确认的版本 ID；
 * 后端会以 409 返回状态数据，调用方应展示“数据已更新，需要刷新”。
 */
export function generateSavedChart(id, confirmedVersionId) {
  return request({
    url: `/api/chart-view/${id}/generate`,
    method: 'post',
    headers: confirmedVersionId ? { 'X-Confirm-Version': String(confirmedVersionId) } : {},
  })
}

export function exportSavedChartCsv(id) {
  return request({
    url: `/api/export/chart/${id}`,
    method: 'post',
    headers: { 'X-Confirm': 'yes' },
    responseType: 'blob',
  })
}

/** 服务端渲染 PNG（基于已聚合/截断结果），导出记录进入任务中心 */
export function exportSavedChartPng(id, params) {
  return request({
    url: `/api/export/chart/${id}/png`,
    method: 'post',
    params,
    headers: { 'X-Confirm': 'yes' },
    responseType: 'blob',
    timeout: 120000,
  })
}
