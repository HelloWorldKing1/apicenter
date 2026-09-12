<template>
  <div>
    <!-- ===== 主卡区：应用/接口 + 今日调用/成功率（overview 口径），附积压角标 ===== -->
    <el-row :gutter="16">
      <el-col :span="6" v-for="card in cards" :key="card.title">
        <el-card shadow="hover">
          <div class="card-title">{{ card.title }}</div>
          <div class="card-value">{{ card.value }}</div>
          <div class="card-sub" v-if="card.sub">{{ card.sub }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 健康角标（积压/对账），点击深链到监控对应区块 -->
    <div class="badge-row" v-if="overview">
      <el-button v-if="overview.compensating + overview.pendingRedelivery > 0" size="small" type="warning" plain
                 @click="goMonitor({ tab: 'queue' })">
        待补偿/待重送：{{ overview.compensating }}/{{ overview.pendingRedelivery }} → 处理
      </el-button>
      <el-button v-if="overview.deadLetterBacklog + overview.unknown > 0" size="small" type="danger" plain
                 @click="goMonitor({ tab: overview.unknown > 0 ? 'queue' : 'dead' })">
        死信/对账中：{{ overview.deadLetterBacklog }}/{{ overview.unknown }} → 处理
      </el-button>
      <span v-if="!overview || overview.compensating + overview.pendingRedelivery + overview.deadLetterBacklog + overview.unknown === 0"
            class="muted">健康：无积压 · 无对账中</span>
    </div>

    <el-row :gutter="16" style="margin-top: 12px">
      <!-- ===== 左列：趋势与延迟 ===== -->
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <div class="panel-head">
              <span>调用流量（IN 入站 / 出站终态失败）· {{ rangeLabel }}</span>
              <div class="head-right">
                <el-radio-group v-model="range" size="small" @change="onRangeChange">
                  <el-radio-button value="1h">近1小时</el-radio-button>
                  <el-radio-button value="24h">近24小时</el-radio-button>
                  <el-radio-button value="7d">近7天</el-radio-button>
                </el-radio-group>
                <el-button size="small" @click="loadStats">刷新</el-button>
              </div>
            </div>
          </template>
          <div ref="trafficChartEl" class="chart"></div>
          <div v-if="!trendLoading && (!trend || !trend.buckets || !trend.buckets.length)" class="empty-hint">
            当前时间窗无数据 —— 调一次接口或到「接口监控」看调用日志
          </div>
          <div class="chart-tip">IN = 平台收到的请求；出站失败 = 状态机终态 DEAD_LETTER + UNKNOWN（成功率以终态口径计）</div>
        </el-card>
        <el-card shadow="never" style="margin-top: 16px">
          <template #header>
            <div class="panel-head"><span>出站延迟 P50 / P99（ms）· {{ rangeLabel }}</span></div>
          </template>
          <div ref="latencyChartEl" class="chart"></div>
          <div class="chart-tip">近似口径：时间窗内最新 2 万条 OUT 调用按桶分位（与告警 p99 同源近似，见设计文档口径表）</div>
        </el-card>
      </el-col>

      <!-- ===== 右列：TOP 接口 + 运行队列 ===== -->
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <div class="panel-head"><span>TOP 接口 · {{ rangeLabel }}</span>
              <el-button size="small" @click="loadTop">刷新</el-button></div>
          </template>
          <el-table :data="topList" size="small" max-height="340">
            <el-table-column label="接口" min-width="130">
              <template #default="{ row }">
                <div class="cell-code">{{ row.code }}</div>
                <div class="cell-path">{{ row.path }}</div>
              </template>
            </el-table-column>
            <el-table-column label="应用" prop="appName" width="90" show-overflow-tooltip />
            <el-table-column label="调用" prop="inCalls" width="70" align="right" />
            <el-table-column label="成功率" width="84" align="right">
              <template #default="{ row }">
                <span :class="row.successRate !== null && row.successRate < 95 ? 'rate-bad' : ''">
                  {{ row.successRate !== null ? row.successRate + '%' : '—' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="P99" width="80" align="right">
              <template #default="{ row }">{{ row.p99 || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="64" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small"
                           @click="goMonitor({ tab: 'logs', interfaceId: row.interfaceId })">监控</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" style="margin-top: 16px">
          <template #header><div class="panel-head"><span>运行队列（当前快照 · 10s 刷新）</span></div></template>
          <el-descriptions :column="2" size="small" border v-if="overview">
            <el-descriptions-item label="待补偿">
              {{ overview.compensating }}<el-button v-if="overview.compensating > 0" link type="primary" size="small"
                 @click="goMonitor({ tab: 'queue', status: 'COMPENSATING' })">查看</el-button>
            </el-descriptions-item>
            <el-descriptions-item label="待重送（入站）">
              {{ overview.pendingRedelivery }}<el-button v-if="overview.pendingRedelivery > 0" link type="primary" size="small"
                 @click="goMonitor({ tab: 'dead' })">去死信</el-button>
            </el-descriptions-item>
            <el-descriptions-item label="死信（PENDING）">
              {{ overview.deadLetterBacklog }}<el-button v-if="overview.deadLetterBacklog > 0" link type="primary" size="small"
                 @click="goMonitor({ tab: 'dead' })">查看</el-button>
            </el-descriptions-item>
            <el-descriptions-item label="对账（UNKNOWN）">
              {{ overview.unknown }}<el-button v-if="overview.unknown > 0" link type="primary" size="small"
                 @click="goMonitor({ tab: 'queue', status: 'UNKNOWN' })">处理</el-button>
            </el-descriptions-item>
          </el-descriptions>
          <div v-else class="empty-hint">暂无数据</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ===== 最近调用 + 详情抽屉 ===== -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="panel-head">
          <span>最近调用日志（call_log 实时数据）</span>
          <el-button size="small" @click="loadRecent">刷新</el-button>
        </div>
      </template>
      <el-table :data="recentLogs" size="small" @row-click="openLogDetail">
        <el-table-column prop="time" label="时间" width="170" show-overflow-tooltip />
        <el-table-column label="方向" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.dir === 'IN' ? 'info' : 'warning'">{{ row.dir }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="iface" label="接口ID" width="100" />
        <el-table-column prop="app" label="应用" width="140" show-overflow-tooltip />
        <el-table-column prop="url" label="URL" show-overflow-tooltip />
        <el-table-column label="状态码" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.code >= 200 && row.code < 300 ? 'success' : 'danger'">{{ row.code }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ms" label="耗时(ms)" width="90" />
        <el-table-column prop="trace" label="traceId（贯穿全链路）" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="goMonitor({ tab: 'logs', traceId: row.trace })">追踪</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="tip">点击行查看脱敏请求/响应明细；完整检索见「接口监控」。</div>
    </el-card>

    <!-- ===== 日志详情抽屉（脱敏后原文，落库即脱敏 + 4096 截断；JSON/XML 可美化） ===== -->
    <el-drawer v-model="logDrawer.visible" :size="logDrawerSize + 'px'" resizable
               @resize-end="onLogDrawerResizeEnd" :title="`调用日志 #${logDrawer.row?.id || ''}`">
      <template v-if="logDrawer.row">
        <el-descriptions :column="1" size="small" border style="margin-bottom: 12px">
          <el-descriptions-item label="方向">{{ logDrawer.row.dir }} · {{ logDrawer.row.method }}</el-descriptions-item>
          <el-descriptions-item label="URL">{{ logDrawer.row.url }}</el-descriptions-item>
          <el-descriptions-item label="接口 / 应用">{{ logDrawer.row.iface }} / {{ logDrawer.row.app }}</el-descriptions-item>
          <el-descriptions-item label="HTTP / 耗时">
            {{ logDrawer.row.code }} / {{ logDrawer.row.ms }}ms
          </el-descriptions-item>
          <el-descriptions-item label="traceId">{{ logDrawer.row.trace }}</el-descriptions-item>
        </el-descriptions>
        <h4 class="side-title">请求头（已脱敏）</h4>
        <PayloadViewer :text="logDrawer.row.reqHeaders" headers />
        <h4 class="side-title">请求体</h4>
        <PayloadViewer :text="logDrawer.row.reqBody" :content-type="contentTypeOf(logDrawer.row.reqHeaders)"
                       :context="logContext(logDrawer.row)" />
        <h4 class="side-title">响应体</h4>
        <PayloadViewer :text="logDrawer.row.respBody" :context="logContext(logDrawer.row)" />
        <el-button size="small" @click="goMonitor({ tab: 'logs', traceId: logDrawer.row.trace })">在监控中追踪该 traceId</el-button>
      </template>
    </el-drawer>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header><span>数据说明（口径）</span></template>
      <ul class="data-notes">
        <li><b>今日成功率</b>：状态机终态口径 = 今日 SUCCESS / (SUCCESS + DEAD_LETTER)，当日无终态显示 100%（不含业务码失败的 HTTP 2xx，勿用 call_log 反推）；</li>
        <li><b>延迟 P50/P99</b>：call_log OUT 条按桶分位，窗口内最新 2 万条近似，与告警 p99 同源；</li>
        <li><b>趋势出站失败</b>：DEAD_LETTER + UNKNOWN 终态计数（按 updated_at 分桶），补偿进行中的瞬时失败不计入；</li>
        <li>聚合端点 60s 缓存；今日卡 10s 轮询。长期保留策略（归档）属 v1.1。</li>
      </ul>
    </el-card>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, nextTick, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import http from '@/api/http'
import PayloadViewer from '@/components/PayloadViewer.vue'
import { contentTypeOf } from '@/utils/payload.mjs'
import { readDrawerWidth, saveDrawerWidth } from '@/utils/prefs.mjs'

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const router = useRouter()
const cards = ref([
  { title: '应用数', value: '—', sub: '' },
  { title: '接口数', value: '—', sub: '' },
  { title: '今日调用量', value: '—', sub: '' },
  { title: '今日成功率', value: '—', sub: '今日终态（成功 / 死信）口径' }
])
const overview = ref(null)
const range = ref('24h')
const rangeLabel = ref('近24小时')
const recentLogs = ref([])
const topList = ref([])
const trendLoading = ref(false)
const trend = ref(null)
const latency = ref(null)
const trafficChartEl = ref()
const latencyChartEl = ref()
let trafficChart = null
let latencyChart = null
const logDrawer = ref({ visible: false, row: null })
let overviewTimer = null

const RANGE_LABEL = { '1h': '近1小时', '24h': '近24小时', '7d': '近7天' }

function goMonitor(query) {
  router.push({ path: '/monitor', query })
}

onMounted(async () => {
  await Promise.all([loadCards(), loadRecent(), loadStats(), loadTop()])
  nextTick(() => {
    renderTraffic()
    renderLatency()
  })
  overviewTimer = setInterval(loadOverviewOnly, 10000)
})

onBeforeUnmount(() => {
  if (overviewTimer) clearInterval(overviewTimer)
  trafficChart?.dispose()
  latencyChart?.dispose()
})

async function loadCards() {
  try {
    const [apps, ifaces, ov] = await Promise.all([
      http.get('/apps'), http.get('/interfaces'), http.get('/monitor/overview')
    ])
    cards.value[0].value = apps.length
    const published = ifaces.filter(i => i.status === 'PUBLISHED').length
    cards.value[0].sub = `已启用 ${apps.filter(a => a.status === 'ENABLED').length}`
    cards.value[1].value = ifaces.length
    cards.value[1].sub = `已发布 ${published}`
    overview.value = ov
    cards.value[2].value = ov.todayCalls
    cards.value[2].sub = `成功 ${ov.todaySuccess} · 死信 ${ov.todayDeadLetter}`
    cards.value[3].value = ov.successRate + '%'
  } catch { /* 后端未启动保持占位 */ }
}

async function loadOverviewOnly() {
  try {
    overview.value = await http.get('/monitor/overview')
  } catch { /* 忽略瞬时失败 */ }
}

async function loadStats() {
  trendLoading.value = true
  try {
    const d = await http.get('/monitor/stats/trend', { params: { range: range.value } })
    trend.value = d
    latency.value = d
    rangeLabel.value = RANGE_LABEL[range.value] || d.range
    await nextTick()
    renderTraffic()
    renderLatency()
  } catch { /* 保持现有 */ } finally { trendLoading.value = false }
}

function onRangeChange() {
  loadStats()
  loadTop()
}

async function loadTop() {
  try {
    topList.value = await http.get('/monitor/stats/top-interfaces', { params: { range: range.value, limit: 8 } })
  } catch { /* 保持现有 */ }
}

async function loadRecent() {
  try {
    const d = await http.get('/monitor/call-logs', { params: { page: 1, pageSize: 8 } })
    recentLogs.value = (d.list || []).map(r => ({
      id: r.id, time: r.createdAt, dir: r.direction, iface: r.interfaceId,
      app: r.appId, url: r.url, method: r.method, code: r.statusCode, ms: r.latencyMs,
      trace: r.traceId, reqHeaders: r.reqHeaders, reqBody: r.reqBody, respBody: r.respBody
    }))
  } catch { /* 保持现有 */ }
}

// 抽屉宽度记忆（拖拽后持久化，刷新保留）
const logDrawerSize = ref(readDrawerWidth('drawer.dashboard', 720))
function onLogDrawerResizeEnd(size) {
  logDrawerSize.value = Math.round(size)
  saveDrawerWidth('drawer.dashboard', logDrawerSize.value)
}

// 「复制含上下文」前缀（Dashboard 行字段已映射为 dir/method/iface/app/code/ms/trace）
const logContext = (row) => `调用日志 #${row.id} ${row.dir} ${row.method} ${row.url}\n`
  + `traceId=${row.trace} app=${row.app} interface=${row.iface} status=${row.code} ${row.ms}ms`

function openLogDetail(row) {
  logDrawer.value = { visible: true, row }
}

// ---------- 图表 ----------
function timeLabel(ts, rangeVal) {
  const d = new Date(ts * 1000)
  const p = n => String(n).padStart(2, '0')
  return rangeVal === '7d' ? `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}h`
    : `${p(d.getHours())}:${p(d.getMinutes())}`
}

function renderTraffic() {
  const buckets = trend.value?.buckets || []
  if (!trafficChartEl.value) return
  if (!trafficChart) {
    trafficChart = echarts.init(trafficChartEl.value)
  }
  trafficChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['IN 调用', '出站终态失败(DEAD+UNKNOWN)'], top: 0 },
    grid: { left: 48, right: 16, top: 28, bottom: 24 },
    xAxis: { type: 'category', data: buckets.map(b => timeLabel(b.ts, range.value)) },
    yAxis: [{ type: 'value', minInterval: 1 }],
    series: [
      { name: 'IN 调用', type: 'bar', data: buckets.map(b => b.inCalls), itemStyle: { color: '#3BA776' }, barMaxWidth: 18 },
      { name: '出站终态失败(DEAD+UNKNOWN)', type: 'line',
        data: buckets.map(b => b.outDead + b.outUnknown), itemStyle: { color: '#F56C6C' } }
    ]
  }, true)
}

function renderLatency() {
  const buckets = latency.value?.buckets || []
  if (!latencyChartEl.value) return
  if (!latencyChart) {
    latencyChart = echarts.init(latencyChartEl.value)
  }
  latencyChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['P50', 'P99'], top: 0 },
    grid: { left: 48, right: 16, top: 28, bottom: 24 },
    xAxis: { type: 'category', data: buckets.map(b => timeLabel(b.ts, range.value)) },
    yAxis: [{ type: 'value' }],
    series: [
      { name: 'P50', type: 'line', smooth: true, data: buckets.map(b => b.p50), itemStyle: { color: '#409EFF' } },
      { name: 'P99', type: 'line', smooth: true, data: buckets.map(b => b.p99), itemStyle: { color: '#E6A23C' } }
    ]
  }, true)
}
</script>

<style scoped>
.card-title { color: #909399; font-size: 13px; }
.card-value { font-size: 28px; font-weight: 600; margin-top: 8px; }
.card-sub { color: #c0c4cc; font-size: 12px; margin-top: 4px; }
.badge-row { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
.muted { color: #909399; font-size: 13px; line-height: 28px; }
.panel-head { display: flex; align-items: center; justify-content: space-between; }
.head-right { display: flex; gap: 8px; align-items: center; }
.chart { height: 240px; }
.chart-tip { color: #909399; font-size: 12px; margin-top: 6px; }
.empty-hint { color: #c0c4cc; font-size: 12px; padding: 8px 0; }
.cell-code { font-weight: 600; font-size: 12px; }
.cell-path { color: #c0c4cc; font-size: 11px; }
.rate-bad { color: #F56C6C; font-weight: 600; }
.tip { color: #909399; font-size: 12px; margin-top: 8px; }
.side-title { margin: 10px 0 6px; color: #606266; font-size: 13px; }
.mono-block {
  background: #F7F8FA; border: 1px solid #EBEEF5; border-radius: 6px;
  padding: 10px; font-family: 'SF Mono', Menlo, Consolas, monospace;
  font-size: 12px; max-height: 220px; overflow: auto; white-space: pre-wrap; word-break: break-all;
}
.data-notes { margin: 0; padding-left: 18px; color: #606266; font-size: 13px; line-height: 1.9; }
</style>
