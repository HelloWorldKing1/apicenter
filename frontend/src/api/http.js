import axios from 'axios'
import { ElMessage } from 'element-plus'
import { authStore } from '@/utils/auth.mjs'

// 统一 HTTP 封装:前缀 /api/admin、统一信封 {code, msg, data} 解包、错误提示、
// 管理面令牌（2026-09-18）：请求自动带 Authorization: Bearer <token>；401 → 清登录态并回登录页。
// code=0 成功返回 data;非 0 抛错并提示 msg(设计 §6.2 统一信封)。
const http = axios.create({
  baseURL: '/api/admin',
  timeout: 10000
})

/**
 * 长耗时端点的单请求超时（2026-09-12）：
 * 接口自测 `POST /interfaces/{id}/test` 会走完整链路（读超时可达 3s × 最大重试 4 次 + 退避），
 * 全局 10s 会在后端仍在执行时先报「网络错误」。调用方显式传 `LONG_RUNNING_TIMEOUT` 覆盖。
 */
export const LONG_RUNNING_TIMEOUT = 30000

/** 登录相关端点自身返回 401 属正常业务（密码错/锁定），不应触发「回登录页」跳转 */
function isAuthEndpoint(url) {
  return typeof url === 'string' && url.includes('/auth/')
}

/** 会话失效：清登录态 + 整页跳登录页并记住来源（整页跳转而非 router，避免 http ↔ router 循环依赖） */
function redirectToLogin() {
  authStore.clear()
  if (typeof window === 'undefined') {
    return
  }
  const current = window.location.pathname + window.location.search
  const target = current.startsWith('/login') ? '/login' : `/login?redirect=${encodeURIComponent(current)}`
  window.location.replace(target)
}

http.interceptors.request.use((config) => {
  const token = authStore.getToken()
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    return body
  },
  (err) => {
    const status = err.response?.status
    const code = err.response?.data?.code
    const msg = err.response?.data?.msg || err.message || '网络错误'
    if ((status === 401 || code === 40104) && !isAuthEndpoint(err.config?.url)) {
      ElMessage.error(msg || '登录已过期，请重新登录')
      redirectToLogin()
    } else {
      ElMessage.error(msg)
    }
    return Promise.reject(err)
  }
)

export default http
