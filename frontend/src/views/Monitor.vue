<template>
  <div>
    <!-- 统计卡（M4：overview 真数据） -->
    <el-row :gutter="16">
      <el-col :span="6" v-for="card in cards" :key="card.title">
        <el-card shadow="hover">
          <div class="card-title">{{ card.title }}</div>
          <div class="card-value">{{ card.value }}</div>
          <div class="card-sub" v-if="card.sub">{{ card.sub }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 调用日志（traceId 贯穿；脱敏后落库） -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="panel-head">
          <span>调用日志</span>
          <el-tag size="small">traceId 贯穿</el-tag>
          <div class="filters">
            <el-input v-model="logFilter.traceId" placeholder="traceId 过滤" size="small" clearable
                      style="width: 200px" @keyup.enter="loadLogs(1)" />
            <el-button size="small" @click="loadLogs(1)">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="logs" size="small">
        <el-table-column prop="createdAt" label="时间" width="170" show-overflow-tooltip />
        <el-table-column label="方向" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.direction === 'IN' ? 'info' : 'warning'">{{ row.direction }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="interfaceId" label="接口ID" width="90" />
        <el-table-column prop="appId" label="应用" width="120" show-overflow-tooltip />
        <el-table-column label="状态码" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.statusCode < 400 ? 'success' : row.statusCode < 500 ? 'warning' : 'danger'">
              {{ row.statusCode }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时(ms)" width="90" />
        <el-table-column prop="url" label="URL" show-overflow-tooltip />
        <el-table-column prop="traceId" label="traceId" show-overflow-tooltip />
      </el-table>
      <div class="pager" v-if="logTotal > logPageSize">
        <el-pagination layout="prev, pager, next" :total="logTotal" :page-size="logPageSize"
                       :current-page="logPage" @current-change="p => loadLogs(p)" />
      </div>
    </el-card>

    <!-- UNKNOWN 对账（设计方案 §4.5 失败诊断与对账查询） -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="panel-head">
          <span>对账（UNKNOWN）</span>
          <div class="filters">
            <el-button size="small" @click="loadUnknown(1)">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="unknowns" size="small">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="interfaceId" label="接口ID" width="90" />
        <el-table-column prop="bizId" label="业务键" width="220" show-overflow-tooltip />
        <el-table-column prop="errorCode" label="错误码" width="90" />
        <el-table-column prop="updatedAt" label="更新时间" width="170" />
        <el-table-column prop="traceId" label="traceId" show-overflow-tooltip />
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button size="small" type="success" @click="reconcile(row, 'SUCCESS')">置为已到达</el-button>
            <el-button size="small" type="warning" @click="reconcile(row, 'COMPENSATING')">置为未到达</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 死信（查看与重放） -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="panel-head">
          <span>死信</span>
          <div class="filters">
            <el-button size="small" @click="loadDeadLetters(1)">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="deadLetters" size="small">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="bizType" label="类型" width="100" />
        <el-table-column prop="refId" label="关联记录" width="100" />
        <el-table-column prop="reason" label="死因" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'PENDING' ? 'danger' : 'success'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" type="primary" :disabled="row.status !== 'PENDING'"
                       @click="replayDeadLetter(row)">重放</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 告警（事件 + 规则管理） -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="panel-head">
          <span>告警</span>
          <div class="filters">
            <el-button size="small" @click="loadAlerts(1)">刷新</el-button>
            <el-button size="small" @click="openRuleModal()">＋ 新建规则</el-button>
          </div>
        </div>
      </template>
      <el-table :data="alerts" size="small">
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.level === 'CRITICAL' ? 'danger' : 'warning'">
              {{ row.level === 'CRITICAL' ? '严重' : '警告' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="内容" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="时间" width="170" />
      </el-table>
      <template v-if="rules.length">
        <div class="rules-title">告警规则</div>
        <el-table :data="rules" size="small">
          <el-table-column prop="name" label="规则名" width="160" />
          <el-table-column prop="metric" label="指标" width="160" />
          <el-table-column prop="threshold" label="阈值" width="90" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button size="small" @click="openRuleModal(row)">编辑</el-button>
              <el-button size="small" type="danger" @click="deleteRule(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <!-- 对账操作弹窗 -->
    <el-dialog v-model="reconcileVisible" title="人工对账（UNKNOWN 置位）" width="460px">
      <el-form label-width="90px">
        <el-form-item label="置位目标">
          <el-tag :type="reconcileForm.target === 'SUCCESS' ? 'success' : 'warning'">
            {{ reconcileForm.target === 'SUCCESS' ? '已到达 → SUCCESS' : '未到达 → COMPENSATING（入补偿队列）' }}
          </el-tag>
        </el-form-item>
        <el-form-item label="操作人">
          <el-input v-model="reconcileForm.operator" placeholder="必填（管理面无用户体系，手动留痕）" />
        </el-form-item>
        <el-form-item label="依据说明">
          <el-input v-model="reconcileForm.reason" type="textarea" :rows="2" placeholder="对账依据（可空）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reconcileVisible = false">取消</el-button>
        <el-button type="primary" @click="submitReconcile">确认置位</el-button>
      </template>
    </el-dialog>

    <!-- 告警规则弹窗 -->
    <el-dialog v-model="ruleVisible" :title="ruleForm.id ? '编辑告警规则' : '新建告警规则'" width="480px">
      <el-form label-width="100px">
        <el-form-item label="规则名">
          <el-input v-model="ruleForm.name" placeholder="如：死信堆积告警" />
        </el-form-item>
        <el-form-item label="指标">
          <el-select v-model="ruleForm.metric">
            <el-option label="近5分钟出站成功率（%）" value="success_rate" />
            <el-option label="近5分钟出站P99延迟（ms）" value="p99_latency" />
            <el-option label="死信PENDING堆积（条）" value="dead_letter_backlog" />
            <el-option label="待重试积压（条）" value="retry_backlog" />
          </el-select>
        </el-form-item>
        <el-form-item label="阈值表达式">
          <el-input v-model="ruleForm.threshold" placeholder="形如 > 100 或 < 95" />
        </el-form-item>
        <el-form-item label="通知渠道">
          <el-input v-model="ruleForm.notifyChannel" placeholder="预留（邮件/IM，v1.1 对接）" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="ruleForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/api/http'

// ---------- 统计卡 ----------
const cards = ref([
  { title: '今日调用量', value: '—', sub: '' },
  { title: '今日成功率', value: '—', sub: '' },
  { title: '待补偿 / 待重送', value: '—', sub: '' },
  { title: '死信 / 对账中', value: '—', sub: '' }
])

async function loadOverview() {
  try {
    const d = await http.get('/monitor/overview')
    cards.value[0].value = d.todayCalls
    cards.value[0].sub = `今日成功 ${d.todaySuccess} · 死信 ${d.todayDeadLetter}`
    cards.value[1].value = d.successRate + '%'
    cards.value[1].sub = '今日终态（成功 / 死信）口径'
    cards.value[2].value = `${d.compensating} / ${d.pendingRedelivery}`
    cards.value[3].value = `${d.deadLetterBacklog} / ${d.unknown}`
  } catch { /* 后端未启动时保持占位 */ }
}

// ---------- 调用日志 ----------
const logs = ref([])
const logTotal = ref(0)
const logPage = ref(1)
const logPageSize = 20
const logFilter = ref({ traceId: '' })

async function loadLogs(page = 1) {
  logPage.value = page
  try {
    const d = await http.get('/monitor/call-logs', {
      params: { page, pageSize: logPageSize, traceId: logFilter.value.traceId || undefined }
    })
    logs.value = d.list
    logTotal.value = d.total
  } catch { /* 保持现有数据 */ }
}

// ---------- UNKNOWN 对账 ----------
const unknowns = ref([])

async function loadUnknown(page = 1) {
  try {
    const d = await http.get('/monitor/outbound-requests', {
      params: { status: 'UNKNOWN', page, pageSize: 20 }
    })
    unknowns.value = d.list
  } catch { /* 保持现有数据 */ }
}

const reconcileVisible = ref(false)
const reconcileForm = ref({ id: 0, target: 'SUCCESS', operator: 'admin', reason: '' })

function reconcile(row, target) {
  reconcileForm.value = { id: row.id, target, operator: 'admin', reason: '' }
  reconcileVisible.value = true
}

async function submitReconcile() {
  const f = reconcileForm.value
  await http.post(`/monitor/outbound-requests/${f.id}/reconcile`, {
    target: f.target, operator: f.operator, reason: f.reason || null
  })
  ElMessage.success('对账置位成功（审计已留痕）')
  reconcileVisible.value = false
  loadUnknown()
  loadOverview()
}

// ---------- 死信 ----------
const deadLetters = ref([])

async function loadDeadLetters(page = 1) {
  try {
    const d = await http.get('/monitor/dead-letters', { params: { page, pageSize: 20 } })
    deadLetters.value = d.list
  } catch { /* 保持现有数据 */ }
}

async function replayDeadLetter(row) {
  await ElMessageBox.confirm(
      `重放死信 #${row.id}（${row.bizType === 'OUTBOUND' ? '出站记录置回补偿队列' : '送达记录置回待重送'}，` +
      '由补偿 worker 自然重放）？', '死信重放', { type: 'warning' })
  await http.post(`/monitor/dead-letters/${row.id}/replay`)
  ElMessage.success('已重新入队（worker 将在数秒内重放）')
  loadDeadLetters()
  loadOverview()
}

// ---------- 告警 ----------
const alerts = ref([])
const rules = ref([])

async function loadAlerts(page = 1) {
  try {
    const d = await http.get('/monitor/alerts', { params: { page, pageSize: 20 } })
    alerts.value = d.list
  } catch { /* 保持现有数据 */ }
}

async function loadRules() {
  try {
    rules.value = await http.get('/monitor/alert-rules')
  } catch { /* 保持现有数据 */ }
}

const ruleVisible = ref(false)
const ruleForm = ref({ id: null, name: '', metric: 'dead_letter_backlog', threshold: '> 10', notifyChannel: '', enabled: true })

function openRuleModal(rule) {
  ruleForm.value = rule
      ? { id: rule.id, name: rule.name, metric: rule.metric, threshold: rule.threshold,
          notifyChannel: rule.notifyChannel || '', enabled: rule.enabled }
      : { id: null, name: '', metric: 'dead_letter_backlog', threshold: '> 10', notifyChannel: '', enabled: true }
  ruleVisible.value = true
}

async function saveRule() {
  const f = ruleForm.value
  const body = {
    name: f.name, metric: f.metric, threshold: f.threshold,
    notifyChannel: f.notifyChannel || null, enabled: f.enabled
  }
  if (f.id) {
    await http.put(`/monitor/alert-rules/${f.id}`, body)
  } else {
    await http.post('/monitor/alert-rules', body)
  }
  ElMessage.success('规则已保存（生效延迟 ≤ 60s 缓存刷新）')
  ruleVisible.value = false
  loadRules()
}

async function deleteRule(rule) {
  await ElMessageBox.confirm(`删除告警规则「${rule.name}」？`, '删除', { type: 'warning' })
  await http.delete(`/monitor/alert-rules/${rule.id}`)
  loadRules()
}

// ---------- 装载与轮询 ----------
let timer = null

onMounted(() => {
  loadOverview()
  loadLogs()
  loadUnknown()
  loadDeadLetters()
  loadAlerts()
  loadRules()
  timer = setInterval(() => {
    loadOverview()
    loadUnknown()
  }, 10000) // 统计卡与对账列表 10s 轮询（惰性观察）
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.card-title { color: #909399; font-size: 13px; }
.card-value { font-size: 28px; font-weight: 600; margin-top: 8px; }
.card-sub { color: #c0c4cc; font-size: 12px; margin-top: 4px; }
.panel-head { display: flex; align-items: center; gap: 8px; }
.panel-head .filters { margin-left: auto; display: flex; gap: 8px; }
.pager { margin-top: 8px; display: flex; justify-content: center; }
.rules-title { color: #909399; font-size: 13px; margin: 12px 0 4px; }
</style>
