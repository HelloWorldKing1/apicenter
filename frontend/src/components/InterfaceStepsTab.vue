<template>
  <div>
    <div class="steps-tip">
      前置步骤：调用本接口前，按顺序复用其他<b>出站中转</b>接口的能力（结果合入
      <span class="mono">steps.&lt;步骤名&gt;</span>，供字段映射 / condition 引用）。
      最多 {{ MAX_STEPS }} 步 · 最多 3 层；<b>不配 = 与现在行为完全一致</b>。
    </div>

    <div v-if="form.ifType !== 'OUTBOUND'" class="empty-hint">入站回调接口不支持前置步骤</div>

    <template v-else>
      <el-table v-if="steps.length" :data="steps" size="small" class="iface-table">
        <el-table-column label="#" width="44">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="步骤名" width="110">
          <template #default="{ row }"><span class="mono">{{ row.stepCode }}</span></template>
        </el-table-column>
        <el-table-column label="前置接口" min-width="200">
          <template #default="{ row }">
            <span class="mono">{{ targetOf(row).code || '—' }}</span>
            <span class="step-target-name">{{ targetOf(row).name }}</span>
            <el-tag v-if="targetOf(row).status && targetOf(row).status !== 'PUBLISHED'"
                    size="small" type="warning" effect="plain">未发布</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="失败策略" width="96">
          <template #default>阻断后续</template>
        </el-table-column>
        <el-table-column label="启用" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled === false ? 'info' : 'success'" effect="plain">
              {{ row.enabled === false ? '停用' : '启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row, $index }">
            <el-button link :disabled="$index === 0" @click="move($index, -1)">↑</el-button>
            <el-button link :disabled="$index === steps.length - 1" @click="move($index, 1)">↓</el-button>
            <el-button link type="primary" @click="openEditStep($index)">编辑</el-button>
            <el-button link type="primary" @click="openFields(row)">可用字段</el-button>
            <el-button link type="danger" @click="steps.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="empty-hint">暂无前置步骤（不配 = 与现在行为一致）</div>

      <el-button size="small" class="add-btn" :disabled="steps.length >= MAX_STEPS" @click="openCreateStep">
        ＋ 添加前置步骤
      </el-button>
    </template>

    <!-- 单步编辑 -->
    <el-dialog v-model="edit.visible" :title="edit.index < 0 ? '添加前置步骤' : '编辑前置步骤'" width="520px">
      <div class="step-item">
        <span class="basic-label">步骤名</span>
        <el-input v-model="edit.stepCode" maxlength="32" placeholder="如 auth（字母 / 数字 / 下划线）" />
      </div>
      <div class="step-item">
        <span class="basic-label">前置接口</span>
        <el-select v-model="edit.targetInterfaceId" placeholder="仅可选已发布的出站中转接口" style="width: 100%">
          <el-option v-for="i in candidates" :key="i.id" :value="i.id"
                     :label="`${i.name}（${i.code}）${i.status === 'PUBLISHED' ? '' : ' · ' + statusLabel(i.status)}`" />
        </el-select>
      </div>
      <div class="step-item">
        <span class="basic-label">失败策略</span>
        <el-radio-group v-model="edit.failurePolicy">
          <el-radio-button value="ABORT">阻断后续（ABORT）</el-radio-button>
          <el-radio-button value="CONTINUE" disabled>继续（二期）</el-radio-button>
        </el-radio-group>
      </div>
      <div class="step-item">
        <span class="basic-label">启用</span>
        <el-switch v-model="edit.enabled" />
      </div>
      <div class="steps-tip">
        引用方式：字段映射 source 写
        <span class="mono">steps.{{ edit.stepCode || '&lt;步骤名&gt;' }}.&lt;字段&gt;</span>
      </div>
      <div v-if="edit.error" class="step-error">{{ edit.error }}</div>
      <template #footer>
        <el-button @click="edit.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmEdit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 可引用字段（帮用户写映射的 source） -->
    <el-dialog v-model="fields.visible" title="可引用字段" width="560px">
      <div class="steps-tip">
        两种用法：① 在「字段映射」的 source 下拉里，选「前置步骤 · {{ fields.stepCode }}」分组（已自动列出）；
        ② 在此复制路径后粘贴进 source。
      </div>
      <div v-if="fields.loading" class="steps-tip">加载中…</div>
      <template v-else>
        <div v-if="!fields.items.length" class="empty-hint">该前置接口未声明出站响应字段 / 出站参数</div>
        <div v-for="it in fields.items" :key="it.path" class="field-row">
          <span class="mono">{{ it.path }}</span>
          <el-button link type="primary" @click="copy(it.path)">复制</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import http from '@/api/http'

/** 与后端 InterfaceService 的上限保持一致（步数 ≤5 / 解析链 ≤3 层） */
const MAX_STEPS = 5
const RESERVED_NAME = 'steps'
const STEP_CODE_RE = /^[A-Za-z0-9_]{1,32}$/

const props = defineProps({
  form: { type: Object, required: true },
  /** 候选前置接口（列表页已加载的全量接口） */
  ifaces: { type: Array, default: () => [] },
  /** 当前编辑的接口 id（创建时为 0）：用于排除自身 */
  selfId: { type: Number, default: 0 }
})

/** 步骤数组直连 form.steps（与 ParamTable 同款原地编辑约定；父级 emptyForm/openEdit 保证它是数组） */
const steps = computed(() => (Array.isArray(props.form.steps) ? props.form.steps : []))

const candidates = computed(() =>
  (props.ifaces || []).filter((i) => i.ifType === 'OUTBOUND' && i.id !== props.selfId))

const edit = reactive({
  visible: false, index: -1, stepCode: '', targetInterfaceId: null,
  failurePolicy: 'ABORT', enabled: true, error: ''
})
const fields = reactive({ visible: false, loading: false, items: [], stepCode: '' })

function statusLabel(s) {
  return { DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下线' }[s] || s
}

/** 目标接口展示信息：优先 detail 回显字段，其次候选列表 */
function targetOf(row) {
  const found = (props.ifaces || []).find((i) => i.id === row.targetInterfaceId)
  return {
    code: row.targetCode || found?.code,
    name: row.targetName || found?.name || (found ? '' : '（接口已不存在）'),
    status: found?.status || row.targetStatus
  }
}

function openCreateStep() {
  Object.assign(edit, {
    visible: true, index: -1, stepCode: 'step' + (steps.value.length + 1),
    targetInterfaceId: null, failurePolicy: 'ABORT', enabled: true, error: ''
  })
}

function openEditStep(index) {
  const row = steps.value[index]
  Object.assign(edit, {
    visible: true, index, stepCode: row.stepCode, targetInterfaceId: row.targetInterfaceId,
    failurePolicy: row.failurePolicy || 'ABORT', enabled: row.enabled !== false, error: ''
  })
}

function confirmEdit() {
  const code = (edit.stepCode || '').trim()
  if (!code) return (edit.error = '步骤名不能为空')
  if (!STEP_CODE_RE.test(code)) return (edit.error = '步骤名仅允许字母 / 数字 / 下划线，长度 1~32')
  if (code.toLowerCase() === RESERVED_NAME) return (edit.error = '步骤名不得为保留名 steps')
  const dup = steps.value.some((s, i) => i !== edit.index && s.stepCode?.toLowerCase() === code.toLowerCase())
  if (dup) return (edit.error = '步骤名重复：' + code)
  if (!edit.targetInterfaceId) return (edit.error = '请选择前置接口')

  const row = {
    seq: edit.index < 0 ? steps.value.length : steps.value[edit.index].seq ?? edit.index,
    stepCode: code,
    targetInterfaceId: edit.targetInterfaceId,
    // 列表页拿到的接口不含 detail 展示字段 → 从候选补上（仅用于展示）
    targetCode: candidates.value.find((i) => i.id === edit.targetInterfaceId)?.code || null,
    targetName: candidates.value.find((i) => i.id === edit.targetInterfaceId)?.name || null,
    failurePolicy: edit.failurePolicy || 'ABORT',
    enabled: edit.enabled !== false
  }
  if (edit.index < 0) steps.value.push(row)
  else steps.value.splice(edit.index, 1, row)
  renumber()
  edit.visible = false
}

function move(index, delta) {
  const next = index + delta
  if (next < 0 || next >= steps.value.length) return
  const arr = steps.value
  const tmp = arr[index]
  arr[index] = arr[next]
  arr[next] = tmp
  renumber()
}

/** 重排 seq（提交时后端也会按 seq 归一并重排为 0..n-1） */
function renumber() {
  steps.value.forEach((s, i) => { s.seq = i })
}

/** 拉取前置接口的出站响应字段 / 出站参数 → 生成可引用的 steps.<step>.<field> 清单 */
async function openFields(row) {
  fields.visible = true
  fields.loading = true
  fields.items = []
  fields.stepCode = row.stepCode
  try {
    const d = await http.get(`/interfaces/${row.targetInterfaceId}`)
    const paths = new Set()
    ;(d.fieldDefs || []).filter((f) => f.kind === 'RESP')
      .forEach((f) => paths.add(`steps.${row.stepCode}.${f.name}`))
    ;(d.params || []).filter((p) => p.side === 'OUT')
      .forEach((p) => paths.add(`steps.${row.stepCode}.${p.name}`))
    fields.items = [...paths].map((path) => ({ path }))
  } catch (e) {
    fields.items = []
  } finally {
    fields.loading = false
  }
}

async function copy(text) {
  try {
    if (navigator?.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    }
    ElMessage.success('已复制：' + text)
  } catch (e) {
    ElMessage.warning('复制失败，请手动选择：' + text)
  }
}
</script>

<style scoped>
.steps-tip { font-size: 12px; color: #6b7280; line-height: 1.7; margin: 4px 0 10px; }
.step-target-name { margin-left: 6px; color: #6b7280; }
.step-item { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.step-item .basic-label { width: 76px; flex: none; }
.step-error { color: #f56c6c; font-size: 12px; }
.field-row { display: flex; align-items: center; justify-content: space-between; padding: 2px 0; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
