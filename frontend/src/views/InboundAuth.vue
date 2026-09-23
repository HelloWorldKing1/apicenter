<template>
  <div>
    <!-- 平台设置：平台默认鉴权方式 + 是否强制自报主体（v1.2，落库、页面可改、改后即时生效） -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-head">
          <span>平台设置（改后 <b>立即生效</b>，无需重启）</span>
          <span class="muted" v-if="setting.updatedBy">最后修改：{{ setting.updatedBy }} · {{ setting.updatedAt }}</span>
        </div>
      </template>
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
                title="平台默认鉴权方式只影响「未单独绑定鉴权方式」的出站中转接口；接口级绑定（接口管理 → 入站鉴权方式）优先于此处。" />
      <div class="grid">
        <div class="item">
          <span class="label">平台默认鉴权方式</span>
          <el-select v-model="form.defaultAdapterId" clearable style="width: 100%"
                     placeholder="（未配置 ⇒ 未绑定接口一律拒绝 40108）">
            <el-option v-for="a in clientAuthAdapters" :key="a.id" :label="`${a.name}（${a.impl}）`" :value="a.id" />
          </el-select>
          <div class="hint">可选范围 = 4 个入站验签实现（API Key / HMAC / Bearer / IP 名单）</div>
        </div>
        <div class="item">
          <span class="label">强制自报主体（X-Client-Id）</span>
          <el-switch v-model="form.requireClientId" />
          <div class="hint">
            开启 = 未带 / 未知主体一律拒绝（兼容档，等价 2026-09-23 的行为）；
            <b>关闭 = 开放集</b>：主体仅入审计，不参与放行判定
          </div>
        </div>
        <div class="item">
          <span class="label">影响面</span>
          <div class="hint">{{ impact.hint || '（点击刷新获取）' }}</div>
        </div>
      </div>
      <div class="actions">
        <el-button type="primary" :disabled="readOnly" @click="saveSetting">保存设置</el-button>
        <el-button @click="loadAll">刷新</el-button>
      </div>
    </el-card>

    <!-- 凭证池：三级属主（平台 / 接口 / 调用方档案） -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-head">
          <span>入站鉴权凭证池</span>
          <span class="muted">持有凭证即可调用；一凭证一行 ⇒ 可单独吊销、可按备注归因（共享凭证下唯一不可伪造的抓手）</span>
        </div>
      </template>
      <div class="toolbar">
        <el-select v-model="ownerType" style="width: 170px" @change="onOwnerChange">
          <el-option v-for="t in OWNER_TYPES" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
        <el-input v-if="ownerIdRequired(ownerType)" v-model="ownerId" style="width: 220px"
                  :placeholder="ownerType === 'INTERFACE' ? '接口数字 id（如 12）' : '调用方标识（如 ERP-PROD）'"
                  @keyup.enter="loadPool" />
        <el-button @click="loadPool">查询</el-button>
        <el-button type="primary" :disabled="readOnly" @click="openIssue">＋ 发放新凭证</el-button>
      </div>
      <div class="hint">{{ ownerHint }}</div>

      <el-table :data="pool" v-loading="poolLoading" size="small" border style="margin-top: 10px">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="类型" width="130">
          <template #default="{ row }">{{ kindLabel(row.kind) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="200">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : (row.status === 'ROTATING' ? 'warning' : 'info')">
              {{ credentialStatusLabel(row.status) }}
            </el-tag>
            <span v-if="row.expired" class="muted">（并存窗口已过）</span>
          </template>
        </el-table-column>
        <el-table-column label="指纹" width="90">
          <template #default="{ row }"><span class="mono">…{{ row.fingerprint }}</span></template>
        </el-table-column>
        <el-table-column prop="label" label="备注（发给谁 / 何时）" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.label">{{ row.label }}</span>
            <span v-else class="muted">未填 —— 强烈建议补上，事故时靠它叫得上人</span>
          </template>
        </el-table-column>
        <el-table-column label="生效 / 失效" width="180">
          <template #default="{ row }">
            <span class="muted">{{ (row.activatedAt || '').replace('T', ' ') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'ROTATING'" link type="primary" :disabled="readOnly"
                       @click="activate(row)">激活</el-button>
            <el-button v-if="row.status === 'ROTATING'" link type="success" :disabled="readOnly"
                       @click="finishRotation(row)">完成轮换</el-button>
            <el-button v-if="row.status !== 'RETIRED'" link type="danger" :disabled="readOnly"
                       @click="retire(row)">吊销</el-button>
            <el-button link type="primary" :disabled="readOnly" @click="editLabel(row)">改备注</el-button>
            <el-button v-if="row.status === 'RETIRED'" link type="danger" :disabled="readOnly"
                       @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 发放：平台生成随机值（明文仅回显一次） -->
    <el-dialog v-model="issue.visible" title="发放新凭证" width="520px">
      <el-form label-width="110px">
        <el-form-item label="属主">
          <span>{{ ownerTypeLabel(ownerType) }}<span v-if="ownerId"> · {{ ownerId }}</span></span>
        </el-form-item>
        <el-form-item label="凭证类型">
          <el-select v-model="issue.kind" style="width: 100%">
            <el-option label="API Key（密钥头比对）" value="API_KEY" />
            <el-option label="HMAC 签名密钥（时间戳 + 签名）" value="HMAC_SECRET" />
            <el-option label="Bearer Token" value="BEARER_TOKEN" />
            <el-option label="Basic" value="BASIC" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="issue.label" maxlength="64" show-word-limit
                    placeholder="发给谁 / 何时，如「某公司 2026-09-24」" />
        </el-form-item>
      </el-form>
      <el-alert v-if="issue.plaintext" type="warning" :closable="false" show-icon
                title="明文仅显示这一次，请立即交付给调用方并妥善保存">
        <div class="mono" style="word-break: break-all; margin-top: 6px">{{ issue.plaintext }}</div>
      </el-alert>
      <template #footer>
        <el-button @click="issue.visible = false">关闭</el-button>
        <el-button v-if="!issue.plaintext" type="primary" :disabled="readOnly" @click="issueOne">生成</el-button>
        <el-button v-else type="primary" @click="copyPlaintext">复制明文</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/api/http'
import { isReadOnly } from '@/utils/roles.mjs'
import { authStore } from '@/utils/auth.mjs'
import { adapterMatchesRole } from '@/utils/adapterUsage.mjs'
import {
  EMPTY_SETTING, OWNER_TYPES, credentialStatusLabel, diffSetting, kindLabel,
  ownerIdRequired, ownerTypeLabel, relaxationConfirmText
} from '@/utils/inboundAuth.mjs'

const meRole = computed(() => authStore.getUser()?.role)
const readOnly = computed(() => isReadOnly(meRole.value))

// ---------- 平台设置 ----------
const setting = reactive({ ...EMPTY_SETTING })
const form = reactive({ defaultAdapterId: null, requireClientId: true })
const impact = reactive({ affectedInterfaces: 0, hint: '' })
const adapters = ref([])
const clientAuthAdapters = computed(() =>
  adapters.value.filter((a) => a.type === 'auth' && a.enabled && adapterMatchesRole(a.impl, 'CLIENT_AUTH')))

async function loadSetting() {
  const s = await http.get('/inbound-auth/settings')
  Object.assign(setting, s || EMPTY_SETTING)
  form.defaultAdapterId = setting.defaultAdapterId
  form.requireClientId = setting.requireClientId
  const i = await http.get('/inbound-auth/settings/impact')
  Object.assign(impact, i || { affectedInterfaces: 0, hint: '' })
}

async function saveSetting() {
  const d = diffSetting(setting, form)
  if (d.relaxation) {
    try {
      await ElMessageBox.confirm(relaxationConfirmText(d.reasons, impact.affectedInterfaces), '这是一次「放松」变更',
        { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' })
    } catch {
      return
    }
  }
  await http.put('/inbound-auth/settings', {
    defaultAdapterId: form.defaultAdapterId || null,
    requireClientId: form.requireClientId
  })
  ElMessage.success('已保存并立即生效（无需重启）')
  await loadSetting()
}

// ---------- 凭证池 ----------
const ownerType = ref('PLATFORM')
const ownerId = ref('')
const pool = ref([])
const poolLoading = ref(false)
const ownerHint = computed(() => OWNER_TYPES.find((t) => t.value === ownerType.value)?.hint || '')

async function loadPool() {
  if (ownerIdRequired(ownerType.value) && !ownerId.value) {
    ElMessage.warning(ownerType.value === 'INTERFACE' ? '请填接口数字 id' : '请填调用方标识')
    return
  }
  poolLoading.value = true
  try {
    pool.value = await http.get('/inbound-credentials', {
      params: { ownerType: ownerType.value, ownerId: ownerId.value || undefined }
    })
  } finally {
    poolLoading.value = false
  }
}

function onOwnerChange() {
  ownerId.value = ''
  pool.value = []
  loadPool()
}

/** 所有行级操作的公共查询参数（属主是端点的一部分，防跨属主误操作） */
function ownerQuery() {
  return { ownerType: ownerType.value, ownerId: ownerId.value || undefined }
}

const issue = reactive({ visible: false, kind: 'API_KEY', label: '', plaintext: '' })

function openIssue() {
  if (ownerIdRequired(ownerType.value) && !ownerId.value) {
    ElMessage.warning(ownerType.value === 'INTERFACE' ? '请先填接口数字 id' : '请先填调用方标识')
    return
  }
  issue.kind = 'API_KEY'
  issue.label = ''
  issue.plaintext = ''
  issue.visible = true
}

async function issueOne() {
  const res = await http.post('/inbound-credentials', {
    ownerType: ownerType.value, ownerId: ownerId.value || null, kind: issue.kind, label: issue.label || null
  })
  issue.plaintext = res.plaintext
  await loadPool()
}

async function copyPlaintext() {
  try {
    await navigator.clipboard.writeText(issue.plaintext)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

async function activate(row) {
  await http.post(`/inbound-credentials/${row.id}/activate`, null, { params: ownerQuery() })
  ElMessage.success('已激活')
  await loadPool()
}

async function finishRotation(row) {
  await http.post(`/inbound-credentials/${row.id}/finish-rotation`, null, { params: ownerQuery() })
  await loadPool()
}

async function retire(row) {
  const res = await http.post(`/inbound-credentials/${row.id}/retire`, null, { params: ownerQuery() })
  // 后端在该类型已无 ACTIVE 凭证时用 msg 返回告警文案（引导补发）
  if (res && typeof res === 'string') {
    ElMessage.warning(res)
  }
  await loadPool()
}

async function editLabel(row) {
  try {
    const { value } = await ElMessageBox.prompt('备注（发给谁 / 何时）', '改备注', {
      inputValue: row.label || '', inputValidator: (v) => !v || v.length <= 64 || '最长 64 字'
    })
    await http.put(`/inbound-credentials/${row.id}/label`, { label: value || null }, { params: ownerQuery() })
    await loadPool()
  } catch {
    /* 取消 */
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm('删除后不可恢复（仅已失效凭证可删）。确认删除？', '删除凭证', { type: 'warning' })
  } catch {
    return
  }
  await http.delete(`/inbound-credentials/${row.id}`, { params: ownerQuery() })
  await loadPool()
}

// ---------- 初始化 ----------
async function loadAll() {
  adapters.value = await http.get('/adapters')
  await loadSetting()
  await loadPool()
}

onMounted(loadAll)
</script>

<style scoped>
.card { margin-bottom: 16px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 14px; }
.item { display: flex; flex-direction: column; gap: 6px; }
.label { font-size: 13px; color: #374151; font-weight: 500; }
.hint { font-size: 12px; color: #6b7280; line-height: 1.6; }
.actions { margin-top: 14px; display: flex; gap: 8px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.muted { color: #9ca3af; font-size: 12px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
