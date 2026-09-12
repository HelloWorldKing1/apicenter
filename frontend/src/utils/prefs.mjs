/**
 * 浏览器偏好持久化（localStorage，SSR / 隐私模式下安全降级）。
 * 用途：报文查看器的行号·折行开关、抽屉宽度记忆。
 */
const PREFIX = 'apicenter.'

export function readPref(key, defValue) {
  try {
    const v = localStorage.getItem(PREFIX + key)
    return v == null ? defValue : v
  } catch (e) {
    return defValue
  }
}

/**
 * 布尔偏好读取（2026-09-12）：localStorage 存的是字符串，调用方原先是
 * `readPref(k,false) === true || readPref(k,false) === '1'`（同一个 key 读两次），统一收口到这里。
 */
export function readBoolPref(key, defValue = false) {
  const v = readPref(key, defValue)
  if (v === true || v === false) return v
  return v === '1' || v === 'true'
}

export function savePref(key, value) {
  try {
    localStorage.setItem(PREFIX + key, String(value))
  } catch (e) {
    /* 隐私模式 / SSR：忽略 */
  }
}

/** 抽屉宽度（px 数值）：读取失败回退默认值 */
export function readDrawerWidth(key, defPx) {
  const v = Number(readPref(key, defPx))
  return Number.isFinite(v) && v >= 320 ? v : defPx
}

export function saveDrawerWidth(key, px) {
  savePref(key, Math.round(px))
}
