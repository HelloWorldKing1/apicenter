<template>
  <div>
    <div class="toolbar">
      <div class="toolbar-filters">
        <el-input v-model="keyword" placeholder="标识 / 名称" clearable style="width: 200px" @keyup.enter="load" />
        <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option label="启用" value="ENABLED" />
          <el-option label="停用" value="DISABLED" />
        </el-select>
        <el-button @click="load">查询</el-button>
      </div>
      <el-button type="primary" :disabled="readOnly" @click="openCreate">＋ 新建调用方</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" size="small" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="clientId" label="调用方标识" width="170" />
      <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
      <el-table-column prop="contact" label="联系人" width="100" />
      <el-table-column label="鉴权方式" width="160">
        <template #default="{ row }">
          {{ row.authAdapterId ? adapterLabel(row.authAdapterId) : '（未配置）' }}
        </template>
      </el-table-column>
      <el-table-column label="凭证" width="180">
        <template #default="{ row }">
          <el-tag v-for="k in row.activeCredentialKinds" :key="k" size="small" style="margin-right: 4px">
            {{ kindLabel(k) }}
          </el-tag>
          <span v-if="!row.activeCredentialKinds?.length" class="muted">无 ACTIVE 凭证</span>
        </template>
      </el-table-column>
      <el-table-column label="IP 名单" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="!row.ipWhitelist && !row.ipBlacklist" class="muted">不限</span>
          <span v-else>
            <span v-if="row.ipWhitelist">白：{{ row.ipWhitelist }}</span>
            <span v-if="row.ipBlacklist"><br />黑：{{ row.ipBlacklist }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="QPS / 日配额" width="120">
        <template #default="{ row }">{{ row.qpsLimit || '—' }} / {{ row.dailyQuota || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
            {{ row.status === 'ENABLED' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="readOnly" @click="openEdit(row)">编辑</el-button>
          <el-button link :type="row.status === 'ENABLED' ? 'warning' : 'success'" :disabled="readOnly"
                     @click="toggle(row)">{{ row.status === 'ENABLED' ? '停用' : '启用' }}</el-button>
          <el-button link type="primary" @click="openCredentials(row)">凭证</el-button>
          <el-button link type="danger" :disabled="readOnly" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? `编辑调用方 · ${form.clientId}` : '新建调用方'"
               width="680px" top="6vh">
      <div class="grid">
        <div class="item">
          <span class="label">调用方标识</span>
          <el-input v-model="form.clientId" :disabled="dialog.isEdit" placeholder="3~32 位大写字母/数字/-/_，如 ERP-PROD" />
        </div>
        <div class="item">
          <span class="label">名称</span>
          <el-input v-model="form.name" placeholder="如：ERP 生产环境" />
        </div>
        <div class="item">
          <span class="label">联系人</span>
          <el-input v-model="form.contact" placeholder="可选" />
        </div>
        <div class="item">
          <span class="label">鉴权适配器</span>
          <el-select v-model="form.authAdapterId" clearable placeholder="未配置（启用后 fail-closed 拒绝）" style="width: 100%">
            <el-option v-for="a in authAdapters" :key="a.id" :label="`${a.name}（${a.impl}）`" :value="a.id" />
          </el-select>
        </div>
        <div class="item">
          <span class="label">IP 白名单</span>
          <el-input v-model="form.ipWhitelist" placeholder="英文逗号分隔；空 = 不限" />
        </div>
        <div class="item">
          <span class="label">IP 黑名单</span>
          <el-input v-model="form.ipBlacklist" placeholder="英文逗号分隔；优先于白名单" />
        </div>
        <div class="item">
          <span class="label">QPS 上限</span>
          <el-input-number v-model="form.qpsLimit" :min="0" style="width: 100%" />
        </div>
        <div class="item">
          <span class="label">日调用量上限</span>
          <el-input-number v-model="form.dailyQuota" :min="0" style="width: 100%" />
        </div>
        <div class="item" style="grid-column: 1 / -1">
          <span class="label">描述</span>
          <el-input v-model="form.desc" placeholder="可选" />
        </div>
      </div>
      <div class="hint">
        鉴权方式决定「入站鉴权」怎么验：API Key / HMAC / Bearer 需要凭证（见列表「凭证」），仅 IP 名单方式不需要凭证。
        <b>未配置鉴权方式时，平台在「调用方鉴权」强制模式下会 fail-closed 拒绝（40108）。</b>
      </div>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 凭证管理 -->
    <el-drawer v-model="cred.visible" :title="`凭证管理 · ${cred.clientId}`" size="620px">
      <div class="hint" style="margin-bottom: 8px">
        凭证在库内 <b>AES-256-GCM 加密</b>存储，管理面<b>永不回显明文</b>（只显示尾 4 位指纹）；
        「新增」时生成的明文<b>仅回显一次</b>，请立即交给调用方配置。
      </div>
      <el-table :data="cred.list" size="small" border>
        <el-table-column prop="kind" label="类型" width="110">
          <template #default="{ row }">{{ kindLabel(row.kind) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : row.status === 'ROTATING' ? 'warning' : 'info'">
              {{ row.status }}{{ row.expired ? '·已过期' : '' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="指纹" width="90">
          <template #default="{ row }">****{{ row.fingerprint }}</template>
        </el-table-column>
        <el-table-column label="操作">
          <template #default="{ row }">
            <el-button v-if="row.status === 'ROTATING'" link type="primary" @click="activate(row)">激活</el-button>
            <el-button v-if="row.status === 'ROTATING'" link type="success" @click="finishRotation(row)">完成轮换</el-button>
            <el-button v-if="row.status !== 'RETIRED'" link type="danger" @click="retire(row)">吊销</el-button>
            <el-button v-else link type="danger" @click="removeCred(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="cred-actions">
        <el-select v-model="cred.newKind" style="width: 180px">
          <el-option v-for="k in KINDS" :key="k" :label="kindLabel(k)" :value="k" />
        </el-select>
        <el-button type="primary" :disabled="readOnly" @click="prepare">生成新凭证（平台随机）</el-button>
      </div>
      <div v-if="cred.issued" class="issued">
        <b>新凭证明文（仅此一次，请立即保存）：</b>
        <pre>{{ cred.issued }}</pre>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 调用方管理（平台入站鉴权 B4，《入站鉴权设计方案》v1.1 §7.2）。
 *
 * 职责：调用方（平台客户）CRUD + 启停用 + 凭证管理（与「应用凭证」同一套 M0-04 状态机语义）。
 * 只读角色（VIEWER）隐藏写操作（服务端由 `AdminAuthFilter` 权威拦截 40302）。
 * 审计查询见「接口监控 → 接入鉴权」Tab。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/api/http'
import { isReadOnly } from '@/utils/roles.mjs'
import { authStore } from '@/utils/auth.mjs'

const KINDS = ['API_KEY', 'HMAC_SECRET', 'BEARER_TOKEN', 'BASIC']
const KIND_LABEL = {
  API_KEY: 'API Key',
  HMAC_SECRET: 'HMAC 密钥',
  BEARER_TOKEN: 'Bearer Token',
  BASIC: 'Basic',
}

const rows = ref([])
const loading = ref(false)
const keyword = ref('')
const filterStatus = ref('')
const adapters = ref([])
const meRole = computed(() => authStore.getUser()?.role)

const readOnly = computed(() => isReadOnly(meRole.value))
const authAdapters = computed(() => adapters.value.filter((a) => a.type === 'auth' && a.enabled))

const dialog = reactive({ visible: false, isEdit: false })
const form = reactive({
  clientId: '', name: '', contact: '', authAdapterId: null,
  ipWhitelist: '', ipBlacklist: '', qpsLimit: 0, dailyQuota: 0, desc: '',
})

const cred = reactive({
  visible: false, clientId: '', list: [], newKind: 'API_KEY', issued: '',
})

function kindLabel(kind) {
  return KIND_LABEL[kind] || kind
}

function adapterLabel(id) {
  const a = adapters.value.find((x) => x.id === id)
  return a ? a.name : id
}

async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams()
    if (keyword.value) params.set('keyword', keyword.value)
    if (filterStatus.value) params.set('status', filterStatus.value)
    rows.value = await http.get(`/clients?${params.toString()}`)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, {
    clientId: '', name: '', contact: '', authAdapterId: null,
    ipWhitelist: '', ipBlacklist: '', qpsLimit: 0, dailyQuota: 0, desc: '',
  })
  dialog.isEdit = false
  dialog.visible = true
}

function openEdit(row) {
  Object.assign(form, {
    clientId: row.clientId, name: row.name, contact: row.contact || '',
    authAdapterId: row.authAdapterId, ipWhitelist: row.ipWhitelist || '',
    ipBlacklist: row.ipBlacklist || '', qpsLimit: row.qpsLimit || 0,
    dailyQuota: row.dailyQuota || 0, desc: row.desc || '',
  })
  dialog.isEdit = true
  dialog.visible = true
}

async function save() {
  const body = {
    clientId: form.clientId,
    name: form.name,
    contact: form.contact,
    authAdapterId: form.authAdapterId,
    ipWhitelist: form.ipWhitelist,
    ipBlacklist: form.ipBlacklist,
    qpsLimit: form.qpsLimit,
    dailyQuota: form.dailyQuota,
    desc: form.desc,
  }
  if (dialog.isEdit) {
    await http.put(`/clients/${form.clientId}`, body)
  } else {
    await http.post('/clients', body)
  }
  ElMessage.success('已保存')
  dialog.visible = false
  await load()
}

async function toggle(row) {
  const action = row.status === 'ENABLED' ? 'disable' : 'enable'
  await http.post(`/clients/${row.clientId}/${action}`)
  ElMessage.success(action === 'disable' ? '已停用（该调用方的请求将立即被拒 40107）' : '已启用')
  await load()
}

async function remove(row) {
  await ElMessageBox.confirm(
    `删除调用方 ${row.name}（${row.clientId}）？其凭证将级联删除；审计记录保留`, '确认', { type: 'warning' })
  await http.delete(`/clients/${row.clientId}`)
  ElMessage.success('已删除')
  await load()
}

// ---------- 凭证 ----------

async function openCredentials(row) {
  cred.clientId = row.clientId
  cred.issued = ''
  cred.visible = true
  await loadCreds()
}

async function loadCreds() {
  cred.list = await http.get(`/clients/${cred.clientId}/credentials`)
}

async function prepare() {
  const res = await http.post(`/clients/${cred.clientId}/credentials`, { kind: cred.newKind })
  cred.issued = res.plaintext
  ElMessage.success('已生成（明文仅显示一次，请立即保存）')
  await loadCreds()
}

async function activate(row) {
  await http.post(`/clients/${cred.clientId}/credentials/${row.id}/activate`)
  ElMessage.success('已激活：旧凭证转为 ROTATING（并存 24h）')
  await loadCreds()
}

async function finishRotation(row) {
  await http.post(`/clients/${cred.clientId}/credentials/${row.id}/finish-rotation`)
  ElMessage.success('已完成轮换（ROTATING → RETIRED）')
  await loadCreds()
}

async function retire(row) {
  const warning = await http.post(`/clients/${cred.clientId}/credentials/${row.id}/retire`)
  ElMessage.warning(warning || '已吊销')
  await loadCreds()
}

async function removeCred(row) {
  await ElMessageBox.confirm('删除该已失效凭证记录？此操作不可恢复', '确认', { type: 'warning' })
  await http.delete(`/clients/${cred.clientId}/credentials/${row.id}`)
  ElMessage.success('已删除')
  await loadCreds()
}

onMounted(async () => {
  adapters.value = await http.get('/adapters').catch(() => [])
  if (!Array.isArray(adapters.value)) adapters.value = []
  await load()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; margin-bottom: 14px; }
.toolbar-filters { display: flex; gap: 10px; }
.muted { color: #c0c4cc; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.item { display: flex; flex-direction: column; gap: 4px; }
.label { font-size: 12px; color: #606266; }
.hint { font-size: 12px; color: #909399; line-height: 1.7; margin-top: 10px; }
.cred-actions { margin-top: 14px; display: flex; gap: 8px; }
.issued { margin-top: 12px; padding: 10px; background: #fdf6ec; border: 1px solid #f5dab1; border-radius: 4px; }
.issued pre { margin: 6px 0 0; font-family: 'SF Mono', Menlo, Consolas, monospace; word-break: break-all; color: #b88230; }
</style>
