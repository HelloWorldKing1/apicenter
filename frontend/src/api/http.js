import axios from 'axios'
import { ElMessage } from 'element-plus'

// 统一 HTTP 封装:前缀 /api/admin、统一信封 {code, msg, data} 解包、错误提示。
// code=0 成功返回 data;非 0 抛错并提示 msg(设计 §6.2 统一信封)。
const http = axios.create({
  baseURL: '/api/admin',
  timeout: 10000
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
    const msg = err.response?.data?.msg || err.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default http
