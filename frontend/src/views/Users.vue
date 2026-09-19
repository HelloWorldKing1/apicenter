<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <div class="toolbar-filters">
          <el-input v-model="keyword" placeholder="按用户名 / 显示名搜索" clearable style="width: 220px"
                    @input="onKeywordInput" />
          <el-button @click="load">刷新</el-button>
        </div>
        <el-button type="primary" @click="openCreate">＋ 新建账号</el-button>
      </div>

      <el-alert type="warning" :closable="false" show-icon class="rbac-alert">
        <div class="rbac-text">{{ NO_RBAC_NOTICE }}</div>
      </el-alert>

      <el-table :data="rows" v-loading="loading" size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="用户名" width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.username }}</span>
            <el-tag v-if="row.id === meId" size="small" type="primary" effect="plain" class="self-tag">当前账号</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="显示名" width="140">
          <template #default="{ row }">{{ row.displayName || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ USER_STATUS_LABEL[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="有效会话" width="90">
          <template #default="{ row }">{{ sessionText(row.sessionCount) }}</template>
        </el-table-column>
        <el-table-column label="最近登录" width="150">
          <template #default="{ row }">{{ fmtTime(row.lastLoginAt) }}</template>
        </el-table-column>
        <el-table-column label="锁定" width="150">
          <template #default="{ row }">
            <span :class="{ locked: accountGuard(row, meId).canUnlock }">{{ lockRemainText(row.lockedUntil) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="260">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="warning" :disabled="!accountGuard(row, meId).canToggleStatus"
                       @click="toggleStatus(row)">
              {{ row.status === 'ENABLED' ? '停用' : '启用' }}
            </el-button>
            <el-button link type="primary" :disabled="!accountGuard(row, meId).canResetPassword"
                       @click="openReset(row)">重置密码</el-button>
            <el-button link type="success" v-if="accountGuard(row, meId).canUnlock"
                       @click="unlock(row)">解锁</el-button>
            <el-button link type="danger" :disabled="!accountGuard(row, meId).canDelete"
                       @click="askRemove(row)">删除</el-button>
            <div v-if="row.id === meId" class="self-hint">当前账号：改口令请用右上角「账号名 → 修改密码」</div>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!loading && rows.length === 0" class="empty-hint">没有匹配的账号</div>
    </el-card>

    <!-- 新建账号（管理员代建；新账号自行登录，不自动建会话） -->
    <el-dialog v-model="create.visible" title="新建账号" width="480px">
      <el-form label-width="100px">
        <el-form-item label="用户名" required>
          <el-input v-model="create.username" placeholder="3-32 位小写字母/数字/_.-" />
        </el-form-item>
        <el-form-item label="初始密码" required>
          <el-input v-model="create.password" type="password" show-password placeholder="8-64 位，含字母与数字" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="create.displayName" placeholder="可选，如 张三" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="create.visible = false">取消</el-button>
        <el-button type="primary" :loading="create.loading" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 编辑（显示名 + 启停用） -->
    <el-dialog v-model="edit.visible" :title="`编辑账号 · ${edit.username}`" width="480px">
      <el-form label-width="100px">
        <el-form-item label="显示名">
          <el-input v-model="edit.displayName" placeholder="可选" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="edit.status" style="width: 100%">
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用（立即吊销其全部会话）" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="dialog-tip">停用后该账号无法登录，且已登录的会话立即失效。</div>
      <template #footer>
        <el-button @click="edit.visible = false">取消</el-button>
        <el-button type="primary" :loading="edit.loading" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="reset.visible" :title="`重置密码 · ${reset.username}`" width="480px">
      <el-form label-width="100px">
        <el-form-item label="新密码" required>
          <el-input v-model="reset.password" type="password" show-password placeholder="8-64 位，含字母与数字" />
        </el-form-item>
      </el-form>
      <div class="dialog-tip">重置后该账号的**所有登录会话立即失效**，需用新密码重新登录。</div>
      <template #footer>
        <el-button @click="reset.visible = false">取消</el-button>
        <el-button type="primary" :loading="reset.loading" @click="submitReset">重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/api/http'
import { authStore, passwordIssue, usernameIssue } from '@/utils/auth.mjs'
import {
  NO_RBAC_NOTICE, USER_STATUS_LABEL, accountGuard, fmtTime, lockRemainText, sessionText, statusTagType
} from '@/utils/users.mjs'

// 账号管理（v1，2026-09-18）：列表 / 新建 / 编辑（显示名·启停用）/ 重置口令 / 解锁 / 删除。
// 服务端守卫：不能停用/删除最后一个可用账号、不能动自己（前端只做按钮禁用与提示）。
const rows = ref([])
const loading = ref(false)
const keyword = ref('')
const meId = computed(() => authStore.getUser()?.id)

const create = reactive({ visible: false, username: '', password: '', displayName: '', loading: false })
const edit = reactive({ visible: false, id: null, username: '', displayName: '', status: 'ENABLED', loading: false })
const reset = reactive({ visible: false, id: null, username: '', password: '', loading: false })

let keywordTimer = null

async function load() {
  loading.value = true
  try {
    rows.value = await http.get('/users', { params: { keyword: keyword.value || undefined } })
  } catch (e) {
    // 提示由 http 拦截器统一处理
  } finally {
    loading.value = false
  }
}

function onKeywordInput() {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(load, 300)
}

function openCreate() {
  create.username = ''
  create.password = ''
  create.displayName = ''
  create.visible = true
}

async function submitCreate() {
  const username = (create.username || '').trim().toLowerCase()
  const nameIssue = usernameIssue(username)
  if (nameIssue) {
    ElMessage.warning(nameIssue)
    return
  }
  const pwdIssue = passwordIssue(create.password)
  if (pwdIssue) {
    ElMessage.warning(pwdIssue)
    return
  }
  create.loading = true
  try {
    await http.post('/users', {
      username,
      password: create.password,
      displayName: (create.displayName || '').trim() || null
    })
    ElMessage.success(`账号 ${username} 已创建（请把初始密码告知本人）`)
    create.visible = false
    await load()
  } catch (e) {
    // 40901 / 40001 由拦截器提示
  } finally {
    create.loading = false
  }
}

function openEdit(row) {
  edit.id = row.id
  edit.username = row.username
  edit.displayName = row.displayName || ''
  edit.status = row.status
  edit.visible = true
}

async function submitEdit() {
  edit.loading = true
  try {
    await http.put(`/users/${edit.id}`, {
      displayName: (edit.displayName || '').trim() || null,
      status: edit.status
    })
    ElMessage.success('已保存')
    edit.visible = false
    await load()
  } catch (e) {
    // 守卫拒绝（40001）由拦截器提示
  } finally {
    edit.loading = false
  }
}

async function toggleStatus(row) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  const label = next === 'DISABLED' ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      next === 'DISABLED'
        ? `停用后 ${row.username} 无法登录，已登录会话立即失效。确定停用？`
        : `确定启用 ${row.username}？`,
      `${label}账号`, { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await http.put(`/users/${row.id}`, { displayName: row.displayName || null, status: next })
    ElMessage.success(`已${label}`)
    await load()
  } catch (e) {
    // 守卫拒绝由拦截器提示
  }
}

function openReset(row) {
  reset.id = row.id
  reset.username = row.username
  reset.password = ''
  reset.visible = true
}

async function submitReset() {
  const pwdIssue = passwordIssue(reset.password)
  if (pwdIssue) {
    ElMessage.warning(pwdIssue)
    return
  }
  reset.loading = true
  try {
    await http.post(`/users/${reset.id}/password`, { newPassword: reset.password })
    ElMessage.success('已重置（该账号所有会话已失效）')
    reset.visible = false
    await load()
  } catch (e) {
    // 拦截器提示
  } finally {
    reset.loading = false
  }
}

async function unlock(row) {
  try {
    await http.post(`/users/${row.id}/unlock`)
    ElMessage.success('已解除锁定')
    await load()
  } catch (e) {
    // 拦截器提示
  }
}

async function askRemove(row) {
  try {
    await ElMessageBox.prompt(
      `删除后该账号与其全部会话一并移除，且不可恢复。请输入用户名「${row.username}」确认：`,
      '删除账号',
      {
        type: 'warning',
        inputPlaceholder: row.username,
        inputValidator: (v) => (v === row.username ? true : '用户名不匹配')
      })
  } catch (e) {
    return
  }
  try {
    await http.delete(`/users/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    // 拦截器提示
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; margin-bottom: 14px; }
.toolbar-filters { display: flex; gap: 10px; }
.rbac-alert { margin-bottom: 12px; }
.rbac-text { font-size: 12px; line-height: 1.7; }
.self-tag { margin-left: 6px; }
.self-hint { font-size: 12px; color: #909399; margin-top: 4px; }
.locked { color: #e6a23c; }
.dialog-tip { font-size: 12px; color: #909399; line-height: 1.7; }
.empty-hint { text-align: center; color: #909399; font-size: 12px; padding: 12px 0; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
