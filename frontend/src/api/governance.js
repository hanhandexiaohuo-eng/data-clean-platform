import request from '@/utils/request'

const confirmHeader = { 'X-Confirm': 'yes' }

export function listProviders() {
  return request({ url: '/api/ai/governance/providers', method: 'get' })
}

export function createProvider(data) {
  return request({ url: '/api/ai/governance/providers', method: 'post', data, headers: confirmHeader })
}

export function updateProvider(data) {
  return request({ url: '/api/ai/governance/providers', method: 'put', data, headers: confirmHeader })
}

export function deleteProvider(id) {
  return request({ url: `/api/ai/governance/providers/${id}`, method: 'delete', headers: confirmHeader })
}

export function listModels() {
  return request({ url: '/api/ai/governance/models', method: 'get' })
}

export function createModel(data) {
  return request({ url: '/api/ai/governance/models', method: 'post', data })
}

export function updateModel(data) {
  return request({ url: '/api/ai/governance/models', method: 'put', data })
}

export function deleteModel(id) {
  return request({ url: `/api/ai/governance/models/${id}`, method: 'delete' })
}

export function listSceneRoutes() {
  return request({ url: '/api/ai/governance/scenes', method: 'get' })
}

export function createSceneRoute(data) {
  return request({ url: '/api/ai/governance/scenes', method: 'post', data })
}

export function updateSceneRoute(data) {
  return request({ url: '/api/ai/governance/scenes', method: 'put', data })
}

export function listCalls(params) {
  return request({ url: '/api/ai/governance/calls', method: 'get', params })
}

export function getUsage(date) {
  return request({ url: '/api/ai/governance/usage', method: 'get', params: { date } })
}

export function listCircuits() {
  return request({ url: '/api/ai/governance/circuits', method: 'get' })
}

export function resetCircuit(id) {
  return request({ url: `/api/ai/governance/circuits/${id}/reset`, method: 'post', headers: confirmHeader })
}
