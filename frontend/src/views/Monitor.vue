<template>
  <div>
    <!-- ===== 健康条（overview，10s 轮询） ===== -->
    <el-row :gutter="16">
      <el-col :span="6" v-for="card in cards" :key="card.title">
        <el-card shadow="hover">
          <div class="card-title">{{ card.title }}</div>
          <div class="card-value">{{ card.value }}</div>
          <div class="card-sub" v-if="card.sub">{{ card.sub }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 16px">
      <el-tabs v-model="tab">
        <!-- ============ Tab 1 总览 ============ -->
        <el-tab-pane label="总览" name="overview">
          <div class="panel-head">
            <span>IN 调用 / 出站终态失败 · 近24小时（完整趋势与延迟见「概览」）</span>
            <el-button size="small" @click="goDashboard">去概览仪表盘</el-button>
          </div>
          <div ref="overviewChartEl" class="chart-sm"></div>
          <el-descriptions :column="4" size="small" border style="margin-top: 10px" v-if="overview">
            <el-descriptions-item label="待补偿">{{ overview.compensating }}</el-descriptions-item>
            <el-descriptions-item label="待重送">{{ overview.pendingRedelivery }}</el-descriptions-item>
            <el-descriptions-item label="死信PENDING">{{ overview.deadLetterBacklog }}</el-descriptions-item>
            <el-descriptions-item label="对账UNKNOWN">{{ overview.unknown }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>

        <!-- ============ Tab 2 调用日志 ============ -->
        <el-tab-pane label="调用日志" name="logs">
          <div class="filters">
            <el-date-picker v-model="logFilter.range" type="datetimerange" size="small"
                            range-separator="~" start-placeholder="开始" end-placeholder="结束"
                            value-format="YYYY-MM-DDTHH:mm:ss" style="width: 340px" />
            <el-select v-model="logFilter.direction" size="small" clearable placeholder="方向" style="width: 90px">
              <el-option label="IN" value="IN" /><el-option label="OUT" value="OUT" />
            </el-select>
            <el-select v-model="logFilter.appId" size="small" clearable placeholder="应用" style="width: 140px"
                       @change="onAppChange">
              <el-option v-for="a in appOptions" :key="a.appId" :label="a.name" :value="a.appId" />
            </el-select>
            <el-select v-model="logFilter.interfaceId" size="small" clearable filterable placeholder="接口"
                       style="width: 190px">
              <el-option v-for="i in logIfaceOptions" :key="i.id" :label="`${i.code} (${i.path})`" :value="i.id" />
            </el-select>
            <el-select v-model="logFilter.statusGroup" size="small" clearable placeholder="HTTP 结果" style="width: 120px">
              <el-option label="2xx 成功" value="2xx" /><el-option label="4xx" value="4xx" />
              <el-option label="5xx" value="5xx" />
            </el-select>
            <el-input v-model="logFilter.traceId" size="small" clearable placeholder="traceId" style="width: 210px" />
            <el-input v-model="logFilter.keyword" size="small" clearable placeholder="URL 子串" style="width: 160px" />
            <el-button size="small" type="primary" @click="loadLogs(1)">查询</el-button>
          </div>
          <el-table :data="logs" size="small" @row-click="openLogDetail">
            <el-table-column prop="createdAt" label="时间" width="170" show-overflow-tooltip />
            <el-table-column label="方向" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="row.direction === 'IN' ? 'info' : 'warning'">{{ row.direction }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="interfaceId" label="接口ID" width="90" />
            <el-table-column prop="appId" label="应用" width="120" show-overflow-tooltip />
            <el-table-column prop="method" label="M" width="56" />
            <el-table-column prop="url" label="URL" show-overflow-tooltip />
            <el-table-column label="状态码" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.statusCode >= 200 && row.statusCode < 300 ? 'success' : 'danger'">
                  {{ row.statusCode }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="latencyMs" label="耗时(ms)" width="90" />
            <el-table-column prop="traceId" label="traceId" show-overflow-tooltip />
            <el-table-column label="操作" width="70" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click.stop="openLogDetail(row)">明细</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pager">
            <el-pagination background layout="total, prev, pager, next" :total="logTotal"
                           :page-size="20" :current-page="logPage" @current-change="loadLogs" />
          </div>
          <div class="tip">行点击/明细 → 查看脱敏请求/响应原文；信封业务码（如 42901）检索需先用 traceId 定位（v1.1 才落 error_code 列）。</div>
        </el-tab-pane>

        <!-- ============ Tab 3 状态机与对账 ============ -->
        <el-tab-pane label="状态机与对账" name="queue">
          <div class="filters">
            <el-select v-model="queueFilter.status" size="small" clearable placeholder="全部状态" style="width: 140px">
              <el-option v-for="(label, key) in STATUS_LABEL" :key="key" :label="label" :value="key" />
            </el-select>
            <el-input v-model="queueFilter.bizId" size="small" clearable placeholder="业务键 bizId" style="width: 200px" />
            <el-input v-model="queueFilter.traceId" size="small" clearable placeholder="traceId" style="width: 200px" />
            <el-button size="small" type="primary" @click="loadQueue(1)">查询</el-button>
          </div>
          <el-table :data="queueRows" size="small">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="interfaceId" label="接口ID" width="90" />
            <el-table-column prop="appId" label="应用" width="120" show-overflow-tooltip />
            <el-table-column prop="bizId" label="业务键" width="220" show-overflow-tooltip />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTag(row.status)">{{ STATUS_LABEL[row.status] || row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="attemptCount" label="尝试" width="60" align="center">
              <template #default="{ row }">{{ row.attemptCount }}/{{ row.maxAttempts }}</template>
            </el-table-column>
            <el-table-column prop="errorCode" label="错误码" width="80" />
            <el-table-column prop="nextRetryAt" label="下次重试" width="165">
              <template #default="{ row }">{{ (row.nextRetryAt || '').replace('T', ' ').slice(0, 19) || '—' }}</template>
            </el-table-column>
            <el-table-column prop="traceId" label="traceId" show-overflow-tooltip />
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'UNKNOWN'">
                  <el-button size="small" type="success" @click="openReconcile(row, 'SUCCESS')">置为已到达</el-button>
                  <el-button size="small" type="warning" @click="openReconcile(row, 'COMPENSATING')">置为未到达</el-button>
                </template>
                <el-button size="small" @click="openQueueDetail(row)">详情/审计</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pager">
            <el-pagination background layout="total, prev, pager, next" :total="queueTotal"
                           :page-size="20" :current-page="queuePage" @current-change="loadQueue" />
          </div>
          <div class="tip">补偿/重送中的记录由 worker 每 3s 推进；入站待重送（PENDING）请在死信 Tab 观察其耗尽后的死信。对账置位与 TTL 自动降级都会留审计。</div>
        </el-tab-pane>

        <!-- ============ Tab 4 死信 ============ -->
        <el-tab-pane label="死信" name="dead">
          <div class="filters">
            <el-select v-model="deadFilter.bizType" size="small" clearable placeholder="全部类型" style="width: 120px">
              <el-option label="出站" value="OUTBOUND" /><el-option label="入站送达" value="INBOUND" />
            </el-select>
            <el-select v-model="deadFilter.status" size="small" clearable placeholder="全部状态" style="width: 120px">
              <el-option label="待处理" value="PENDING" /><el-option label="已处理" value="HANDLED" />
            </el-select>
            <el-button size="small" type="primary" @click="loadDeadLetters(1)">查询</el-button>
          </div>
          <el-table :data="deadLetters" size="small">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column label="类型" width="100">
              <template #default="{ row }">{{ row.bizType === 'OUTBOUND' ? '出站' : '入站送达' }}</template>
            </el-table-column>
            <el-table-column prop="refId" label="关联记录" width="100" />
            <el-table-column prop="reason" label="死因" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'PENDING' ? 'warning' : 'info'">
                  {{ row.status === 'PENDING' ? '待处理' : '已处理' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="时间" width="170">
              <template #default="{ row }">{{ (row.createdAt || '').replace('T', ' ').slice(0, 19) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button size="small" @click="openDeadDetail(row)">报文</el-button>
                <el-button size="small" type="primary" :disabled="row.status !== 'PENDING'"
                           @click="replayDeadLetter(row)">重放</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pager">
            <el-pagination background layout="total, prev, pager, next" :total="deadTotal"
                           :page-size="20" :current-page="deadPage" @current-change="loadDeadLetters" />
          </div>
        </el-tab-pane>

        <!-- ============ Tab 5 告警 ============ -->
        <el-tab-pane label="告警" name="alerts">
          <div class="panel-head" style="margin-bottom: 8px">
            <span>规则</span>
            <el-button size="small" @click="openRuleModal()">＋ 新建规则</el-button>
          </div>
          <el-table :data="rules" size="small" :key="'rules'">
            <el-table-column prop="name" label="规则名" min-width="140" />
            <el-table-column label="指标" width="190">
              <template #default="{ row }">{{ METRIC_LABEL[row.metric] || row.metric }}</template>
            </el-table-column>
            <el-table-column prop="threshold" label="阈值" width="90" />
            <el-table-column label="启用" width="80">
              <template #default="{ row }">
                <el-switch :model-value="row.enabled" @change="(v) => toggleRule(row, v)" />
              </template>
            </el-table-column>
            <el-table-column prop="notifyChannel" label="通知渠道" width="120">
              <template #default="{ row }">{{ row.notifyChannel || '预留' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openRuleModal(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="deleteRule(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="rules-title">事件（同一规则冷却 5 分钟去重；rule_id 为空 = 内置告警，如验签连续失败）</div>
          <el-table :data="alerts" size="small">
            <el-table-column prop="createdAt" label="时间" width="170">
              <template #default="{ row }">{{ row.createdAt.replace('T', ' ').slice(0, 19) }}</template>
            </el-table-column>
            <el-table-column label="级别" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.level === 'ERROR' ? 'danger' : 'warning'">{{ row.level }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="指标" width="160">
              <template #default="{ row }">{{ METRIC_LABEL[row.metric] || row.metric }}</template>
            </el-table-column>
            <el-table-column label="来源" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.ruleId ? 'info' : 'danger'" effect="plain">
                  {{ row.ruleId ? '规则' : '内置' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="message" label="消息" show-overflow-tooltip />
          </el-table>
          <div class="pager">
            <el-pagination background layout="total, prev, pager, next" :total="alertTotal"
                           :page-size="20" :current-page="alertPage" @current-change="loadAlerts" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- ===== 日志/死信详情抽屉 ===== -->
    <el-drawer v-model="detail.visible" :size="detailSize + 'px'" resizable
               @resize-end="onDetailResizeEnd" :title="detail.title">
      <template v-if="detail.kind === 'log' && detail.row">
        <el-descriptions :column="1" size="small" border style="margin-bottom: 8px">
          <el-descriptions-item label="方向/方法">{{ detail.row.direction }} · {{ detail.row.method }}</el-descriptions-item>
          <el-descriptions-item label="URL">{{ detail.row.url }}</el-descriptions-item>
          <el-descriptions-item label="接口/应用">{{ detail.row.interfaceId }} / {{ detail.row.appId }}</el-descriptions-item>
          <el-descriptions-item label="HTTP/耗时">{{ detail.row.statusCode }} / {{ detail.row.latencyMs }}ms</el-descriptions-item>
          <el-descriptions-item label="traceId">{{ detail.row.traceId }}</el-descriptions-item>
        </el-descriptions>
        <h4 class="side-title">请求头（已脱敏）</h4>
        <PayloadViewer :text="detail.row.reqHeaders" headers />
        <h4 class="side-title">请求体</h4>
        <PayloadViewer :text="detail.row.reqBody" :content-type="contentTypeOf(detail.row.reqHeaders)"
                       :context="callLogContext(detail.row)" />
        <h4 class="side-title">响应体</h4>
        <PayloadViewer :text="detail.row.respBody" :context="callLogContext(detail.row)" />
      </template>

      <template v-else-if="detail.kind === 'dead' && detail.row">
        <el-descriptions :column="1" size="small" border style="margin-bottom: 8px">
          <el-descriptions-item label="死信 #{{ detail.row.id }}">
            {{ detail.row.bizType === 'OUTBOUND' ? '出站' : '入站送达' }} · 关联记录 {{ detail.row.refId }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ detail.row.status === 'PENDING' ? '待处理' : '已处理' }} · {{ detail.row.createdAt }}
          </el-descriptions-item>
        </el-descriptions>
        <h4 class="side-title">死因 reason</h4>
        <CodeBlock :text="detail.row.reason" />
        <h4 class="side-title">报文快照 payload（重放依据）</h4>
        <PayloadViewer :text="detail.row.payload" :context="deadLetterContext(detail.row)" />
      </template>

      <template v-else-if="detail.kind === 'queue' && detail.row">
        <el-descriptions :column="2" size="small" border style="margin-bottom: 8px">
          <el-descriptions-item label="状态">{{ STATUS_LABEL[detail.row.status] || detail.row.status }}</el-descriptions-item>
          <el-descriptions-item label="尝试">{{ detail.row.attemptCount }}/{{ detail.row.maxAttempts }}</el-descriptions-item>
          <el-descriptions-item label="错误码">{{ detail.row.errorCode || '—' }}</el-descriptions-item>
          <el-descriptions-item label="bizId">{{ detail.row.bizId }}</el-descriptions-item>
          <el-descriptions-item label="traceId" :span="2">{{ detail.row.traceId }}</el-descriptions-item>
        </el-descriptions>
        <!-- 状态链（设计 §4.6）：完整状态流转时间线；空态 = 创建于状态链上线前的历史记录 -->
        <div style="display:flex;align-items:center;gap:8px;margin-top:6px">
          <span class="side-title" style="margin:0">状态链</span>
          <span class="tip" style="margin:0" v-if="!detail.row.stateChain || !detail.row.stateChain.length">
            暂无历史（记录创建于状态链上线前，仅当前状态可查）
          </span>
        </div>
        <el-timeline v-if="detail.row.stateChain && detail.row.stateChain.length" style="padding-left:2px">
          <el-timeline-item v-for="(s, i) in detail.row.stateChain" :key="s.seq"
                            :type="chainNodeType(s.toStatus)" size="large"
                            :timestamp="(s.createdAt || '').replace('T', ' ').slice(0, 19)" placement="top">
            <div class="chain-main">
              <b>{{ STATUS_LABEL[s.toStatus] || s.toStatus }}</b>
              <el-tag v-if="i === detail.row.stateChain.length - 1" size="small" type="danger" effect="plain" style="margin-left:6px">当前</el-tag>
              <span class="chain-meta">尝试 {{ s.attempt }}</span>
            </div>
            <div class="chain-sub">
              {{ TRIGGER_LABEL[s.trigger] || s.trigger }}<template v-if="s.errorCode"> · {{ s.errorCode }}</template>
              <template v-if="s.detail"> — {{ s.detail }}</template>
            </div>
          </el-timeline-item>
        </el-timeline>
        <h4 class="side-title">入站报文 in_payload（预览，最长 4000 字）</h4>
        <PayloadViewer :text="detail.row.inPayloadPreview" :context="outboundContext(detail.row)" />
        <h4 class="side-title">出站报文 out_payload（预览）</h4>
        <PayloadViewer :text="detail.row.outPayloadPreview" :context="outboundContext(detail.row)" />
        <h4 class="side-title">响应 resp_payload（预览）</h4>
        <PayloadViewer :text="detail.row.respPayloadPreview" :context="outboundContext(detail.row)" />
        <h4 class="side-title">对账审计时间线（MANUAL / TTL）</h4>
        <el-table v-if="detail.row.audits && detail.row.audits.length" :data="detail.row.audits" size="small" max-height="220">
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ row.createdAt.replace('T', ' ').slice(0, 19) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.source === 'MANUAL' ? 'success' : 'info'" effect="plain">
                {{ row.source === 'MANUAL' ? '人工' : 'TTL' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="转换" width="140">
            <template #default="{ row }">{{ row.fromStatus }} → {{ row.toStatus }}</template>
          </el-table-column>
          <el-table-column label="操作人/说明" show-overflow-tooltip>
            <template #default="{ row }">{{ row.operator || 'TTL-WORKER' }} {{ row.reason || '' }}</template>
          </el-table-column>
        </el-table>
        <div v-else class="tip">暂无审计（非 UNKNOWN 或尚未对账）</div>
      </template>
    </el-drawer>

    <!-- 对账弹窗 -->
    <el-dialog v-model="reconcile.visible" title="UNKNOWN 对账置位" width="480px">
      <el-form label-width="84px">
        <el-form-item label="置位目标">
          <el-radio-group v-model="reconcile.target">
            <el-radio value="SUCCESS">置为已到达（收敛成功）</el-radio>
            <el-radio value="COMPENSATING">置为未到达（立即补偿）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="操作人" required>
          <el-input v-model="reconcile.operator" placeholder="如 admin" />
        </el-form-item>
        <el-form-item label="依据说明">
          <el-input v-model="reconcile.reason" placeholder="对账依据（可选，审计用）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reconcile.visible = false">取消</el-button>
        <el-button type="primary" @click="submitReconcile">保存（写审计）</el-button>
      </template>
    </el-dialog>

    <!-- 告警规则弹窗 -->
    <el-dialog v-model="ruleVisible" :title="ruleForm.id ? '编辑规则' : '新建规则'" width="480px">
      <el-form label-width="84px">
        <el-form-item label="规则名">
          <el-input v-model="ruleForm.name" placeholder="如：死信堆积告警" />
        </el-form-item>
        <el-form-item label="指标">
          <el-select v-model="ruleForm.metric" style="width: 100%">
            <el-option v-for="(label, key) in METRIC_LABEL" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="阈值表达式">
          <el-input v-model="ruleForm.threshold" placeholder="形如 > 100 或 < 95" />
        </el-form-item>
        <el-form-item label="通知渠道">
          <el-input v-model="ruleForm.notifyChannel" placeholder="预留（邮件/IM，v1.1 对接）" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="ruleForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, nextTick, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import http from '@/api/http'
import PayloadViewer from '@/components/PayloadViewer.vue'
import CodeBlock from '@/components/CodeBlock.vue'
import { contentTypeOf } from '@/utils/payload.mjs'
import { readDrawerWidth, saveDrawerWidth } from '@/utils/prefs.mjs'
import { callLogContext, deadLetterContext, outboundContext } from '@/utils/logContext.mjs'

echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const route = useRoute()
const router = useRouter()

const STATUS_LABEL = {
  INIT: '初始', MAPPING: '映射中', UNKNOWN: '对账中', COMPENSATING: '待补偿',
  SUCCESS: '成功', DEAD_LETTER: '死信'
}
const METRIC_LABEL = {
  success_rate: '近5分钟出站成功率（%）', p99_latency: '近5分钟出站P99（ms）',
  dead_letter_backlog: '死信PENDING堆积（条）', retry_backlog: '待重试积压（条）'
}
// 状态链 trigger 中文（设计 §4.6：FIRST_SEND/COMPENSATE/CIRCUIT_OPEN/...）
const TRIGGER_LABEL = {
  FIRST_SEND: '首送', COMPENSATE: '补偿重放', CIRCUIT_OPEN: '熔断短路',
  RECONCILE_MANUAL: '人工对账', TTL_DOWNGRADE: 'TTL 降级',
  REPLAY: '死信重放', EXHAUSTED: '重试耗尽'
}
const statusTag = s => ({ SUCCESS: 'success', DEAD_LETTER: 'danger', UNKNOWN: 'warning', COMPENSATING: 'warning', INIT: 'info', MAPPING: 'info' }[s] || 'info')
// 状态链时间线节点色（el-timeline-item type：primary/success/warning/danger/info）
const chainNodeType = s => ({ SUCCESS: 'success', DEAD_LETTER: 'danger', UNKNOWN: 'warning', COMPENSATING: 'warning', INIT: 'info', MAPPING: 'primary' }[s] || 'info')

const cards = ref([
  { title: '今日调用量', value: '—', sub: '' },
  { title: '今日成功率', value: '—', sub: '' },
  { title: '待补偿 / 待重送', value: '—', sub: '' },
  { title: '死信 / 对账中', value: '—', sub: '' }
])
const overview = ref(null)
const tab = ref('overview')

const goDashboard = () => router.push('/dashboard')

// ---------- 公共数据 ----------
const appOptions = ref([])
const ifaceOptions = ref([])

async function loadDicts() {
  try {
    const [apps, ifaces] = await Promise.all([http.get('/apps'), http.get('/interfaces')])
    appOptions.value = apps
    ifaceOptions.value = ifaces
  } catch (e) { console.warn('[monitor] 忽略的失败', e?.message || e) }
}

function onAppChange() {
  logFilter.value.interfaceId = null
}

const logIfaceOptions = computed(() =>
  logFilter.value.appId
    ? ifaceOptions.value.filter(i => i.appId === logFilter.value.appId)
    : ifaceOptions.value)

async function loadOverview() {
  try {
    overview.value = await http.get('/monitor/overview')
    const d = overview.value
    cards.value[0].value = d.todayCalls
    cards.value[0].sub = `成功 ${d.todaySuccess} · 死信 ${d.todayDeadLetter}`
    cards.value[1].value = d.successRate + '%'
    cards.value[1].sub = '今日终态（成功 / 死信）口径'
    cards.value[2].value = `${d.compensating} / ${d.pendingRedelivery}`
    cards.value[3].value = `${d.deadLetterBacklog} / ${d.unknown}`
  } catch (e) { console.warn('[monitor] 占位数据加载失败', e?.message || e) }
}

// ---------- Tab 总览 迷你趋势 ----------
const overviewChartEl = ref()
let overviewChart = null

async function loadOverviewTrend() {
  try {
    const d = await http.get('/monitor/stats/trend', { params: { range: '24h' } })
    await nextTick()
    if (!overviewChart && overviewChartEl.value) {
      overviewChart = echarts.init(overviewChartEl.value)
    }
    if (!overviewChart) return
    const buckets = d.buckets || []
    const tl = buckets.map(b => {
      const t = new Date(b.ts * 1000)
      return `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`
    })
    overviewChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: ['IN', 'DEAD+UNKNOWN'], top: 0 },
      grid: { left: 40, right: 12, top: 26, bottom: 22 },
      xAxis: { type: 'category', data: tl },
      yAxis: [{ type: 'value', minInterval: 1 }],
      series: [
        { name: 'IN', type: 'bar', data: buckets.map(b => b.inCalls), itemStyle: { color: '#3BA776' }, barMaxWidth: 10 },
        { name: 'DEAD+UNKNOWN', type: 'line', data: buckets.map(b => b.outDead + b.outUnknown), itemStyle: { color: '#F56C6C' } }
      ]
    }, true)
  } catch (e) { console.warn('[monitor] 忽略的失败', e?.message || e) }
}

// ---------- Tab 调用日志 ----------
const logs = ref([])
const logTotal = ref(0)
const logPage = ref(1)
const logFilter = ref({ range: null, direction: null, appId: null, interfaceId: null, statusGroup: null, traceId: '', keyword: '' })

async function loadLogs(page = 1) {
  logPage.value = page
  const f = logFilter.value
  try {
    const d = await http.get('/monitor/call-logs', {
      params: {
        page, pageSize: 20,
        traceId: f.traceId || undefined,
        interfaceId: f.interfaceId || undefined,
        direction: f.direction || undefined,
        appId: f.appId || undefined,
        statusGroup: f.statusGroup || undefined,
        timeFrom: f.range ? f.range[0] : undefined,
        timeTo: f.range ? f.range[1] : undefined,
        keyword: f.keyword || undefined
      }
    })
    logs.value = d.list
    logTotal.value = d.total
  } catch (e) { console.warn('[monitor] 加载失败（保持现有数据）', e?.message || e) }
}

// ---------- Tab 状态机与对账 ----------
const queueRows = ref([])
const queueTotal = ref(0)
const queuePage = ref(1)
const queueFilter = ref({ status: null, bizId: '', traceId: '' })

async function loadQueue(page = 1) {
  queuePage.value = page
  const f = queueFilter.value
  try {
    const d = await http.get('/monitor/outbound-requests', {
      params: { page, pageSize: 20, status: f.status || undefined, bizId: f.bizId || undefined, traceId: f.traceId || undefined }
    })
    queueRows.value = d.list
    queueTotal.value = d.total
  } catch (e) { console.warn('[monitor] 加载失败（保持现有数据）', e?.message || e) }
}

async function openQueueDetail(row) {
  const d = await http.get(`/monitor/outbound-requests/${row.id}`)
  detail.value = { visible: true, kind: 'queue', title: `出站记录 #${row.id} · ${STATUS_LABEL[row.status] || row.status}`, row: d }
}

const reconcile = ref({ visible: false, id: 0, target: 'SUCCESS', operator: 'admin', reason: '' })
function openReconcile(row, target) {
  reconcile.value = { visible: true, id: row.id, target, operator: 'admin', reason: '' }
}
async function submitReconcile() {
  const r = reconcile.value
  if (!r.operator.trim()) {
    ElMessage.warning('请填写操作人')
    return
  }
  await http.post(`/monitor/outbound-requests/${r.id}/reconcile`, {
    target: r.target, operator: r.operator.trim(), reason: r.reason.trim() || null
  })
  ElMessage.success('对账置位成功（审计已留痕）')
  reconcile.value.visible = false
  loadQueue()
  loadOverview()
}

// ---------- Tab 死信 ----------
const deadLetters = ref([])
const deadTotal = ref(0)
const deadPage = ref(1)
const deadFilter = ref({ bizType: null, status: null })

async function loadDeadLetters(page = 1) {
  deadPage.value = page
  const f = deadFilter.value
  try {
    const d = await http.get('/monitor/dead-letters', {
      params: { page, pageSize: 20, bizType: f.bizType || undefined, status: f.status || undefined }
    })
    deadLetters.value = d.list
    deadTotal.value = d.total
  } catch (e) { console.warn('[monitor] 加载失败（保持现有数据）', e?.message || e) }
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

// ---------- Tab 告警 ----------
const alerts = ref([])
const alertTotal = ref(0)
const alertPage = ref(1)
const rules = ref([])
const ruleVisible = ref(false)
const ruleForm = ref({ id: null, name: '', metric: 'dead_letter_backlog', threshold: '> 10', notifyChannel: '', enabled: true })

async function loadAlerts(page = 1) {
  alertPage.value = page
  try {
    const d = await http.get('/monitor/alerts', { params: { page, pageSize: 20 } })
    alerts.value = d.list
    alertTotal.value = d.total
  } catch (e) { console.warn('[monitor] 加载失败（保持现有数据）', e?.message || e) }
}
async function loadRules() {
  try {
    rules.value = await http.get('/monitor/alert-rules')
  } catch (e) { console.warn('[monitor] 加载失败（保持现有数据）', e?.message || e) }
}
function openRuleModal(rule) {
  ruleForm.value = rule
    ? { id: rule.id, name: rule.name, metric: rule.metric, threshold: rule.threshold,
        notifyChannel: rule.notifyChannel || '', enabled: rule.enabled }
    : { id: null, name: '', metric: 'dead_letter_backlog', threshold: '> 10', notifyChannel: '', enabled: true }
  ruleVisible.value = true
}
async function saveRule() {
  const f = ruleForm.value
  const body = { name: f.name, metric: f.metric, threshold: f.threshold, notifyChannel: f.notifyChannel || null, enabled: f.enabled }
  if (f.id) {
    await http.put(`/monitor/alert-rules/${f.id}`, body)
  } else {
    await http.post('/monitor/alert-rules', body)
  }
  ElMessage.success('规则已保存（生效延迟 ≤ 60s 缓存刷新）')
  ruleVisible.value = false
  loadRules()
}
async function toggleRule(row, enabled) {
  await http.put(`/monitor/alert-rules/${row.id}`, {
    name: row.name, metric: row.metric, threshold: row.threshold,
    notifyChannel: row.notifyChannel || null, enabled
  })
  ElMessage.success(enabled ? '已启用' : '已停用')
  loadRules()
}
async function deleteRule(rule) {
  await ElMessageBox.confirm(`删除告警规则「${rule.name}」？`, '删除', { type: 'warning' })
  await http.delete(`/monitor/alert-rules/${rule.id}`)
  loadRules()
}

// ---------- 详情抽屉 ----------
const detail = ref({ visible: false, kind: '', title: '', row: null })

// ---------- 抽屉宽度记忆（拖拽后持久化，刷新保留） ----------
const detailSize = ref(readDrawerWidth('drawer.monitor', 720))
function onDetailResizeEnd(size) {
  detailSize.value = Math.round(size)
  saveDrawerWidth('drawer.monitor', detailSize.value)
}

/**
 * 调用日志明细：列表接口已瘦身（不带 req_headers / req_body / resp_body，2026-09-12），
 * 打开抽屉时按 id 拉详情；拉取失败时先展示列表行（头部字段仍在），并给出提示。
 */
async function openLogDetail(row) {
  detail.value = { visible: true, kind: 'log', title: `调用日志 #${row.id}`, row }
  try {
    const full = await http.get(`/monitor/call-logs/${row.id}`)
    if (detail.value.visible && detail.value.kind === 'log' && detail.value.row?.id === row.id) {
      detail.value = { ...detail.value, row: full }
    }
  } catch (e) {
    console.warn('[monitor] 调用日志详情加载失败', row.id, e?.message || e)
  }
}
function openDeadDetail(row) {
  detail.value = { visible: true, kind: 'dead', title: `死信 #${row.id}`, row }
}

// ---------- 路由深链与轮询 ----------
function applyQuery() {
  const q = route.query
  if (q.tab === 'logs' || q.traceId || q.interfaceId) {
    tab.value = 'logs'
    if (q.traceId) logFilter.value.traceId = String(q.traceId)
    if (q.interfaceId) logFilter.value.interfaceId = Number(q.interfaceId)
    loadLogs(1)
  } else if (q.tab === 'dead') {
    tab.value = 'dead'
    if (q.status === 'HANDLED' || q.status === 'PENDING') deadFilter.value.status = q.status
    loadDeadLetters(1)
  } else if (q.tab === 'queue' || q.status || q.bizId) {
    tab.value = 'queue'
    if (q.status && STATUS_LABEL[q.status]) queueFilter.value.status = q.status
    if (q.bizId) queueFilter.value.bizId = String(q.bizId)
    loadQueue(1)
  }
}

let timer = null
onMounted(async () => {
  await Promise.all([loadOverview(), loadDicts()])
  loadOverviewTrend()
  await loadLogs(1)
  await loadQueue(1)
  loadDeadLetters(1)
  loadAlerts(1)
  loadRules()
  applyQuery()
  timer = setInterval(() => loadOverview(), 10000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  overviewChart?.dispose()
})
</script>

<style scoped>
.card-title { color: #909399; font-size: 13px; }
.card-value { font-size: 26px; font-weight: 600; margin-top: 8px; }
.card-sub { color: #c0c4cc; font-size: 12px; margin-top: 4px; }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.filters { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; align-items: center; }
.chart-sm { height: 200px; }
.tip { color: #909399; font-size: 12px; margin-top: 8px; }
.pager { margin-top: 8px; display: flex; justify-content: center; }
.rules-title { color: #909399; font-size: 13px; margin: 12px 0 4px; }
.side-title { margin: 12px 0 6px; color: #606266; font-size: 13px; }
.chain-main { display: flex; align-items: baseline; gap: 6px; }
.chain-meta { color: #a8abb2; font-size: 12px; margin-left: auto; padding-right: 6px; }
.chain-sub { color: #909399; font-size: 12px; line-height: 1.5; word-break: break-all; }
</style>
