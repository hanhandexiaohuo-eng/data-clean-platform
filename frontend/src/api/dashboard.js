import request from '@/utils/request'

export function listDashboards() {
  return request({ url: '/api/dashboard', method: 'get' })
}

export function getDashboard(id) {
  return request({ url: `/api/dashboard/${id}`, method: 'get' })
}

export function createDashboard(data) {
  return request({ url: '/api/dashboard', method: 'post', data })
}

export function renameDashboard(id, data) {
  return request({ url: `/api/dashboard/${id}`, method: 'put', data })
}

export function deleteDashboard(id) {
  return request({
    url: `/api/dashboard/${id}`,
    method: 'delete',
    headers: { 'X-Confirm': 'yes' },
  })
}

export function addDashboardItem(id, data) {
  return request({ url: `/api/dashboard/${id}/items`, method: 'post', data })
}

export function updateDashboardItem(id, itemId, data) {
  return request({ url: `/api/dashboard/${id}/items/${itemId}`, method: 'put', data })
}

export function removeDashboardItem(id, itemId) {
  return request({
    url: `/api/dashboard/${id}/items/${itemId}`,
    method: 'delete',
    headers: { 'X-Confirm': 'yes' },
  })
}

export function reorderDashboard(id, itemIds) {
  return request({ url: `/api/dashboard/${id}/order`, method: 'put', data: itemIds })
}
