import request from '@/utils/request'

export function getJobs() {
  return request({
    url: '/api/job',
    method: 'get',
  })
}

export function getJob(id) {
  return request({
    url: `/api/job/${id}`,
    method: 'get',
  })
}

export function getJobsByDataset(datasetId) {
  return request({
    url: `/api/job/dataset/${datasetId}`,
    method: 'get',
  })
}

export function cancelJob(id) {
  return request({
    url: `/api/job/${id}/cancel`,
    method: 'post',
  })
}

export function retryJob(id) {
  return request({
    url: `/api/job/${id}/retry`,
    method: 'post',
  })
}
