<template>
  <pre class="code-block">{{ text || emptyText }}</pre>
</template>

<script setup>
// 纯文本代码块（2026-09-12 抽公共）：Monitor 与 Dashboard 各写一份 .mono-block 样式，改一处漏一处。
// 需要缩进/高亮/折叠的报文请用 PayloadViewer；本组件只服务「请求头原文、死信原因」等纯文本场景。
defineProps({
  text: { type: String, default: '' },
  emptyText: { type: String, default: '—' }
})
</script>

<style scoped>
.code-block {
  background: #F7F8FA; border: 1px solid #EBEEF5; border-radius: 6px; padding: 10px;
  font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12px;
  max-height: 220px; overflow: auto; white-space: pre-wrap; word-break: break-all; margin: 0;
}
</style>
