/**
 * 复制到剪贴板（2026-09-25，凭证明文「复制」按钮共用）。
 *
 * <p>为什么要包一层：`navigator.clipboard` **只在安全上下文可用**（https / `localhost`）；
 * 用 `http://192.168.x.x` 打开管理面时它是 `undefined` ⇒ 直接调用会抛
 * `Cannot read properties of undefined (reading 'writeText')`。这里按序降级：
 * ① `navigator.clipboard.writeText`（现代、异步）→ ② 临时 `textarea` + `document.execCommand('copy')`（老路径）→ ③ 返回 false。
 *
 * <p>SSR / 单测无 `navigator` / `document` ⇒ 守卫后直接返回 false，**不抛**（冒烟会跑本模块的调用方组件）。
 * `deps` 仅用于单测注入替身。
 *
 * @returns {Promise<boolean>} true = 已复制（调用方据此决定「已复制」还是「请手动选择复制」提示）
 */
export async function copyText(text, deps = {}) {
  const value = text == null ? '' : String(text)
  if (!value) {
    return false
  }
  const nav = deps.navigator !== undefined ? deps.navigator : globalThis.navigator
  const doc = deps.document !== undefined ? deps.document : globalThis.document
  if (nav && nav.clipboard && typeof nav.clipboard.writeText === 'function') {
    try {
      await nav.clipboard.writeText(value)
      return true
    } catch {
      // 权限被拒 / 非安全上下文：走兜底
    }
  }
  return legacyCopy(value, doc)
}

/** 兜底路径：临时 textarea + execCommand('copy')（用完即删，不留 DOM 垃圾） */
function legacyCopy(value, doc) {
  if (!doc || !doc.body || typeof doc.createElement !== 'function') {
    return false
  }
  const area = doc.createElement('textarea')
  area.value = value
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.top = '-1000px'
  area.style.opacity = '0'
  doc.body.appendChild(area)
  try {
    if (typeof area.select === 'function') {
      area.select()
    } else if (typeof area.setSelectionRange === 'function') {
      area.setSelectionRange(0, area.value.length)
    }
    if (typeof doc.execCommand !== 'function') {
      return false
    }
    return doc.execCommand('copy') !== false
  } catch {
    return false
  } finally {
    if (typeof area.remove === 'function') {
      area.remove()
    } else if (doc.body.removeChild) {
      doc.body.removeChild(area)
    }
  }
}
