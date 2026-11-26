import { reactive } from 'vue'
import axios from 'axios'

/**
 * 运行模式（public/private）与公开模式限制。
 *
 * 注意：/api/public/config 返回的是裸 JSON（没有 Result{code,...} 包装），
 * 因此必须使用不带响应拦截器的独立 axios 实例，否则会被误判为失败，
 * 导致公开模式被当成私有模式并跳转到登录页。
 */
const rawHttp = axios.create({ baseURL: '', timeout: 10000 })

export const appMode = reactive({
  loaded: false,
  publicMode: false,
  mode: 'private',
  config: {},
})

export async function loadAppMode() {
  try {
    const { data } = await rawHttp.get('/api/public/config')
    appMode.loaded = true
    appMode.publicMode = !!data?.publicMode
    appMode.mode = data?.mode || 'private'
    appMode.config = data || {}
  } catch (e) {
    // 配置接口不可用时按私有模式处理，至少不破坏登录流程
    appMode.loaded = true
    appMode.publicMode = false
    appMode.mode = 'private'
    appMode.config = {}
  }
  return appMode
}

/** 本次浏览器会话在公开工作区中的开始时间（仅用于展示清理倒计时） */
export function workspaceStartedAt() {
  const key = 'dc_workspace_started_at'
  let value = Number(localStorage.getItem(key))
  if (!value || Number.isNaN(value) || value > Date.now()) {
    value = Date.now()
    localStorage.setItem(key, String(value))
  }
  return value
}

/** 公开模式剩余时间（毫秒），按会话 TTL 计算；过期返回 0 */
export function workspaceRemainingMs() {
  const hours = Number(appMode.config?.sessionTtlHours) || 24
  const elapsed = Date.now() - workspaceStartedAt()
  return Math.max(0, hours * 3600 * 1000 - elapsed)
}
