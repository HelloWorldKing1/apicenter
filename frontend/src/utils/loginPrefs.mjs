/**
 * 登录页的「记住用户名」（2026-09-24）。
 *
 * <p>**为什么只记用户名、不记口令**：管理面令牌与凭证的纪律是「**口令只存摘要、令牌只存哈希**」；
 * 把明文口令放进 `localStorage` 等于把最高权限凭证摊在浏览器里 —— 一次 XSS 即全量泄露，
 * 且同一台电脑的其他用户/扩展也能读到。因此「记住密码」这件事交给**浏览器自带的密码管理器**：
 * 我们只需把表单做对（`el-form` → 真实 `<form>`、输入框有 `name` + `autocomplete`、
 * 按钮 `native-type="submit"` 触发原生 submit 事件），浏览器就会提示「保存密码 / 自动填充」——
 * 它由浏览器加密保管，且能跟随浏览器账号跨设备同步，比自建方案更安全也更好用。
 *
 * <p>本模块只负责**用户名**的持久化（storage 可注入，便于 Node 单测；顶层做能力守卫，保证 SSR 安全）。
 */

export const REMEMBER_KEY = 'apicenter.rememberUsername'
export const REMEMBER_FLAG_KEY = 'apicenter.rememberUsernameOn'

/** 默认勾选「记住用户名」（只存用户名，无敏感信息，默认开启更省事） */
export const REMEMBER_DEFAULT = true

/** 读环境 storage（无 localStorage 的 SSR / Node 环境返回 null） */
function defaultStorage() {
  return typeof localStorage !== 'undefined' ? localStorage : null
}

/** 记住的用户名（未记住时返回空串） */
export function loadRememberedUsername(storage) {
  const s = storage === undefined ? defaultStorage() : storage
  if (!s) {
    return ''
  }
  try {
    return s.getItem(REMEMBER_KEY) || ''
  } catch (e) {
    return ''
  }
}

/** 「记住用户名」是否勾选（没存过则用默认值） */
export function loadRememberFlag(storage) {
  const s = storage === undefined ? defaultStorage() : storage
  if (!s) {
    return REMEMBER_DEFAULT
  }
  try {
    const v = s.getItem(REMEMBER_FLAG_KEY)
    return v === null ? REMEMBER_DEFAULT : v === '1'
  } catch (e) {
    return REMEMBER_DEFAULT
  }
}

/**
 * 保存「记住用户名」选择：
 * 勾选 ⇒ 存用户名；取消 ⇒ **同时清掉旧值**（避免"取消了却还记着"）。
 * `username` 为空串时一律清除（登录页不该记住空值）。
 *
 * @returns {boolean} 最终是否记住了（勾选且用户名非空）
 */
export function saveRememberedUsername(storage, username, remember) {
  const s = storage === undefined ? defaultStorage() : storage
  if (!s) {
    return false
  }
  const name = (username || '').trim()
  try {
    s.setItem(REMEMBER_FLAG_KEY, remember ? '1' : '0')
    if (remember && name) {
      s.setItem(REMEMBER_KEY, name)
      return true
    }
    s.removeItem(REMEMBER_KEY)
    return false
  } catch (e) {
    return false
  }
}
