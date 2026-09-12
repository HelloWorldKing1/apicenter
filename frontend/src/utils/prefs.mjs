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
