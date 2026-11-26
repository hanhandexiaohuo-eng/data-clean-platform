import request from '@/utils/request'

/**
 * 用户登录
 */
export function login(data) {
  return request({
    url: '/api/auth/login',
    method: 'post',
    data,
  })
}

/**
 * 用户注册
 */
export function register(data) {
  return request({
    url: '/api/auth/register',
    method: 'post',
    data,
  })
}

/**
 * 获取当前用户信息
 */
export function getUserInfo() {
  return request({
    url: '/api/auth/info',
    method: 'get',
  })
}
