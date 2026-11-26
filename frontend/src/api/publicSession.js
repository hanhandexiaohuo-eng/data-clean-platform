import axios from 'axios'

/**
 * 公开模式匿名会话引导。
 *
 * <p>唯一建会话入口是 POST /api/public/session：
 * 已有有效会话时幂等返回；单 IP 当日创建次数达到阈值后返回 428，
 * 需先领取一次性挑战令牌再携带 X-Session-Challenge 重试。
 *
 * <p>这里使用独立 axios 实例，避免与业务拦截器互相触发。
 */
const raw = axios.create({ baseURL: '', timeout: 15000 })

let bootstrapped = false
let inflight = null

/** 确保已建立匿名会话；并发调用共用同一个请求。 */
export function ensurePublicSession(force = false) {
  if (bootstrapped && !force) {
    return Promise.resolve(true)
  }
  if (inflight) {
    return inflight
  }
  inflight = createSession().finally(() => {
    inflight = null
  })
  return inflight
}

async function createSession() {
  try {
    await raw.post('/api/public/session')
    bootstrapped = true
    return true
  } catch (e) {
    if (e.response?.status === 428) {
      return retryWithChallenge()
    }
    bootstrapped = false
    return false
  }
}

async function retryWithChallenge() {
  try {
    const { data } = await raw.post('/api/public/session/challenge')
    await raw.post('/api/public/session', null, {
      headers: { 'X-Session-Challenge': data.token },
    })
    bootstrapped = true
    return true
  } catch (e) {
    bootstrapped = false
    return false
  }
}
