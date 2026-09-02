<template>
  <div>
    <el-card shadow="never">
      <div class="panel-head">
        <h3>分组管理（应用 → 分组）</h3>
        <el-button type="primary" @click="openCreate">＋ 新建分组</el-button>
      </div>

      <!-- 树形两级视图（原型平移：应用行 + 缩进分组行） -->
      <div v-loading="loading">
        <template v-for="app in grouped" :key="app.appId">
          <div class="tree-item app-row">
            <span class="arrow">▾</span>
            <span class="name">{{ app.name }}</span>
            <span class="sub">{{ app.appId }} · {{ app.groups.length }} 个分组</span>
          </div>
          <div class="tree-indent">
            <template v-if="app.groups.length === 0">
              <div class="tree-item empty-row"><span class="arrow">·</span><span class="sub">暂无分组</span></div>
            </template>
            <div v-for="g in app.groups" :key="g.id" class="tree-item group-row" @click="openGroupDetail(app, g)">
              <span class="arrow">▸</span>
              <span class="name">分组：{{ g.name }}</span>
              <span class="sub">{{ g.ifaceCount }} 个接口 · 排序 {{ g.sortOrder }}</span>
              <span class="ops">
                <el-button size="small" @click.stop="openEdit(g)">编辑</el-button>
                <el-button size="small" type="danger" @click.stop="remove(g)">删除</el-button>
              </span>
            </div>
          </div>
        </template>
      </div>
    </el-card>

    <!-- 新建 / 编辑分组弹窗（原型字段平移） -->
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
        <div class="hint">分组为纯组织单元，不承载配置（应用 → 分组 → 接口 层级）</div>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 分组详情弹窗（原型平移：列出组内接口，点击跳接口管理详情） -->
    <el-dialog v-model="detail.visible" :title="`分组：${detail.group?.name || ''}（${detail.app?.name || ''}）`" width="640px">
      <div v-loading="detail.loading">
        <template v-if="detail.ifaces.length === 0">
          <div class="empty">该分组下暂无接口</div>
        </template>
        <div v-for="i in detail.ifaces" :key="i.id" class="tree-item iface-row" @click="goIfaceDetail(i)">
          <span class="arrow">·</span>
          <span class="name">{{ i.name }}</span>
          <span class="sub">{{ i.method }} {{ i.path }} · {{ i.protocolIn }} → {{ i.protocolOut }}</span>
          <span class="ops">
            <el-tag size="small" :type="i.status === 'PUBLISHED' ? 'success' : 'info'">
              {{ { DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '下线' }[i.status] }}
            </el-tag>
          </span>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import http from '@/api/http'

const router = useRouter()

const apps = ref([])
const groups = ref([])
const loading = ref(false)
const dialog = reactive({ visible: false, isEdit: false, editId: 0 })
const form = reactive({ appId: '', name: '', sortOrder: 0 })
const detail = reactive({ visible: false, app: null, group: null, ifaces: [], loading: false })

async function load() {
  loading.value = true
  try {
    apps.value = await http.get('/apps')
    groups.value = await http.get('/groups')
  } finally {
    loading.value = false
  }
}
onMounted(load)

/** 树形视图：应用 → 分组（按排序升序，照原型） */
const grouped = computed(() => {
  return apps.value.map((app) => ({
    ...app,
    groups: groups.value
      .filter((g) => g.appId === app.appId)
      .sort((x, y) => (x.sortOrder || 0) - (y.sortOrder || 0))
  }))
})

// ---------- 新建 / 编辑 ----------
function openCreate() {
  Object.assign(form, { appId: '', name: '', sortOrder: 0 })
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

// ---------- 分组详情（组内接口列表，点击跳接口管理详情） ----------
async function openGroupDetail(app, g) {
  detail.visible = true
  detail.app = app
  detail.group = g
  detail.loading = true
  try {
    detail.ifaces = await http.get('/interfaces', { params: { appId: app.appId, groupId: g.id } })
  } finally {
    detail.loading = false
  }
}
function goIfaceDetail(iface) {
  detail.visible = false
  router.push({ path: '/interfaces', query: { id: iface.id } })
}
</script>

<style scoped>
.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.panel-head h3 { margin: 0; font-size: 15px; color: #303133; }

/* 树形两级视图（原型 tree-item 平移） */
.tree-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 13px;
}
.app-row { background: #f5f6fa; margin-bottom: 4px; }
.group-row { cursor: pointer; }
.group-row:hover { background: #f5f7fa; }
.empty-row { cursor: default; }
.arrow { color: #909399; width: 14px; text-align: center; }
.name { color: #303133; }
.app-row .name { font-weight: 600; }
.sub { color: #909399; font-size: 12px; }
.ops { margin-left: auto; }
.tree-indent { padding-left: 24px; }

.iface-row { cursor: pointer; border-bottom: 1px solid #f0f2f5; }
.iface-row:hover { background: #f5f7fa; }
.empty { text-align: center; color: #c0c4cc; padding: 24px 0; }
.hint { font-size: 12px; color: #909399; margin-top: -6px; }
</style>
