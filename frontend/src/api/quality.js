import request from '@/utils/request'

/** 读取已采集的质量报表；无记录时 data.status = NOT_COLLECTED */
export function getQualityReport(datasetId, params) {
  return request({
    url: `/api/quality/${datasetId}`,
    method: 'get',
    params,
  })
}

/** 触发真实质量统计（数据库聚合），会写入任务中心 */
export function computeQuality(datasetId, params) {
  return request({
    url: `/api/quality/${datasetId}/compute`,
    method: 'post',
    params,
    timeout: 120000,
  })
}
