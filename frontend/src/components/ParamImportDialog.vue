<template>
  <el-dialog :model-value="modelValue" :title="`快速导入参数（${sideLabel}）`" width="760px" append-to-body
             @update:model-value="emit('update:modelValue', $event)">
    <!-- ① 粘贴 + 校验 + 美化 -->
    <div class="pi-step">
      <span class="pi-step-no">①</span> 粘贴 JSON
      <span class="pi-spacer" />
      <el-button size="small" :disabled="!bodyRaw" @click="useBodyTemplate">从本侧请求体带入</el-button>
      <el-button size="small" :disabled="!analysis.ok" @click="beautify">美化</el-button>
      <el-button size="small" text @click="clear">清空</el-button>
    </div>
    <el-input ref="inputRef" v-model="text" type="textarea" :rows="8" spellcheck="false"
              class="pi-paste" placeholder='粘贴请求 JSON，例如 {"filter":{"seller_id":7494312521977267257},"page":1}'
              @input="onInput" />
    <div class="pi-status" :class="analysis.ok ? 'ok' : 'err'">
      <template v-if="!text.trim()">等待粘贴…（支持 JSON；也支持 form-urlencoded，如 a=1&amp;b=2）</template>
      <template v-else-if="analysis.ok">
        ✓ 格式合法 · 识别 {{ analysis.params.length }} 个字段 · 最大深度 {{ analysis.stats.maxDepth }}
        <template v-if="analysis.truncated">（内容疑似截断，按可见部分解析）</template>
      </template>
      <template v-else>
        ✗ 第 {{ analysis.error.line }} 行 {{ analysis.error.column }} 列：{{ analysis.error.message }}
        <el-button v-if="analysis.error.snippet" link type="primary" size="small" @click="locateError">定位</el-button>
      </template>
    </div>
    <div v-if="analysis.error && analysis.error.snippet" class="pi-snippet">{{ analysis.error.snippet }}</div>
    <div v-if="analysis.warnings.length" class="pi-warnings">
      <div v-for="(w, i) in analysis.warnings" :key="i">⚠ {{ w }}</div>
    </div>

    <!-- ② 选项 -->
    <div class="pi-step"><span class="pi-step-no">②</span> 导入选项</div>
    <div class="pi-options">
      <span class="pi-opt">
        已存在参数
        <el-radio-group v-model="mergeMode" size="small" :disabled="!existingNames.length">
          <el-radio-button value="merge">覆盖同名并追加</el-radio-button>
          <el-radio-button value="replace">清空后导入</el-radio-button>
        </el-radio-group>
      </span>
      <span class="pi-opt">
        必填策略
        <el-radio-group v-model="requiredMode" size="small">
          <el-radio-button value="all">全部必填</el-radio-button>
          <el-radio-button value="top">仅顶层必填</el-radio-button>
        </el-radio-group>
      </span>
      <span class="pi-opt">嵌套展开 <el-switch v-model="expand" size="small" /></span>
      <span class="pi-opt">数组取样 <el-switch v-model="arraySample" size="small" /></span>
    </div>

    <!-- ③ 预览 -->
    <div class="pi-step">
      <span class="pi-step-no">③</span> 将导入 {{ analysis.params.length }} 条
      <span v-if="analysis.ok" class="pi-meta">
        （覆盖 {{ overwriteCount }} · 新增 {{ analysis.params.length - overwriteCount }}<template
          v-if="analysis.stats.skipped"> · 忽略 {{ analysis.stats.skipped }}</template>）
      </span>
    </div>
    <el-table :data="previewRows" size="small" max-height="240" class="pi-preview">
      <el-table-column prop="name" label="参数名" show-overflow-tooltip />
      <el-table-column prop="type" label="类型" width="90" />
      <el-table-column label="必填" width="70">
        <template #default="{ row }">
          <el-tag size="small" :type="row.required ? 'success' : 'info'" effect="plain">
            {{ row.required ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sample" label="示例值" show-overflow-tooltip />
    </el-table>
    <div v-if="analysis.params.length > previewRows.length" class="pi-meta">
      仅预览前 {{ previewRows.length }} 条，实际将导入 {{ analysis.params.length }} 条。
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!canImport" @click="doImport">
        导入 {{ analysis.params.length }} 条
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { extractParams } from '@/utils/paramImport.mjs'
import { formatForm, formatJson } from '@/utils/payload.mjs'

// 请求参数快速导入弹窗（2026-09-12 评审定稿：D1–D6 按推荐）。
// 校验与取值全在 utils/paramImport.mjs（示例值取 token 原始切片，19 位数字保真）；
// 本组件只负责：粘贴区、校验状态、选项、预览表、美化。
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  side: { type: String, default: 'IN' },            // 'IN' | 'OUT'（仅展示用，写回由父组件决定）
  sideLabel: { type: String, default: '入站侧' },
  existingNames: { type: Array, default: () => [] },// 该侧已有参数名（用于覆盖/新增提示）
  bodyRaw: { type: String, default: '' }            // 该侧请求体模板（可一键带入）
})
const emit = defineEmits(['update:modelValue', 'import'])

const PREVIEW_LIMIT = 50
const inputRef = ref(null)
const text = ref('')
const mergeMode = ref('merge')
const requiredMode = ref('all')
const expand = ref(true)
const arraySample = ref(true)

/** 防抖后的分析结果（粘贴 300ms 后计算） */
const analysis = reactive({ ok: false, error: null, truncated: false, format: 'json', params: [], warnings: [], stats: { count: 0, skipped: 0, maxDepth: 0 } })
let timer = null

function analyzeNow() {
  Object.assign(analysis, extractParams(text.value, {
    allRequired: requiredMode.value === 'all',
    expand: expand.value,
    arraySample: arraySample.value
  }))
}

function onInput() {
  clearTimeout(timer)
  timer = setTimeout(analyzeNow, 300)
}

// 卸载时清掉防抖（否则弹窗关闭后仍会跑一次分析）
onBeforeUnmount(() => clearTimeout(timer))

watch(() => props.modelValue, (open) => {
  if (!open) return
  // 每次打开复位（保留上一次选项：expand/merge 是用户偏好）
  text.value = ''
  clearTimeout(timer)
  analyzeNow()
})

// 选项变化立即重算（不必等防抖）
watch([requiredMode, expand, arraySample], analyzeNow)

const existingSet = computed(() => new Set(props.existingNames))
const overwriteCount = computed(() => (mergeMode.value === 'replace'
  ? 0
  : analysis.params.filter((p) => existingSet.value.has(p.name)).length))
const previewRows = computed(() => analysis.params.slice(0, PREVIEW_LIMIT))
const canImport = computed(() => analysis.ok && analysis.params.length > 0)

function clear() {
  text.value = ''
  analyzeNow()
}

function useBodyTemplate() {
  text.value = props.bodyRaw || ''
  analyzeNow()
}

/** 美化：JSON 用扫描式缩进（不改写 token），form 用拆行 */
function beautify() {
  if (!analysis.ok) return
  text.value = analysis.format === 'form' ? formatForm(text.value.trim()) : formatJson(text.value.trim())
  analyzeNow()
}

/** 定位错误：把光标/选区落到出错行（textarea 无法局部高亮，退而求其次） */
async function locateError() {
  const { line, column } = analysis.error
  const lines = text.value.split('\n')
  const start = lines.slice(0, line - 1).reduce((n, l) => n + l.length + 1, 0) + Math.max(0, column - 1)
  const end = start + Math.max(1, (lines[line - 1] || '').length - column + 1)
  await nextTick()
  const ta = inputRef.value?.textarea || inputRef.value?.$el?.querySelector('textarea')
  if (!ta) return
  ta.focus()
  ta.setSelectionRange(start, Math.min(end, text.value.length))
}

function doImport() {
  if (!canImport.value) return
  emit('import', { params: analysis.params, mergeMode: mergeMode.value, format: analysis.format })
  emit('update:modelValue', false)
}
</script>

<style scoped>
.pi-step { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #303133; margin: 4px 0 6px; }
.pi-step-no { color: #409eff; font-weight: 600; }
.pi-spacer { flex: 1; }
.pi-paste :deep(textarea) { font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12px; line-height: 1.6; }
.pi-status { font-size: 12px; margin-top: 6px; line-height: 1.7; }
.pi-status.ok { color: #529b2e; }
.pi-status.err { color: #f56c6c; }
.pi-snippet {
  font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12px; color: #b88230;
  background: #fdf6ec; border: 1px solid #f5dab1; border-radius: 4px; padding: 4px 8px; margin-top: 4px;
  white-space: pre-wrap; word-break: break-all;
}
.pi-warnings { font-size: 12px; color: #b88230; line-height: 1.8; margin-top: 4px; }
.pi-options { display: flex; flex-wrap: wrap; gap: 14px 20px; margin-bottom: 10px; }
.pi-opt { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: #606266; }
.pi-meta { font-size: 12px; color: #909399; }
.pi-preview { border: 1px solid #EBEEF5; border-radius: 6px; }
</style>
