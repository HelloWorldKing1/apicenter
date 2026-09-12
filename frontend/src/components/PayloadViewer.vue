<template>
  <div class="payload-viewer" :class="{ 'pv--full': fullscreen }">
    <!-- 工具栏：格式 / 截断 / 二进制标记 · 字符数·行数 · 美化原文切换 · 折行·行号·全屏 · 复制 -->
    <div class="pv-bar">
      <el-tag v-if="view.lang !== 'text'" size="small" effect="plain">{{ langLabel }}</el-tag>
      <el-tag v-if="view.truncated" size="small" type="warning" effect="plain">已截断</el-tag>
      <el-tag v-if="view.binary" size="small" type="info" effect="plain">二进制</el-tag>
      <span v-if="view.length" class="pv-meta">
        {{ view.length }} 字符<template v-if="lineCount > 1"> · {{ lineCount }} 行</template>
      </span>
      <span class="pv-spacer" />
      <el-radio-group v-if="view.formatted" v-model="mode" size="small">
        <el-radio-button value="pretty">美化</el-radio-button>
        <el-radio-button value="raw">原文</el-radio-button>
      </el-radio-group>
      <el-button size="small" text :type="wrap ? 'primary' : ''" title="长行折行显示" @click="wrap = !wrap">折行</el-button>
      <el-button size="small" text :type="gutter ? 'primary' : ''" title="显示行号" @click="gutter = !gutter">行号</el-button>
      <el-button size="small" text title="全屏查看（Esc 退出）" @click="fullscreen = !fullscreen">
        {{ fullscreen ? '退出全屏' : '全屏' }}
      </el-button>
      <el-dropdown size="small" @command="onCopy">
        <el-button size="small" text>复制 ▾</el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="view">复制当前视图</el-dropdown-item>
            <el-dropdown-item command="raw">复制原文</el-dropdown-item>
            <el-dropdown-item command="context" :disabled="!context">复制含上下文</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <div v-if="note" class="pv-note">{{ note }}</div>
    <div v-if="view.imagePreview" class="pv-image">
      <img :src="view.imagePreview" alt="Base64 图片预览" />
    </div>

    <div class="pv-body" :class="{ 'pv-body--wrap': wrap, 'pv-body--full': fullscreen }"
         :style="fullscreen ? null : { maxHeight }">
      <div v-if="!view.raw" class="pv-empty">{{ emptyText }}</div>
      <template v-else>
        <div v-for="(line, li) in visibleLines" :key="li" class="pv-line">
          <span v-if="gutter" class="pv-ln">{{ li + 1 }}</span>
          <span class="pv-lt">
            <span v-for="(tk, ti) in (line.length ? line : EMPTY_LINE)" :key="ti" :class="'tk-' + tk.type">{{ tk.text }}</span>
          </span>
        </div>
      </template>
    </div>

    <div v-if="folded" class="pv-fold">
      <el-button size="small" text type="primary" @click="expanded = !expanded">
        {{ expanded ? `折叠（只看前 ${FOLD_LINES} 行）` : `展开全部（共 ${lineCount} 行）` }}
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  analyzeHeaders, analyzePayload, FOLD_LINES, highlightEnabled, MAX_FORMAT_CHARS,
  pickPayloadText, RENDER_MAX_LINES, splitTokenLines, tokenize, WORKER_FORMAT_LIMIT
} from '@/utils/payload.mjs'
import { readPref, savePref } from '@/utils/prefs.mjs'

// 报文查看器（调用日志明细 / 状态机 Tab / 仪表盘最近日志 / 死信 payload 共用）。
// 格式化约定见 utils/payload.mjs：只增删空白、不改写 token；截断报文也能缩进；可切「原文」核对。
// 本组件只负责展示：探测 / 缩进 / 高亮 / 折叠 / 复制 / 全屏。
const props = defineProps({
  text: { type: String, default: '' },
  emptyText: { type: String, default: '—' },
  headers: { type: Boolean, default: false },   // 头串模式（`k: v | k2: v2` → 拆行）
  contentType: { type: String, default: '' },   // 请求头里的 Content-Type（探测提示，可选）
  context: { type: String, default: '' },       // 「复制含上下文」前缀（如 `#448 OUT POST url trace=…`）
  maxHeight: { type: String, default: '320px' }
})

/** 空行占位：让空行仍占一行高度（零宽空格，不影响复制内容？复制按文字走，见 onCopy 用原文） */
const EMPTY_LINE = [{ type: 'plain', text: '\u200b' }]

const mode = ref('pretty')
const wrap = ref(readPref('payload.wrap', false) === true || readPref('payload.wrap', false) === '1')
const gutter = ref(readPref('payload.gutter', false) === true || readPref('payload.gutter', false) === '1')
const fullscreen = ref(false)
const expanded = ref(false)

// 大报文：Worker 异步格式化（主线程先显示原文，完成后替换）
const asyncView = ref(null)
const pending = ref(false)
let worker = null
let seq = 0

/** 同步视图：头串走 analyzeHeaders，其余走 analyzePayload（带 Content-Type 提示） */
const baseView = computed(() => (props.headers
  ? analyzeHeaders(props.text)
  : analyzePayload(props.text, props.contentType)))
const view = computed(() => asyncView.value?.view || baseView.value)

/** 展示文本（美化/原文）与 token 化行（语法高亮；超限或原文模式退化为纯文本） */
const displayText = computed(() => pickPayloadText(view.value, mode.value))
const tokens = computed(() => {
  const text = displayText.value
  if (mode.value !== 'pretty' || !view.value.formatted || !highlightEnabled(text)) {
    return [{ type: 'plain', text }]
  }
  return tokenize(text, view.value.lang)
})
const lines = computed(() => splitTokenLines(tokens.value))
const lineCount = computed(() => (view.value.raw ? lines.value.length : 0))
const visibleLines = computed(() => (expanded.value ? lines.value.slice(0, RENDER_MAX_LINES) : lines.value.slice(0, FOLD_LINES)))
const folded = computed(() => lineCount.value > FOLD_LINES)
const langLabel = computed(() => ({ json: 'JSON', xml: 'XML', form: 'FORM', binary: 'BIN', headers: 'HEADERS' }[view.value.lang] || 'TEXT'))

const note = computed(() => {
  const notes = []
  if (view.value.note) notes.push(view.value.note)
  if (pending.value) notes.push('内容较大，正在后台美化…（先显示原文）')
  if (expanded.value && lineCount.value > RENDER_MAX_LINES) {
    notes.push(`已渲染前 ${RENDER_MAX_LINES} 行（共 ${lineCount.value} 行），全文请用「复制」获取`)
  }
  return notes.join(' · ')
})

// 文本 / 模式变化：复位视图态 + 触发大报文 Worker
watch([() => props.text, () => props.headers, () => props.contentType], () => {
  mode.value = 'pretty'
  expanded.value = false
  asyncView.value = null
  scheduleLargeFormat()
}, { immediate: true })

watch(wrap, (v) => savePref('payload.wrap', v))
watch(gutter, (v) => savePref('payload.gutter', v))
watch(fullscreen, (on) => {
  if (typeof document === 'undefined') return
  if (on) document.addEventListener('keydown', onKeydown)
  else document.removeEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  if (typeof document !== 'undefined') document.removeEventListener('keydown', onKeydown)
  try { worker?.terminate() } catch (e) { /* 忽略 */ }
})

function onKeydown(e) {
  if (e.key === 'Escape') fullscreen.value = false
}

/** 仅对「主线程阈值以上、Worker 上限以下」的报文启用 Worker */
function scheduleLargeFormat() {
  const text = props.text
  const need = !props.headers && typeof text === 'string'
    && text.length > MAX_FORMAT_CHARS && text.length <= WORKER_FORMAT_LIMIT
  if (!need || typeof Worker === 'undefined') {
    pending.value = false
    return
  }
  pending.value = true
  const key = ++seq
  try {
    if (!worker) {
      worker = new Worker(new URL('../workers/payloadFormat.worker.mjs', import.meta.url), { type: 'module' })
    }
    worker.onmessage = (e) => {
      if (e.data?.key !== key) return
      if (e.data.view) asyncView.value = { key, view: e.data.view }
      pending.value = false
    }
    worker.onerror = () => { worker = null; pending.value = false }
    worker.postMessage({ key, text, contentType: props.contentType })
  } catch (e) {
    worker = null
    pending.value = false   // 环境不支持 / 构建异常 → 保持原文降级
  }
}

/** 复制：当前视图 / 原文 / 含上下文（上下文 + 当前展示文本） */
async function onCopy(command) {
  const body = command === 'raw' ? view.value.raw : displayText.value
  if (!body) return
  const text = command === 'context' && props.context ? `${props.context}\n\n${body}` : body
  await writeClipboard(text)
}

async function writeClipboard(text) {
  try {
    if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch (e) {
    // 非安全上下文（http 非 localhost）兜底：临时 textarea + execCommand
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try {
      document.execCommand('copy')
      ElMessage.success('已复制')
    } catch (e2) {
      ElMessage.warning('复制失败，请手动选择文本')
    }
    document.body.removeChild(ta)
  }
}
</script>

<style scoped>
.payload-viewer { margin-bottom: 4px; }
.pv--full {
  position: fixed; inset: 0; z-index: 3000; background: #fff;
  padding: 16px 20px; display: flex; flex-direction: column;
}
.pv-bar { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; flex-wrap: wrap; }
.pv-meta { font-size: 12px; color: #909399; }
.pv-spacer { flex: 1; }
.pv-note { font-size: 12px; color: #b88230; background: #fdf6ec; border: 1px solid #f5dab1;
  border-radius: 4px; padding: 4px 8px; margin-bottom: 6px; line-height: 1.6; }
.pv-image { margin-bottom: 6px; }
.pv-image img { max-width: 100%; max-height: 320px; border: 1px solid #EBEEF5; border-radius: 6px; }
.pv-body {
  background: #F7F8FA; border: 1px solid #EBEEF5; border-radius: 6px; padding: 10px;
  font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12px; line-height: 1.6;
  overflow: auto;
}
.pv-body--full { flex: 1; max-height: none; }
.pv-empty { color: #909399; }
.pv-line { display: flex; }
.pv-ln {
  flex: none; min-width: 3.5ch; padding-right: 10px; text-align: right;
  color: #c0c4cc; user-select: none;
}
.pv-lt { white-space: pre; }
.pv-body--wrap .pv-lt { white-space: pre-wrap; word-break: break-all; }
.pv-fold { margin-top: 4px; }

/* 语法高亮（浅色，仅着色不改写 token） */
.tk-key { color: #8250df; }
.tk-string { color: #0a3069; }
.tk-number { color: #0550ae; }
.tk-literal { color: #cf222e; }
.tk-punct { color: #57606a; }
.tk-tag { color: #116329; }
.tk-attr { color: #0550ae; }
.tk-attrvalue { color: #0a3069; }
.tk-text { color: #24292f; }
.tk-comment { color: #6e7781; font-style: italic; }
.tk-cdata { color: #6e7781; }
.tk-pi { color: #8250df; }
.tk-doctype { color: #8250df; }
.tk-plain { color: inherit; }
</style>
