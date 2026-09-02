<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-radio-group v-model="filterType" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="auth">鉴权</el-radio-button>
          <el-radio-button value="protocol">协议</el-radio-button>
          <el-radio-button value="message">报文</el-radio-button>
        </el-radio-group>
        <el-button type="primary" @click="openCreate">＋ 新建适配器</el-button>
      </div>

      <el-table :data="adapters" v-loading="loading">
        <el-table-column prop="id" label="标识" width="110" />
        <el-table-column prop="name" label="名称" width="180" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ { auth: '鉴权', protocol: '协议', message: '报文' }[row.type] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="impl" label="实现类" width="260" show-overflow-tooltip />
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="(v) => toggle(row, v)" />
          </template>
        </el-table-column>
        <el-table-column label="参数(JSON)" show-overflow-tooltip>
          <template #default="{ row }">{{ row.params || '{}' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建 / 编辑（参数按 impl 元数据动态渲染，原型 ADAPTER_FIELDS 模式） -->
    <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? '编辑适配器' : '新建适配器'" width="620px">
      <el-form :model="form" label-width="150px">
        <el-form-item label="标识" required>
          <el-input v-model="form.id" :disabled="dialog.isEdit" placeholder="如 ADP-102" />
        </el-form-item>
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" :disabled="dialog.isEdit" @change="onTypeChange">
            <el-option label="鉴权 auth" value="auth" />
            <el-option label="协议 protocol" value="protocol" />
            <el-option label="报文 message" value="message" />
          </el-select>
        </el-form-item>
        <el-form-item label="实现类" required>
          <el-select v-model="form.impl" :disabled="dialog.isEdit" @change="onImplChange" style="width: 100%">
            <el-option v-for="m in implOptions" :key="m.impl" :label="`${m.name}（${m.impl}）`" :value="m.impl" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="form.version" placeholder="默认 1.0" style="width: 160px" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>

        <!-- 参数动态表单 -->
        <template v-if="currentMeta">
          <el-divider content-position="left">参数配置（{{ currentMeta.name }}）</el-divider>
          <el-form-item v-for="f in currentMeta.fields" :key="f.key" :label="f.label"
                        :required="f.required">
            <el-select v-if="f.kind === 'select'" v-model="params[f.key]" clearable style="width: 100%">
              <el-option v-for="o in f.options" :key="o" :label="o" :value="o" />
            </el-select>
            <el-input-number v-else-if="f.kind === 'number'" v-model="params[f.key]" style="width: 100%" />
            <el-switch v-else-if="f.kind === 'switch'" v-model="params[f.key]" />
            <el-input v-else-if="f.kind === 'textarea'" v-model="params[f.key]" type="textarea" :rows="3" />
            <el-input v-else-if="f.kind === 'codeMap'" v-model="params[f.key]" placeholder="上游码→平台码，逗号分隔" />
            <el-input v-else-if="f.kind === 'secret'" disabled placeholder="凭证统一在应用凭证管理中配置" />
            <el-input v-else v-model="params[f.key]" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/api/http'

const adapters = ref([])
const impls = ref([])
const filterType = ref('')
const loading = ref(false)
const dialog = reactive({ visible: false, isEdit: false, editId: '' })
const form = reactive({ id: '', name: '', type: 'auth', impl: '', version: '1.0', enabled: true })
const params = reactive({})

async function load() {
  loading.value = true
  try {
    adapters.value = await http.get('/adapters', { params: { type: filterType.value || undefined } })
    impls.value = await http.get('/adapters/impls')
  } finally {
    loading.value = false
  }
}
onMounted(load)

const implOptions = computed(() => impls.value.filter((m) => m.type === form.type))
const currentMeta = computed(() => impls.value.find((m) => m.impl === form.impl))

function openCreate() {
  Object.assign(form, { id: '', name: '', type: 'auth', impl: '', version: '1.0', enabled: true })
  clearParams()
  dialog.isEdit = false
  dialog.visible = true
}
function openEdit(row) {
  Object.assign(form, { id: row.id, name: row.name, type: row.type, impl: row.impl, version: row.version, enabled: row.enabled })
  clearParams()
  try {
    Object.assign(params, JSON.parse(row.params || '{}'))
  } catch { /* 忽略非法历史数据 */ }
  dialog.isEdit = true
  dialog.editId = row.id
  dialog.visible = true
}
function clearParams() {
  Object.keys(params).forEach((k) => delete params[k])
}
function onTypeChange() {
  form.impl = ''
  clearParams()
}
function onImplChange() {
  clearParams()
}

async function save() {
  const payload = { ...form, params: JSON.stringify(params) }
  if (dialog.isEdit) {
    await http.put(`/adapters/${dialog.editId}`, payload)
  } else {
    await http.post('/adapters', payload)
  }
  ElMessage.success('保存成功')
  dialog.visible = false
  load()
}
async function toggle(row, enabled) {
  await http.post(`/adapters/${row.id}/${enabled ? 'enable' : 'disable'}`)
  ElMessage.success('已' + (enabled ? '启用' : '停用'))
  load()
}
async function remove(row) {
  await ElMessageBox.confirm(`删除适配器「${row.name}」？引用它的应用/接口将回退为无鉴权/平台默认`, '确认', { type: 'warning' })
  await http.delete(`/adapters/${row.id}`)
  ElMessage.success('已删除')
  load()
}
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; margin-bottom: 14px; }
</style>
