/**
 * 登录页的「记住用户名」与「记住密码」（2026-09-24）。
 *
 * ## 记住用户名（默认开，安全）
 * 只把**用户名**存进 `localStorage`（不敏感，省一次输入）；取消勾选**同时清掉旧值**。
 *
 * ## 记住密码（默认关，带风险确认）
 * 需求方明确要求"本地记住口令"。**不做明文落 `localStorage`**（那等于把最高权限凭证摊在浏览器里，
 * 任何能读存储的东西都能拿走），改用：
 * - **密钥**：`crypto.subtle.generateKey({name:'AES-GCM'}, extractable=false, ...)` —— **不可导出**，
 *   存在 **IndexedDB** 里 ⇒ 页面脚本**拿不走密钥**（只能"用它解密"，无法把密钥外传/复制到别处）；
 * - **密文**：AES-GCM（带随机 IV + 认证标签）存 IndexedDB；
 * - **开关**：布尔标记存 `localStorage`（同步可读，供复选框回显）。
 *
 * ### 威胁模型（**必须如实告知**，页面上有风险确认弹窗）
 * | 能防 | 不能防 |
 * |---|---|
 * | 离线读取浏览器 profile / 备份里的凭据（拿到的是密文） | **同源脚本（XSS）**：它能调用本模块解密出明文 |
 * | 只读存储的扩展 / 手工翻 `localStorage` | 已拿到本机用户会话的攻击者（能开 DevTools / 注入脚本） |
 * | 把密文拷到别的浏览器/设备（密钥留在本机 IDB，解不开） | 键盘记录 / 屏幕截取 |
 * ⇒ **仅建议在个人专用设备上开启**；环境不支持（无 WebCrypto / 无 IndexedDB）时**一律不记住**（fail-closed，
 * 宁可功能不可用，也不退回明文存储）。
 *
 * <p>依赖可注入（`deps.subtle` / `deps.store`）⇒ Node 单测可用内存 store + Node 自带 `crypto.subtle`
 * 完整覆盖加解密、篡改检测与失败降级；模块顶层做能力守卫，保证 SSR 安全。
 */

export const REMEMBER_KEY = 'apicenter.rememberUsername'
export const REMEMBER_FLAG_KEY = 'apicenter.rememberUsernameOn'
export const REMEMBER_PASSWORD_FLAG_KEY = 'apicenter.rememberPasswordOn'

/** 默认勾选「记住用户名」（只存用户名，无敏感信息，默认开启更省事） */
export const REMEMBER_DEFAULT = true

/** **默认不勾选**「记住密码」（涉及口令，必须由使用者显式选择并确认风险） */
export const REMEMBER_PASSWORD_DEFAULT = false

const IDB_NAME = 'apicenter.prefs'
const IDB_STORE = 'secrets'
const SECRET_ID = 'login-password'

/** 风险提示文案（勾选「记住密码」时弹出的确认内容；与设计方案 §15 的威胁模型一致） */
export const REMEMBER_PASSWORD_RISK = [
  '口令将以**加密形式**保存在本机浏览器中（密钥不可导出，存于本机 IndexedDB）。',
  '但它**不能防住同源脚本（XSS）**：能在此页面执行脚本的人仍可解密出明文。',
  '也不能防住已在你这台电脑上操作的人（可开 DevTools 或注入脚本）。',
  '**仅在个人专用设备上开启**；公用/共享电脑请勿勾选。'
].join('\n')

/** 读环境 storage（无 localStorage 的 SSR / Node 环境返回 null） */
function defaultStorage() {
  return typeof localStorage !== 'undefined' ? localStorage : null
}

function safeGet(storage, key) {
  if (!storage) {
    return null
  }
  try {
    return storage.getItem(key)
  } catch (e) {
    return null
  }
}

function safeSet(storage, key, value) {
  if (!storage) {
    return false
  }
  try {
    storage.setItem(key, value)
    return true
  } catch (e) {
    return false
  }
}

function safeRemove(storage, key) {
  if (!storage) {
    return false
  }
  try {
    storage.removeItem(key)
    return true
  } catch (e) {
    return false
  }
}

// ---------- 记住用户名 ----------

/** 记住的用户名（未记住时返回空串） */
export function loadRememberedUsername(storage) {
  return safeGet(storage === undefined ? defaultStorage() : storage, REMEMBER_KEY) || ''
}

/** 「记住用户名」是否勾选（没存过则用默认值） */
export function loadRememberFlag(storage) {
  const v = safeGet(storage === undefined ? defaultStorage() : storage, REMEMBER_FLAG_KEY)
  return v === null ? REMEMBER_DEFAULT : v === '1'
}

/**
 * 保存「记住用户名」选择：勾选 ⇒ 存用户名；取消 ⇒ **同时清掉旧值**；空用户名一律清除。
 * @returns {boolean} 最终是否记住了
 */
export function saveRememberedUsername(storage, username, remember) {
  const s = storage === undefined ? defaultStorage() : storage
  const name = (username || '').trim()
  safeSet(s, REMEMBER_FLAG_KEY, remember ? '1' : '0')
  if (remember && name) {
    // 以**实际写入结果**为准：storage 不可用/不可写时不能谎报"已记住"
    return safeSet(s, REMEMBER_KEY, name)
  }
  safeRemove(s, REMEMBER_KEY)
  return false
}

// ---------- 记住密码（加密保管） ----------

/** 当前环境是否支持安全地记住口令（WebCrypto + IndexedDB 都要有） */
export function isPasswordRememberSupported() {
  return typeof crypto !== 'undefined' && !!crypto.subtle && typeof indexedDB !== 'undefined'
}

/** 「记住密码」是否勾选（默认 **false**） */
export function loadRememberPasswordFlag(storage) {
  const v = safeGet(storage === undefined ? defaultStorage() : storage, REMEMBER_PASSWORD_FLAG_KEY)
  return v === null ? REMEMBER_PASSWORD_DEFAULT : v === '1'
}

export function saveRememberPasswordFlag(storage, on) {
  return safeSet(storage === undefined ? defaultStorage() : storage, REMEMBER_PASSWORD_FLAG_KEY, on ? '1' : '0')
}

/** IndexedDB 存储（密钥 + 密文都放这里；密钥不可导出） */
function idbStore() {
  return {
    async open() {
      return new Promise((resolve, reject) => {
        const req = indexedDB.open(IDB_NAME, 1)
        req.onupgradeneeded = () => {
          if (!req.result.objectStoreNames.contains(IDB_STORE)) {
            req.result.createObjectStore(IDB_STORE)
          }
        }
        req.onsuccess = () => resolve(req.result)
        req.onerror = () => reject(req.error)
      })
    },
    async put(value) {
      const db = await this.open()
      return new Promise((resolve, reject) => {
        const tx = db.transaction(IDB_STORE, 'readwrite')
        tx.objectStore(IDB_STORE).put(value, SECRET_ID)
        tx.oncomplete = () => resolve(true)
        tx.onerror = () => reject(tx.error)
      })
    },
    async get() {
      const db = await this.open()
      return new Promise((resolve, reject) => {
        const tx = db.transaction(IDB_STORE, 'readonly')
        const req = tx.objectStore(IDB_STORE).get(SECRET_ID)
        req.onsuccess = () => resolve(req.result || null)
        req.onerror = () => reject(req.error)
      })
    },
    async remove() {
      const db = await this.open()
      return new Promise((resolve, reject) => {
        const tx = db.transaction(IDB_STORE, 'readwrite')
        tx.objectStore(IDB_STORE).delete(SECRET_ID)
        tx.oncomplete = () => resolve(true)
        tx.onerror = () => reject(tx.error)
      })
    }
  }
}

function toBase64(bytes) {
  let s = ''
  for (const b of bytes) {
    s += String.fromCharCode(b)
  }
  return typeof btoa === 'function' ? btoa(s) : Buffer.from(bytes).toString('base64')
}

function fromBase64(text) {
  if (typeof atob === 'function') {
    const bin = atob(text)
    const out = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) {
      out[i] = bin.charCodeAt(i)
    }
    return out
  }
  return new Uint8Array(Buffer.from(text, 'base64'))
}

/**
 * 加密保存口令（**不落明文**）。
 *
 * <p>密钥首次生成后**不可导出**（`extractable=false`）并存 IndexedDB —— 这样即使有人能读存储，
 * 也只能读到密文；即使页面脚本被注入，也只能"调用解密"，无法把密钥带出到别处持久化。
 *
 * @returns {Promise<boolean>} 是否保存成功（**环境不支持 / 任何异常 ⇒ false**，绝不明文兜底）
 */
export async function saveRememberedPassword(password, deps = {}) {
  // ⚠️ 必须用 `in` 判断：显式传 null 表示"该依赖不可用"（测试/fail-closed 场景），
  //    用 `||` 会让 null 穿透到全局实现，把"环境不支持"误判成"支持"。
  const subtle = 'subtle' in deps ? deps.subtle : (typeof crypto !== 'undefined' ? crypto.subtle : null)
  const store = 'store' in deps ? deps.store : (typeof indexedDB !== 'undefined' ? idbStore() : null)
  if (!subtle || !store || !password) {
    return false
  }
  try {
    let rec = await store.get()
    let key = rec && rec.key
    if (!key) {
      key = await subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
    }
    const iv = (deps.randomBytes ? deps.randomBytes(12) : crypto.getRandomValues(new Uint8Array(12)))
    const ct = await subtle.encrypt({ name: 'AES-GCM', iv }, key,
      new TextEncoder().encode(password))
    await store.put({ key, iv: toBase64(iv), ct: toBase64(new Uint8Array(ct)) })
    return true
  } catch (e) {
    return false
  }
}

/**
 * 读取并解密口令（未保存 / 解密失败 / 环境不支持 ⇒ 返回**空串**，调用方按"未记住"处理）。
 * 解密失败（如密文被篡改、密钥被清）时会**顺手清掉**脏数据，避免每次都白试。
 */
export async function loadRememberedPassword(deps = {}) {
  const subtle = 'subtle' in deps ? deps.subtle : (typeof crypto !== 'undefined' ? crypto.subtle : null)
  const store = 'store' in deps ? deps.store : (typeof indexedDB !== 'undefined' ? idbStore() : null)
  if (!subtle || !store) {
    return ''
  }
  try {
    const rec = await store.get()
    if (!rec || !rec.key || !rec.ct || !rec.iv) {
      return ''
    }
    const plain = await subtle.decrypt({ name: 'AES-GCM', iv: fromBase64(rec.iv) }, rec.key,
      fromBase64(rec.ct))
    return new TextDecoder().decode(plain)
  } catch (e) {
    try {
      await store.remove()
    } catch (ignored) {
      // 清理失败不影响返回值
    }
    return ''
  }
}

/** 清除已记住的口令（取消勾选 / 改密后调用） */
export async function clearRememberedPassword(deps = {}) {
  const store = 'store' in deps ? deps.store : (typeof indexedDB !== 'undefined' ? idbStore() : null)
  if (!store) {
    return false
  }
  try {
    await store.remove()
    return true
  } catch (e) {
    return false
  }
}
