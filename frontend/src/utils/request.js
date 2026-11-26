import axios from 'axios'
import { ElMessage } from 'element-plus'
import { appMode } from '@/stores/appMode'
import { ensurePublicSession } from '@/api/publicSession'

const request = axios.create({
  baseURL: '',
  timeout: 30000,
})

/** 是否为浏览器 Blob（下载接口的 responseType=blob） */
const isBlobData = (data) => typeof Blob !== 'undefined' && data instanceof Blob

/** 下载接口返回 JSON 说明是错误响应，而不是真实文件 */
const isJsonBlob = (blob) => {
  if (!isBlobData(blob)) return false
  const type = (blob.type || '').toLowerCase()
  return type.includes('application/json') || type.includes('+json')
}

const readBlobJson = async (blob) => {
  try {
    return JSON.parse(await blob.text())
  } catch {
    return null
  }
}

// 请求拦截器：注入 JWT Token
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截器
request.interceptors.response.use(
  async (response) => {
    // 文件下载（blob）：先识别错误响应，避免把 JSON 错误当成 CSV/PNG 下载
    if (response.config?.responseType === 'blob') {
      if (isJsonBlob(response.data)) {
        const payload = await readBlobJson(response.data)
        const message = payload?.message || '导出失败'
        const err = new Error(message)
        err.response = response
        err.code = payload?.code
        err.isBlobError = true
        return Promise.reject(err)
      }
      return response
    }
    const res = response.data
    if (res.code === 401 && !appMode.publicMode) {
      // Token 无效或过期 → 清除登录状态并跳转
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message))
    }
    return res.data
  },
  async (error) => {
    if (error.response?.status === 401 && !appMode.publicMode) {
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    // 公开模式：会话缺失/失效时重新引导一次，成功则重放原请求
    const sessionRequired = error.response?.headers?.['x-session-required'] === '1'
    if (error.response?.status === 401 && appMode.publicMode && sessionRequired && !error.config?.__sessionRetry) {
      error.config.__sessionRetry = true
      return ensurePublicSession(true).then((ok) => {
        if (ok) {
          return request(error.config)
        }
        ElMessage.error('匿名会话初始化失败，请刷新页面重试')
        return Promise.reject(error)
      })
    }
    // 下载接口的错误响应是 Blob 包裹的 JSON：解析后抛出，交给调用方展示
    if (error.config?.responseType === 'blob' && isJsonBlob(error.response?.data)) {
      const payload = await readBlobJson(error.response.data)
      const message = payload?.message || `请求失败（${error.response.status}）`
      error.message = message
      error.code = payload?.code
      error.isBlobError = true
      error.response.data = payload
      return Promise.reject(error)
    }
    // 优先展示后端返回的业务提示，避免只看到 Request failed with status code 400
    const message = error.response?.data?.message || error.message || '网络错误'
    ElMessage.error(message)
    error.message = message
    return Promise.reject(error)
  }
)

export default request
