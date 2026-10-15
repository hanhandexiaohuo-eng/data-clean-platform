import request from '@/utils/request'

export function getCleanRules(datasetId) {
  return request({
    url: '/api/clean/rule',
    method: 'get',
    params: { datasetId },
  })
}

export function createRule(data) {
  return request({
    url: '/api/clean/rule',
    method: 'post',
    data,
  })
}

export function updateRule(id, data) {
  return request({
    url: `/api/clean/rule/${id}`,
    method: 'put',
    data,
  })
}

export function deleteRule(id) {
  return request({
    url: `/api/clean/rule/${id}`,
    method: 'delete',
  })
}

export function executeClean(data) {
  return request({
    url: '/api/clean/execute',
    method: 'post',
    data,
  })
}

export function previewClean(data) {
  return request({
    url: '/api/clean/preview',
    method: 'post',
    data,
  })
}

export function rollbackClean(logId) {
  return request({
    url: `/api/clean/rollback/${logId}`,
    method: 'post',
  })
}

export function getCleanLogs(datasetId) {
  return request({
    url: '/api/clean/log',
    method: 'get',
    params: { datasetId },
  })
}
