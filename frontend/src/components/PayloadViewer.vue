<template>
  <div class="payload-viewer">
    <!-- 工具栏：格式标签 / 截断标记 / 字符数 + 美化·原文切换 + 复制 -->
    <div class="pv-bar">
      <el-tag v-if="view.lang !== 'text'" size="small" effect="plain">{{ view.lang.toUpperCase() }}</el-tag>
      <el-tag v-if="view.truncated" size="small" type="warning" effect="plain">已截断</el-tag>
      <span v-if="view.length" class="pv-meta">{{ view.length }} 字符</span>
      <span class="pv-spacer" />
      <el-radio-group v-if="view.formatted" v-model="mode" size="small">
        <el-radio-button value="pretty">美化</el-radio-button>
        <el-radio-button value="raw">原文</el-radio-button>
      </el-radio-group>
      <el-button size="small" text :disabled="!view.raw" @click="copy">复制</el-button>
    </div>
    <div v-if="view.note" class="pv-note">{{ view.note }}</div>
    <pre class="pv-body">{{ shown || emptyText }}</pre>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { analyzePayload, pickPayloadText } from '@/utils/payload.mjs'

// 报文展示组件（调用日志明细 / 状态机 Tab / 仪表盘最近日志共用）。
// 格式化约束见 utils/payload.mjs：只增删空白、不改写 token（19 位数字等原样保留），
// 截断报文也能缩进；可一键切「原文」逐字节核对。默认美化，切换文本时复位。
const props = defineProps({
  text: { type: String, default: '' },
  emptyText: { type: String, default: '—' }
})

const mode = ref('pretty')
watch(() => props.text, () => { mode.value = 'pretty' })

const view = computed(() => analyzePayload(props.text))
// 注意：JS 里必须 `.value`（模板会自动解包）——取展示文本的规则已提为纯函数并单测
const shown = computed(() => pickPayloadText(view.value, mode.value))

async function copy() {
  const text = shown.value
  if (!text) return
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
.pv-bar { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.pv-meta { font-size: 12px; color: #909399; }
.pv-spacer { flex: 1; }
.pv-note { font-size: 12px; color: #b88230; background: #fdf6ec; border: 1px solid #f5dab1;
  border-radius: 4px; padding: 4px 8px; margin-bottom: 6px; line-height: 1.6; }
.pv-body {
  background: #F7F8FA; border: 1px solid #EBEEF5; border-radius: 6px; padding: 10px;
  font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12px; line-height: 1.6;
  max-height: 320px; overflow: auto; white-space: pre; margin: 0;
}
</style>
