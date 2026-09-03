<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <div class="toolbar-filters">
          <el-input v-model="keyword" placeholder="按名称 / 标识搜索" clearable style="width: 220px" @input="onKeywordInput" />
          <el-select v-model="filterStatus" placeholder="状态" clearable style="width: 130px" @change="load">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
            <el-option label="注销" value="CANCELLED" />
          </el-select>
        </div>
        <el-button type="primary" @click="openCreate">＋ 新建应用</el-button>
      </div>

      <el-table :data="apps" v-loading="loading" @row-click="openDetail">
        <el-table-column prop="appId" label="应用标识" width="180" />
        <el-table-column prop="name" label="应用名称" width="160" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="服务地址" show-overflow-tooltip />
        <el-table-column label="分组 / 接口" width="110">
          <template #default="{ row }">{{ row.groupCount }} / {{ row.ifaceCount }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="修改时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openEdit(row)">编辑</el-button>
            <el-button link type="success" v-if="['DRAFT','DISABLED'].includes(row.status)"
                       @click.stop="doAction(row, 'enable')">启用</el-button>
            <el-button link type="warning" v-if="row.status === 'ENABLED'"
                       @click.stop="doAction(row, 'disable')">停用</el-button>
            <el-button link type="info" v-if="row.status === 'DISABLED'"
                       @click.stop="doAction(row, 'cancel')">注销</el-button>
            <el-button link type="danger" @click.stop="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建 / 编辑弹窗(原型应用弹窗平移) -->
    <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? '编辑应用' : '新建应用'" width="640px">
      <el-form :model="form" label-width="130px">
        <el-form-item label="应用标识" required>
          <el-input v-model="form.appId" :disabled="dialog.isEdit" placeholder="全局唯一,如 TENCENT-CLOUD" />
        </el-form-item>
        <el-form-item label="应用名称" required>
          <el-input v-model="form.name" placeholder="供应商名称,如 FastMoss" />
        </el-form-item>
        <el-form-item label="服务地址 base-url">
          <el-input v-model="form.baseUrl" placeholder="供应商 API 根地址,如 https://openapi.fastmoss.com" />
        </el-form-item>
        <el-form-item label="供应商签名适配器">
          <div class="adapter-pick">
            <el-select v-model="form.authAdapterId" clearable placeholder="出站鉴权(应用级默认,接口可覆盖)">
              <el-option v-for="a in authAdapters" :key="a.id" :label="`${a.name}（${a.impl}）`" :value="a.id" />
            </el-select>
            <el-button size="small" @click="openInlineAdapter('auth', 'authAdapterId')">＋ 自定义</el-button>
          </div>
        </el-form-item>
        <el-form-item label="回调验签适配器">
          <div class="adapter-pick">
            <el-select v-model="form.callbackAuthAdapterId" clearable placeholder="仅入站回调接口生效">
              <el-option v-for="a in authAdapters" :key="a.id" :label="`${a.name}（${a.impl}）`" :value="a.id" />
            </el-select>
            <el-button size="small" @click="openInlineAdapter('auth', 'callbackAuthAdapterId')">＋ 自定义</el-button>
          </div>
        </el-form-item>
        <el-form-item label="默认报文适配器">
          <div class="adapter-pick">
            <el-select v-model="form.defaultMessageAdapterId" clearable placeholder="默认直通(Noop)">
              <el-option v-for="a in messageAdapters" :key="a.id" :label="`${a.name}（${a.impl}）`" :value="a.id" />
            </el-select>
            <el-button size="small" @click="openInlineAdapter('message', 'defaultMessageAdapterId')">＋ 自定义</el-button>
          </div>
        </el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contact" /></el-form-item>
        <el-form-item label="QPS 限流">
          <el-input-number v-model="form.qpsLimit" :min="0" placeholder="空/0 = 不限" />
        </el-form-item>
        <el-form-item label="日调用量上限">
          <el-input-number v-model="form.dailyQuota" :min="0" placeholder="空/0 = 不限" />
        </el-form-item>
        <el-form-item label="IP 白名单">
          <el-input v-model="form.ipWhitelist" type="textarea" :rows="2" placeholder="英文逗号分隔,空 = 不限制" />
        </el-form-item>
        <el-form-item label="IP 黑名单">
          <el-input v-model="form.ipBlacklist" type="textarea" :rows="2" placeholder="英文逗号分隔,空 = 不限制" />
        </el-form-item>
        <el-form-item label="描述"><el-input v-model="form.desc" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 凭证录入弹窗（更新/重置，支持出站签名与回调验签两类） -->
    <el-dialog v-model="credDialog.visible" :title="credDialog.mode === 'update' ? '更新凭证（旧凭证并存 24h）' : '重置凭证（旧凭证立即失效）'" width="520px">
      <el-form label-width="100px">
        <el-form-item label="凭证类型" required>
          <el-radio-group v-model="credDialog.kind">
            <el-radio value="OUTBOUND">出站签名</el-radio>
            <el-radio value="CALLBACK">回调验签</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="凭证内容" required>
          <el-input v-model="credDialog.credential" type="textarea" :rows="4"
                    placeholder="供应商提供的密钥 / token（保存后仅显示尾 4 位指纹，永不回显明文）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="credDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveCred">保存</el-button>
      </template>
    </el-dialog>

    <!-- 内联创建适配器（原型「＋ 自定义鉴权适配器」平移，保存后回填下拉） -->
    <el-dialog v-model="inlineDialog.visible" title="自定义适配器" width="560px" append-to-body>
      <el-form label-width="120px">
        <el-form-item label="适配器名称" required>
          <el-input v-model="inlineDialog.name" placeholder="如 我的 Bearer Token" />
        </el-form-item>
        <el-form-item label="实现类与参数" required>
          <AdapterParamsEditor v-model="inlineDialog.paramsModel" :impls="impls" :type="inlineDialog.type" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inlineDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveInlineAdapter">保存并选用</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉(含凭证遮显 + 轮换操作) -->
    <el-drawer v-model="detail.visible" :title="`应用详情 · ${detail.row.appId || ''}`" size="560px">
      <el-descriptions :column="2" border v-if="detail.row.appId">
        <el-descriptions-item label="名称">{{ detail.row.name }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusType(detail.row.status)">{{ statusText(detail.row.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="服务地址" :span="2">{{ detail.row.baseUrl || '—' }}</el-descriptions-item>
        <el-descriptions-item label="QPS 限流">{{ detail.row.qpsLimit || '不限' }}</el-descriptions-item>
        <el-descriptions-item label="日配额">{{ detail.row.dailyQuota || '不限' }}</el-descriptions-item>
        <el-descriptions-item label="IP 白名单" :span="2">{{ detail.row.ipWhitelist || '—' }}</el-descriptions-item>
        <el-descriptions-item label="IP 黑名单" :span="2">{{ detail.row.ipBlacklist || '—' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail.row.createdAt?.replace('T', ' ').slice(0, 19) || '—' }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ detail.row.desc || '—' }}</el-descriptions-item>
      </el-descriptions>

      <h4>凭证（遮显，永不回显明文）</h4>
      <p class="cred-hint">出站签名 / 回调验签的密钥统一在此维护（适配器只配置用法，如 Header 名、前缀、算法）</p>
      <el-table :data="detail.credentials" size="small">
        <el-table-column prop="kind" label="类型" width="110">
          <template #default="{ row }">{{ row.kind === 'OUTBOUND' ? '出站签名' : '回调验签' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
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
        <el-button size="small" @click="openCredDialog('update')">更新凭证（供应商已换密钥）</el-button>
        <el-button size="small" @click="openCredDialog('reset')">重置（应急换新）</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/api/http'
import AdapterParamsEditor from '@/components/AdapterParamsEditor.vue'

// ---------- 列表 ----------
const apps = ref([])
const loading = ref(false)
const keyword = ref('')
const filterStatus = ref('')
const authAdapters = ref([])
const messageAdapters = ref([])
const impls = ref([])

// 搜索防抖（评审中危 #10）
let keywordTimer
function onKeywordInput() {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(load, 300)
}

async function load() {
  loading.value = true
  try {
    apps.value = await http.get('/apps', { params: { keyword: keyword.value || undefined, status: filterStatus.value || undefined } })
    const all = await http.get('/adapters')
    authAdapters.value = all.filter(a => a.type === 'auth' && a.enabled)
    messageAdapters.value = all.filter(a => a.type === 'message' && a.enabled)
    impls.value = await http.get('/adapters/impls')
  } finally {
    loading.value = false
  }
}
onMounted(load)

// ---------- 内联创建适配器（原型平移：保存为适配器并回填下拉） ----------
const inlineDialog = reactive({
  visible: false,
  name: '',
  type: 'auth',
  targetField: 'authAdapterId',
  paramsModel: { impl: '', params: {} }
})

function openInlineAdapter(type, targetField) {
  inlineDialog.name = ''
  inlineDialog.type = type
  inlineDialog.targetField = targetField
  inlineDialog.paramsModel = { impl: '', params: {} }
  inlineDialog.visible = true
}

async function saveInlineAdapter() {
  if (!inlineDialog.name || !inlineDialog.paramsModel.impl) {
    ElMessage.warning('请填写适配器名称并选择实现类')
    return
  }
  const payload = {
    id: 'ADP-' + Date.now().toString().slice(-6),
    name: inlineDialog.name,
    type: inlineDialog.type,
    impl: inlineDialog.paramsModel.impl,
    enabled: true,
    version: '1.0',
    params: JSON.stringify(inlineDialog.paramsModel.params)
  }
  await http.post('/adapters', payload)
  inlineDialog.visible = false
  form[inlineDialog.targetField] = payload.id
  ElMessage.success('已创建并选用')
  load() // 刷新下拉选项（保留选中值：load 后重新设置）
  // load() 会重拉列表但不会清空 form，选中值仍在
}

// ---------- 新建 / 编辑 ----------
const dialog = reactive({ visible: false, isEdit: false, editingAppId: '' })
const emptyForm = () => ({
  appId: '', name: '', contact: '', authAdapterId: null, callbackAuthAdapterId: null,
  defaultMessageAdapterId: null, baseUrl: '', ipWhitelist: '', ipBlacklist: '',
  qpsLimit: null, dailyQuota: null, desc: ''
})
const form = reactive(emptyForm())

function openCreate() {
  Object.assign(form, emptyForm())
  dialog.isEdit = false
  dialog.visible = true
}
function openEdit(row) {
  Object.assign(form, emptyForm(), {
    appId: row.appId, name: row.name, contact: row.contact,
    authAdapterId: row.authAdapterId, callbackAuthAdapterId: row.callbackAuthAdapterId,
    defaultMessageAdapterId: row.defaultMessageAdapterId, baseUrl: row.baseUrl,
    ipWhitelist: row.ipWhitelist, ipBlacklist: row.ipBlacklist,
    qpsLimit: row.qpsLimit, dailyQuota: row.dailyQuota, desc: row.desc
  })
  dialog.isEdit = true
  dialog.visible = true
}
async function save() {
  if (dialog.isEdit) {
    await http.put(`/apps/${form.appId}`, { ...form })
  } else {
    await http.post('/apps', { ...form })
  }
  ElMessage.success('保存成功')
  dialog.visible = false
  load()
}

// ---------- 生命周期 ----------
async function doAction(row, action) {
  await http.post(`/apps/${row.appId}/${action}`)
  ElMessage.success('操作成功')
  load()
}
async function remove(row) {
  await ElMessageBox.confirm(`删除应用 ${row.name}？其分组与凭证将级联删除`, '确认', { type: 'warning' })
  await http.delete(`/apps/${row.appId}`)
  ElMessage.success('已删除')
  load()
}

// ---------- 详情与凭证 ----------
const detail = reactive({ visible: false, row: {}, credentials: [] })
async function openDetail(row) {
  detail.visible = true
  detail.row = row
  const full = await http.get(`/apps/${row.appId}`)
  detail.row = full
  detail.credentials = full.credentials || []
}
async function activate(cred) {
  await http.post(`/apps/${detail.row.appId}/credentials/${cred.id}/activate`)
  ElMessage.success('已激活，旧凭证并存 24h')
  openDetail(detail.row)
}
async function finishRotation(cred) {
  await http.post(`/apps/${detail.row.appId}/credentials/${cred.id}/finish-rotation`)
  ElMessage.success('轮换完成')
  openDetail(detail.row)
}
async function retire(cred) {
  await ElMessageBox.confirm('吊销后该凭证立即不可用，确认？', '确认', { type: 'warning' })
  const warning = await http.post(`/apps/${detail.row.appId}/credentials/${cred.id}/retire`)
  if (warning) ElMessage.warning(warning)
  else ElMessage.success('已吊销')
  openDetail(detail.row)
}
async function removeCred(cred) {
  await ElMessageBox.confirm('删除该已失效凭证记录？此操作不可恢复', '确认', { type: 'warning' })
  await http.delete(`/apps/${detail.row.appId}/credentials/${cred.id}`)
  ElMessage.success('已删除')
  openDetail(detail.row)
}
// 凭证录入（支持 OUTBOUND / CALLBACK 两类，修复评审中危 #8 写死 OUTBOUND）
const credDialog = reactive({ visible: false, mode: 'update', kind: 'OUTBOUND', credential: '' })

function openCredDialog(mode) {
  credDialog.mode = mode
  credDialog.kind = 'OUTBOUND'
  credDialog.credential = ''
  credDialog.visible = true
}

async function saveCred() {
  if (!credDialog.credential) {
    ElMessage.warning('请输入凭证内容')
    return
  }
  const payload = { kind: credDialog.kind, credential: credDialog.credential }
  if (credDialog.mode === 'update') {
    await http.post(`/apps/${detail.row.appId}/credentials/update`, payload)
    ElMessage.success('已更新，旧凭证并存 24h')
  } else {
    await http.post(`/apps/${detail.row.appId}/credentials/reset`, payload)
    ElMessage.success('已重置（旧凭证已立即失效）')
  }
  credDialog.visible = false
  openDetail(detail.row)
}

// ---------- 展示辅助 ----------
const statusText = (s) => ({ DRAFT: '草稿', ENABLED: '启用', DISABLED: '停用', CANCELLED: '注销' }[s] || s)
const statusType = (s) => ({ DRAFT: 'info', ENABLED: 'success', DISABLED: 'warning', CANCELLED: 'info' }[s] || 'info')
/** 时间格式化：ISO → 年月日时分秒 */
const fmtTime = (t) => (t ? t.replace('T', ' ').slice(0, 19) : '—')
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; margin-bottom: 14px; }
.toolbar-filters { display: flex; gap: 10px; }
.cred-actions { margin-top: 14px; display: flex; gap: 8px; }
h4 { margin: 20px 0 10px; color: #303133; }
.cred-hint { font-size: 12px; color: #909399; margin: -4px 0 10px; }
.adapter-pick { display: flex; width: 100%; }
.adapter-pick .el-select { flex: 1; margin-right: 8px; }
</style>
