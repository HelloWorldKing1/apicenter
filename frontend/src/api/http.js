import axios from 'axios'
import { ElMessage } from 'element-plus'

// 统一 HTTP 封装:前缀 /api/admin、统一信封 {code, msg, data} 解包、错误提示。
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
    const msg = err.response?.data?.msg || err.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default http
