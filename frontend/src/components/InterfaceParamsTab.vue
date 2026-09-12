<template>
  <div>
    <div v-for="side in ['IN', 'OUT']" :key="side" class="side-block">
      <div class="side-head">
        <span class="side-name">{{ side === 'IN' ? '入站侧' : '出站侧' }}</span>
        <span class="side-desc">{{ side === 'IN' ? '来源 → 平台' : (form.ifType === 'INBOUND' ? '送达报文（必填）· 平台 → 调用方' : '平台 → 目标') }}</span>
        <div class="subtabs">
          <button type="button" class="subtab" :class="{ active: reqTab[side] === 'params' }"
                  @click="reqTab[side] = 'params'">Params</button>
          <button type="button" class="subtab" :class="{ active: reqTab[side] === 'body' }"
                  @click="reqTab[side] = 'body'">Body</button>
        </div>
      </div>

      <!-- 透传模式开关（仅出站接口的出站侧；透传 = 后端不做字段映射，出站报文原样转发） -->
      <div v-if="side === 'OUT' && form.ifType === 'OUTBOUND'" class="passthrough-bar">
        <span class="side-desc">转发方式</span>
        <el-radio-group v-model="form.passthrough" size="small">
          <el-radio-button :value="true">透传（出站 = 入站原样）</el-radio-button>
          <el-radio-button :value="false">自定义映射</el-radio-button>
        </el-radio-group>
      </div>

      <!-- 透传模式：出站侧编辑区整体隐藏（仅出站接口生效，入站回调的送达报文不受影响） -->
      <div v-if="side === 'OUT' && form.ifType === 'OUTBOUND' && form.passthrough" class="empty-hint">
        透传模式：出站报文 = 入站报文原样转发，无需配置出站侧参数；字段映射不生效
      </div>
      <template v-else>
        <!-- Params 子面板 -->
        <div v-if="reqTab[side] === 'params'">
          <div class="params-toolbar">
            <el-button size="small" @click="openImport(side)">⇪ 快速导入参数</el-button>
            <span class="side-desc">粘贴 JSON 自动推断参数名 / 类型 / 必填 / 示例（也支持 form-urlencoded）</span>
            <span class="pi-spacer" />
            <el-button v-if="importUndo && importUndo.side === side" size="small" text type="primary"
                       @click="undoImport">撤销导入</el-button>
          </div>
          <ParamTable v-if="side === 'IN'" v-model="form.inParams" />
          <ParamTable v-else v-model="form.outParams" />
        </div>

        <!-- Body 子面板 -->
        <div v-else>
          <div class="body-types">
            <button v-for="t in BODY_TYPES" :key="t" type="button" class="subtab"
                    :class="{ active: sideBody(side).type === t }" @click="sideBody(side).type = t">{{ t }}</button>
          </div>
          <div v-if="sideBody(side).type === 'none'" class="empty-hint">无请求体</div>
          <textarea v-else-if="['json','xml'].includes(sideBody(side).type)" v-model="sideBody(side).raw"
                    class="raw-editor" :placeholder="RAW_PLACEHOLDER[sideBody(side).type]"></textarea>
          <template v-else>
            <el-table :data="sideBody(side).formRows" size="small" class="kv-table">
              <el-table-column label="键" min-width="40%">
                <template #default="{ row }"><el-input v-model="row.key" size="small" placeholder="键" /></template>
              </el-table-column>
              <el-table-column label="值">
                <template #default="{ row }"><el-input v-model="row.value" size="small" placeholder="值" /></template>
              </el-table-column>
              <el-table-column width="50">
                <template #default="{ $index }">
                  <el-button link type="danger" @click="sideBody(side).formRows.splice($index, 1)">×</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button size="small" class="add-btn" @click="sideBody(side).formRows.push({ key: '', value: '' })">＋ 添加</el-button>
          </template>
        </div>
      </template>
    </div>

    <!-- 请求参数快速导入（D1–D6 按评审推荐：覆盖同名并追加 + 一次撤销快照） -->
    <ParamImportDialog v-model="importDialog.visible" :side="importDialog.side"
                       :side-label="importDialog.side === 'IN' ? '入站侧' : '出站侧'"
                       :existing-names="sideParams(importDialog.side).map((p) => p.name).filter(Boolean)"
                       :body-raw="sideBodyRaw(importDialog.side)"
                       @import="applyImport" />
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ParamTable from '@/components/ParamTable.vue'
import ParamImportDialog from '@/components/ParamImportDialog.vue'
import { mergeParams } from '@/utils/paramImport.mjs'

// 请求参数 tab（2026-09-12 从 Interfaces.vue 拆出，1458 行巨型单文件治理）：
// 入站/出站两侧的 Params / Body 编辑 + 快速导入（含撤销）。
// 约定：直接编辑父组件传入的 reactive form（ParamTable 需要真实数组引用做原地编辑），故无 emit。
const props = defineProps({
  form: { type: Object, required: true }
})
const form = props.form

const BODY_TYPES = ['none', 'form-data', 'x-www-form-urlencoded', 'json', 'xml']
/** raw 编辑器占位示例（属性内嵌多行字符串含双引号会破坏模板解析，提为常量） */
const RAW_PLACEHOLDER = {
  json: '{\n  "key": "value"\n}',
  xml: '<root></root>'
}
const reqTab = reactive({ IN: 'params', OUT: 'params' })

/** 某侧参数数组（真实引用，ParamTable 原地编辑同一数组） */
function sideParams(side) {
  return side === 'IN' ? form.inParams : form.outParams
}

/** 某侧请求体模板（导入弹窗「从本侧请求体带入」的数据源） */
function sideBodyRaw(side) {
  return side === 'IN' ? form.inBodyRaw : form.outBodyRaw
}

/** 某侧 Body 编辑视图（getter/setter 映射到 form 的扁平字段 + formRows 数组引用） */
function sideBody(side) {
  const key = side === 'IN' ? 'in' : 'out'
  return {
    get type() {
      return form[key + 'BodyType']
    },
    set type(v) {
      form[key + 'BodyType'] = v
    },
    get raw() {
      return form[key + 'BodyRaw']
    },
    set raw(v) {
      form[key + 'BodyRaw'] = v
    },
    formRows: form[key + 'FormRows']
  }
}

// ---------- 快速导入（覆盖同名并追加 / 清空后导入 + 一次撤销快照） ----------
const importDialog = reactive({ visible: false, side: 'IN' })
const importUndo = ref(null)

function openImport(side) {
  importDialog.side = side
  importDialog.visible = true
  importUndo.value = null
}

function applyImport({ params, mergeMode }) {
  const side = importDialog.side
  const target = sideParams(side)
  importUndo.value = { side, rows: target.map((r) => ({ ...r })) }
  const stat = mergeParams(target, params, mergeMode)   // 合并逻辑在 utils/paramImport.mjs（有单测）
  ElMessage.success(`已导入 ${stat.total} 条参数（覆盖 ${stat.overwritten} / 新增 ${stat.added}，`
    + `${side === 'IN' ? '入站侧' : '出站侧'}）`)
}

function undoImport() {
  if (!importUndo.value) return
  const target = sideParams(importUndo.value.side)
  target.splice(0, target.length, ...importUndo.value.rows)
  importUndo.value = null
  ElMessage.success('已撤销导入')
}
</script>

<style scoped>
.side-block { border: 1px solid #e8eaf0; border-radius: 6px; padding: 10px 12px; margin-bottom: 12px; }
.side-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.side-name { font-weight: 600; font-size: 14px; color: #303133; }
.side-desc { font-size: 12px; color: #909399; }
.subtabs { margin-left: auto; display: flex; gap: 4px; }
.subtab {
  border: 1px solid #dcdfe6; background: #fff; color: #606266;
  font-size: 12px; padding: 4px 12px; border-radius: 4px; cursor: pointer;
}
.subtab.active { background: #1F2739; border-color: #1F2739; color: #fff; }
.passthrough-bar {
  display: flex; align-items: center; gap: 10px; margin-bottom: 10px;
  padding: 6px 10px; background: #f5f6fa; border-radius: 4px;
}
.body-types { display: flex; gap: 4px; margin-bottom: 10px; }
.raw-editor {
  width: 100%; min-height: 140px; box-sizing: border-box; background: #282C34; color: #abb2bf;
  border: none; border-radius: 6px; padding: 12px;
  font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 13px; line-height: 1.6; resize: vertical;
}
.raw-editor:focus { outline: 1px solid #4A90D9; }
.kv-table { margin-bottom: 8px; }
.empty-hint { text-align: center; color: #c0c4cc; font-size: 13px; padding: 18px 0; }
.add-btn { margin-top: 4px; }
.params-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.pi-spacer { flex: 1; }
</style>
