/**
 * 管理面登录态工具（2026-09-18）：令牌 / 用户信息的存取 + 登录页表单预校验。
 *
 * 设计要点：
 * - **storage 可注入**（`createAuthStore`）：Node 单测里没有 localStorage，用内存实现注入即可完整覆盖；
 * - **SSR 安全**：模块顶层一律做能力守卫（`typeof localStorage !== 'undefined'` / `typeof window`），
 *   否则组件 SSR 冒烟（`npm run test:ssr`）会直接抛「localStorage is not defined」；
 * - 预校验规则与后端 `AuthService` 保持一致（后端仍会再校验，前端只做即时提示）。
 */

const TOKEN_KEY = 'apicenter.token'
const USER_KEY = 'apicenter.user'
const AUTH_ENABLED_KEY = 'apicenter.authEnabled'

/** 内存 storage（Node / 无 localStorage 环境兜底） */
export function memoryStorage() {
  const map = new Map()
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k)
  }
}

/** 令牌/用户存取（storage 可注入，便于单测） */
export function createAuthStore(storage) {
  return {
    getToken() {
      return storage.getItem(TOKEN_KEY) || ''
    },
    setToken(token) {
      if (token) {
        storage.setItem(TOKEN_KEY, token)
      } else {
        storage.removeItem(TOKEN_KEY)
      }
    },
    getUser() {
      const raw = storage.getItem(USER_KEY)
      if (!raw) {
        return null
      }
      try {
        return JSON.parse(raw)
      } catch {
        return null // 脏数据一律当未登录，不抛
      }
    },
    setUser(user) {
      if (user) {
        storage.setItem(USER_KEY, JSON.stringify(user))
      } else {
        storage.removeItem(USER_KEY)
      }
    },
    /** 认证开关缓存（null = 未知）：false 时前端不强制跳登录（后端也未校验） */
    getAuthEnabled() {
      const raw = storage.getItem(AUTH_ENABLED_KEY)
      return raw === null ? null : raw === 'true'
    },
    setAuthEnabled(enabled) {
      storage.setItem(AUTH_ENABLED_KEY, enabled ? 'true' : 'false')
    },
    clear() {
      storage.removeItem(TOKEN_KEY)
      storage.removeItem(USER_KEY)
    },
    /** 展示名：显示名优先，其次用户名 */
    displayName() {
      const u = this.getUser()
      return u ? u.displayName || u.username || '' : ''
    }
  }
}

/** 默认实例：浏览器用 localStorage，其它环境用内存（SSR 冒烟不会污染、也不会抛） */
export const authStore = createAuthStore(
  typeof localStorage !== 'undefined' ? localStorage : memoryStorage()
)

/** 用户名规则（与后端一致）：3-32 位小写字母/数字/_.- 且以字母或数字开头 */
export function usernameIssue(name) {
  const value = (name || '').trim().toLowerCase()
  if (!value) {
    return '请输入用户名'
  }
  if (!/^[a-z0-9][a-z0-9_.-]{2,31}$/.test(value)) {
    return '用户名需 3-32 位小写字母/数字/_.-，且以字母或数字开头'
  }
  return ''
}

/** 口令规则（与后端一致）：8-64 位且同时含字母与数字 */
export function passwordIssue(password) {
  const value = password || ''
  if (!value) {
    return '请输入密码'
  }
  if (value.length < 8 || value.length > 64) {
    return '密码长度需 8-64 位'
  }
  if (!/[A-Za-z]/.test(value) || !/[0-9]/.test(value)) {
    return '密码需同时包含字母与数字'
  }
  return ''
}

/** 注册表单整体预校验 → 第一条错误文案（空串 = 通过） */
export function registerIssue({ username, password, confirm }) {
  return usernameIssue(username) || passwordIssue(password)
    || (password !== confirm ? '两次输入的密码不一致' : '')
}

/**
 * 登录后的跳转目标：只接受**站内相对路径**（防开放重定向 —— `//evil.com` / `http://evil.com` 一律丢弃）。
 * 从 `route.query.redirect` 取值（字符串或数组均兼容）。
 */
export function redirectTarget(redirect) {
  const value = Array.isArray(redirect) ? redirect[0] : redirect
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) {
    return '/dashboard'
  }
  return value.startsWith('/login') ? '/dashboard' : value
}
