import request from '@/utils/request'

export function generate(data) {
  return request({
    url: '/api/ai/generate',
    method: 'post',
    data,
  })
}

export function generateStream(params) {
  return new EventSource(`/api/ai/generate/stream?datasetId=${params.datasetId}&templateKey=${params.templateKey}&contextType=${params.contextType}&customPrompt=${encodeURIComponent(params.customPrompt || '')}&configId=${params.configId || ''}`)
}

/** 公开模式：先领取一次性人机挑战，再携带 X-AI-Challenge 发起同步请求 */
export async function generatePublic(data) {
  const challenge = await request({
    url: '/api/public/ai/challenge',
    method: 'post',
  })
  return request({
    url: '/api/ai/generate',
    method: 'post',
    data,
    headers: { 'X-AI-Challenge': challenge.token },
  })
}

/** 公开模式：当前匿名会话的 AI 剩余额度 */
export function getPublicAiQuota() {
  return request({
    url: '/api/public/ai/quota',
    method: 'get',
  })
}

export function getAiConfigs() {
  return request({
    url: '/api/ai/config',
    method: 'get',
  })
}

export function saveAiConfig(data) {
  return request({
    url: '/api/ai/config',
    method: 'put',
    data,
  })
}

export function deleteAiConfig(id) {
  return request({
    url: `/api/ai/config/${id}`,
    method: 'delete',
  })
}

export function getPromptTemplates() {
  return request({
    url: '/api/ai/template',
    method: 'get',
  })
}

export function savePromptTemplate(data) {
  return request({
    url: '/api/ai/template',
    method: 'put',
    data,
  })
}

export function deletePromptTemplate(id) {
  return request({
    url: `/api/ai/template/${id}`,
    method: 'delete',
  })
}

export function getAiHistory(params) {
  return request({
    url: '/api/ai/history',
    method: 'get',
    params,
  })
}

export function getLatestGeneration(datasetId, contextType) {
  return request({
    url: '/api/ai/history/latest',
    method: 'get',
    params: { datasetId, contextType },
  })
}
