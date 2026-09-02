<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filterApp" clearable placeholder="全部应用" style="width: 240px" @change="load">
          <el-option v-for="a in apps" :key="a.appId" :label="`${a.name}（${a.appId}）`" :value="a.appId" />
        </el-select>
        <el-button type="primary" @click="openCreate">＋ 新建分组</el-button>
      </div>

      <!-- 应用 → 分组两级视图(原型分组页平移) -->
      <el-table :data="grouped" v-loading="loading" row-key="key" default-expand-all>
        <el-table-column type="expand">
          <template #default="{ row }">
            <el-table :data="row.groups" size="small" style="padding-left: 40px">
              <el-table-column prop="name" label="分组名称" width="220" />
              <el-table-column label="排序" width="90"><template #default="{ row: g }">{{ g.sortOrder }}</template></el-table-column>
              <el-table-column label="接口数" width="90"><template #default="{ row: g }">{{ g.ifaceCount }}</template></el-table-column>
              <el-table-column label="创建时间" width="180">
                <template #default="{ row: g }">{{ fmt(g.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="操作">
                <template #default="{ row: g }">
                  <el-button link type="primary" @click="openEdit(g)">编辑</el-button>
                  <el-button link type="danger" @click="remove(g)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="应用" width="260">
          <template #default="{ row }">{{ row.name }}（{{ row.appId }}）</template>
        </el-table-column>
        <el-table-column label="分组数" width="100">
          <template #default="{ row }">{{ row.groups.length }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建 / 编辑分组弹窗 -->
    <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? '编辑分组' : '新建分组'" width="480px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="所属应用" required>
          <el-select v-model="form.appId" :disabled="dialog.isEdit" placeholder="选择应用" style="width: 100%">
            <el-option v-for="a in apps" :key="a.appId" :label="`${a.name}（${a.appId}）`" :value="a.appId" />
          </el-select>
        </el-form-item>
        <el-form-item label="分组名称" required>
          <el-input v-model="form.name" placeholder="应用内唯一" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" />
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

const apps = ref([])
const groups = ref([])
const filterApp = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    apps.value = await http.get('/apps')
    groups.value = await http.get('/groups', { params: { appId: filterApp.value || undefined } })
  } finally {
    loading.value = false
  }
}
onMounted(load)

/** 两级视图：按应用组织分组 */
const grouped = computed(() => {
  const map = {}
  for (const g of groups.value) {
    ;(map[g.appId] ||= []).push(g)
  }
  return apps.value
    .filter((a) => !filterApp.value || a.appId === filterApp.value)
    .map((a) => ({ ...a, key: a.appId, groups: map[a.appId] || [] }))
})

// ---------- 新建 / 编辑 ----------
const dialog = reactive({ visible: false, isEdit: false, editId: 0 })
const form = reactive({ appId: '', name: '', sortOrder: 0 })

function openCreate() {
  Object.assign(form, { appId: filterApp.value || '', name: '', sortOrder: 0 })
  dialog.isEdit = false
  dialog.visible = true
}
function openEdit(g) {
  Object.assign(form, { appId: g.appId, name: g.name, sortOrder: g.sortOrder })
  dialog.isEdit = true
  dialog.editId = g.id
  dialog.visible = true
}
async function save() {
  if (dialog.isEdit) {
    await http.put(`/groups/${dialog.editId}`, { ...form })
  } else {
    await http.post('/groups', { ...form })
  }
  ElMessage.success('保存成功')
  dialog.visible = false
  load()
}
async function remove(g) {
  await ElMessageBox.confirm(`删除分组「${g.name}」？`, '确认', { type: 'warning' })
  await http.delete(`/groups/${g.id}`)
  ElMessage.success('已删除')
  load()
}

const fmt = (t) => (t ? t.replace('T', ' ').slice(0, 19) : '—')
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; margin-bottom: 14px; }
</style>
