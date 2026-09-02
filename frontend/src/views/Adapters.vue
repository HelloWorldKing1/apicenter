<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-radio-group v-model="filterType" @change="onFilterChange">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="auth">鉴权</el-radio-button>
          <el-radio-button value="protocol">协议</el-radio-button>
          <el-radio-button value="message">报文</el-radio-button>
        </el-radio-group>
        <el-button type="primary" @click="openCreate">＋ 新建适配器</el-button>
      </div>

      <el-table :data="paged" v-loading="loading">
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

      <!-- 分页（原型每页 5 条） -->
      <div class="pager">
        <el-pagination v-model:current-page="page" :page-size="PAGE_SIZE" :total="adapters.length"
                       layout="total, prev, pager, next" @current-change="() => {}" />
      </div>
    </el-card>

    <!-- 新建 / 编辑（参数按 impl 元数据动态渲染，与内联创建共用编辑器组件） -->
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
        <el-form-item label="实现类与参数" required>
          <AdapterParamsEditor v-model="paramsModel" :impls="impls" :type="form.type" style="width: 100%" />
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="form.version" placeholder="默认 1.0" style="width: 160px" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
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
import AdapterParamsEditor from '@/components/AdapterParamsEditor.vue'

const PAGE_SIZE = 5 // 原型每页 5 条

const adapters = ref([])
const impls = ref([])
const filterType = ref('')
const loading = ref(false)
const page = ref(1)
const dialog = reactive({ visible: false, isEdit: false, editId: '' })
const form = reactive({ id: '', name: '', type: 'auth', version: '1.0', enabled: true })
const paramsModel = ref({ impl: '', params: {} })

const paged = computed(() => {
  const start = (page.value - 1) * PAGE_SIZE
  return adapters.value.slice(start, start + PAGE_SIZE)
})

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

function onFilterChange() {
  page.value = 1
  load()
}

function openCreate() {
  Object.assign(form, { id: '', name: '', type: 'auth', version: '1.0', enabled: true })
  paramsModel.value = { impl: '', params: {} }
  dialog.isEdit = false
  dialog.visible = true
}
function openEdit(row) {
  Object.assign(form, { id: row.id, name: row.name, type: row.type, version: row.version, enabled: row.enabled })
  let params = {}
  try {
    params = JSON.parse(row.params || '{}')
  } catch { /* 忽略非法历史数据 */ }
  paramsModel.value = { impl: row.impl, params }
  dialog.isEdit = true
  dialog.editId = row.id
  dialog.visible = true
}
function onTypeChange() {
  paramsModel.value = { impl: '', params: {} }
}

async function save() {
  const payload = { ...form, impl: paramsModel.value.impl, params: JSON.stringify(paramsModel.value.params) }
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
.pager { margin-top: 14px; display: flex; justify-content: flex-end; }
</style>
