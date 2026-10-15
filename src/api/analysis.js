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
