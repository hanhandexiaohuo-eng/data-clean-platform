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
