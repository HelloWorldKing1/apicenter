<template>
  <!-- 一次性明文（凭证 / 密钥）统一展示块：复制按钮 + 长文本滚动 + 等宽 -->
  <div class="plaintext-once">
    <div class="p-head">
      <span class="p-title">{{ title }}</span>
      <el-button size="small" type="primary" plain @click="copy">复制</el-button>
    </div>
    <!-- 长度超限时**纵向滚动**（超长单词/长 JSON 也不撑破弹窗：break-all + pre-wrap） -->
    <pre ref="box" class="p-body" :style="{ maxHeight: maxHeight + 'px' }" @click="selectAll">{{ text }}</pre>
    <div class="p-hint">{{ hint }}</div>
  </div>
</template>

<script setup>
/**
 * 一次性明文展示（2026-09-25）。
 *
 * 起因：调用方管理「生成新凭证」后只把明文塞在 `<pre>` 里 —— ① 没有复制按钮（长 token 靠手选，
 * 容易选漏一个字符，然后拿错密钥去联调）；② 长凭证（多字段 JSON / 长 Bearer）把抽屉撑爆。
 * 现在统一由本组件渲染：**复制按钮**（带降级路径，见 `utils/clipboard.mjs`）+ **固定最大高度内滚动**。
 *
 * 用法：`<PlaintextOnce v-if="issued" :text="issued" />`
 * 注意：明文只在**内存**里存在（后端也只在生成响应里回显一次），关掉弹窗即应清空。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { copyText } from '@/utils/clipboard.mjs'

const props = defineProps({
  text: { type: String, required: true },
  title: { type: String, default: '明文仅显示这一次，请立即交付给调用方并妥善保存' },
  hint: {
    type: String,
    default: '库内只存密文/摘要，关闭后无法再次查看；若已丢失，请吊销后重新生成'
  },
  /** 明文框最大高度（px）：超出即出现滚动条（默认 160） */
  maxHeight: { type: Number, default: 160 }
})

const box = ref(null)

async function copy() {
  const ok = await copyText(props.text)
  if (ok) {
    ElMessage.success('已复制到剪贴板')
  } else {
    ElMessage.warning('当前环境不支持自动复制，请点击明文框后手动复制（已自动全选）')
    selectAll()
  }
}

/** 点击明文块 = 全选（复制失败时的手动兜底路径） */
function selectAll() {
  const el = box.value
  if (!el || typeof window === 'undefined' || !window.getSelection || !document.createRange) {
    return
  }
  const range = document.createRange()
  range.selectNodeContents(el)
  const selection = window.getSelection()
  selection.removeAllRanges()
  selection.addRange(range)
}
</script>

<style scoped>
.plaintext-once {
  margin-top: 12px;
  padding: 10px 12px;
  background: #fdf6ec;
  border: 1px solid #f5dab1;
  border-radius: 4px;
}
.p-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.p-title { font-size: 13px; font-weight: 600; color: #b88230; }
.p-body {
  margin: 6px 0 0;
  padding: 6px 8px;
  background: #fff;
  border: 1px solid #f0dcb8;
  border-radius: 3px;
  overflow: auto;                 /* 长凭证 → 纵向滚动条（横向也兜底） */
  white-space: pre-wrap;
  word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: #8a6218;
  cursor: text;
  user-select: all;
}
.p-hint { margin-top: 6px; font-size: 12px; color: #909399; line-height: 1.6; }
</style>
