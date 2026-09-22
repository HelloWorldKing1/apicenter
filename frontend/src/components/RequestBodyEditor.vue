<template>
  <div class="rb-editor">
    <!-- 两行布局：上排 = 格式标签 + 操作；提示**独占一行** —— 同行会把标签/按钮挤成竖排窄条（实测） -->
    <div class="rb-top">
      <span class="rb-fmt">{{ formatLabel }}</span>
      <el-button size="small" class="rb-btn" @click="doBeautify">美化结构</el-button>
    </div>
    <div class="rb-hint">{{ hint }}</div>
    <textarea v-model="text" class="rb-body" spellcheck="false" :placeholder="ph"></textarea>
    <div v-if="message" class="rb-msg" :class="{ 'rb-msg-err': !ok }">{{ message }}</div>
  </div>
</template>

<script setup>
/**
 * 请求体输入框（「测试接口」/「模拟回调」共用，2026-09-22）。
 *
 * 解决三件事：
 * ① **格式随接口的「入站协议」**（不是在这里选）：工具栏显示 `XML/JSON · 按「入站协议」` 并给出该填什么的提示；
 * ② **结构美化**（无损，只增删空白）：一键按 XML / JSON 缩进；**失败不吞输入**，只提示原因；
 * ③ **样式**：自带深色等宽编辑器样式（此前两个弹窗用的 `raw-editor` 样式定义在别的组件的 scoped 块里，
 *    实际**不生效**，textarea 是浏览器默认外观）。
 */
import { computed, ref } from 'vue'
import { beautifyBody, bodyFormatLabel, bodyHintFor, bodyPlaceholderFor } from '@/utils/requestBody.mjs'

const props = defineProps({
  modelValue: { type: String, default: '' },
  /** 接口的入站协议：决定美化格式与提示（缺省按 JSON，与后端默认一致） */
  protocolIn: { type: String, default: 'JSON' },
  /** true = 回调报文（仅影响提示措辞与示例） */
  callback: { type: Boolean, default: false },
  /** 显式指定 placeholder；不传则按协议给示例 */
  placeholder: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue'])

const text = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})
const formatLabel = computed(() => bodyFormatLabel(props.protocolIn))
const hint = computed(() => bodyHintFor(props.protocolIn, { callback: props.callback }))
const ph = computed(() => props.placeholder || bodyPlaceholderFor(props.protocolIn, { callback: props.callback }))

const message = ref('')
const ok = ref(true)

function doBeautify() {
  const r = beautifyBody(text.value, props.protocolIn)
  ok.value = r.ok
  message.value = r.message
  // 仅在成功且确有变化时回写（失败保持原样，绝不吞掉用户输入）
  if (r.ok && r.text !== text.value) {
    text.value = r.text
  }
}

/** 弹窗重新打开时清掉上一次的提示（由父组件通过 ref 调用） */
function reset() {
  message.value = ''
  ok.value = true
}

defineExpose({ reset, doBeautify })
</script>

<style scoped>
.rb-editor { width: 100%; }
.rb-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}
.rb-fmt {
  flex: none;
  padding: 1px 6px;
  border-radius: 3px;
  background: #ecf5ff;
  border: 1px solid #d9ecff;
  color: #409eff;
  font-size: 12px;
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  white-space: nowrap;
}
.rb-hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
  margin-bottom: 6px;
}
.rb-body {
  width: 100%;
  min-height: 260px;
  box-sizing: border-box;
  background: #282c34;
  color: #abb2bf;
  border: none;
  border-radius: 6px;
  padding: 12px;
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  resize: vertical;
  white-space: pre;
  tab-size: 2;
}
.rb-body:focus { outline: 1px solid #4a90d9; }
.rb-body::placeholder { color: #6b7280; }
.rb-msg {
  margin-top: 6px;
  padding: 6px 10px;
  border-radius: 4px;
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
  color: #529b2e;
  font-size: 12px;
  line-height: 1.6;
}
.rb-msg-err {
  background: #fdf6ec;
  border-color: #f5dab1;
  color: #b88230;
}
</style>
