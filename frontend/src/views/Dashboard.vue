<template>
  <div>
    <!-- 概览统计卡：应用 / 接口 + M4 真数据（今日调用量 / 成功率，来源 /monitor/overview） -->
    <el-row :gutter="16">
      <el-col :span="6" v-for="card in cards" :key="card.title">
        <el-card shadow="hover">
          <div class="card-title">{{ card.title }}</div>
          <div class="card-value">{{ card.value }}</div>
          <div class="card-sub" v-if="card.sub">{{ card.sub }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 最近调用日志（call_log 实时数据，M4 起落库） -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="panel-head">
          <span>最近调用日志（call_log 实时数据，最近 8 条）</span>
          <el-button size="small" @click="loadRecent">刷新</el-button>
        </div>
      </template>
      <el-table :data="recentLogs" size="small">
        <el-table-column prop="time" label="时间" width="170" show-overflow-tooltip />
        <el-table-column label="方向" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.dir === 'IN' ? 'info' : 'warning'">{{ row.dir }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="iface" label="接口ID" width="100" />
        <el-table-column prop="app" label="应用" width="140" show-overflow-tooltip />
        <el-table-column label="状态码" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.code === 200 ? 'success' : 'danger'">{{ row.code }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ms" label="耗时(ms)" width="90" />
        <el-table-column prop="trace" label="traceId（贯穿全链路）" show-overflow-tooltip />
      </el-table>
      <div class="tip">完整日志与过滤见「接口监控」页（M4 全量监控面板）。</div>
    </el-card>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header>使用提示</template>
      <p>当前里程碑：M0–M4 已完成 —— 出站 / 入站全链路 + 容错底座（熔断 / UNKNOWN 对账 / 死信重放）+ 接入层防护（QPS 限流 / IP 名单）+ 可观测（call_log 双向 / 脱敏 / 指标 / 告警）。</p>
      <p>种子数据：fastmoss 黄金用例（应用 + Bearer 凭证 + 接口 IF-FM-001 + 入站回调 IF-FM-CB-001）随应用启动幂等导入。</p>
      <p>下一步：M5 版本快照 / 灰度 / 压测（计划已评审定稿）。</p>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '@/api/http'

const cards = ref([
  { title: '应用数', value: '—', sub: '' },
  { title: '接口数', value: '—', sub: '' },
  { title: '今日调用量', value: '—', sub: '' },
  { title: '今日成功率', value: '—', sub: '今日终态（成功 / 死信）口径' }
])

const recentLogs = ref([])

onMounted(() => {
  loadStats()
  loadRecent()
})

async function loadStats() {
  try {
    const [apps, ifaces, overview] = await Promise.all([
      http.get('/apps'),
      http.get('/interfaces'),
      http.get('/monitor/overview')
    ])
    cards.value[0].value = apps.length
    cards.value[1].value = ifaces.length
    cards.value[2].value = overview.todayCalls
    cards.value[2].sub = `今日成功 ${overview.todaySuccess} · 死信 ${overview.todayDeadLetter}`
    cards.value[3].value = overview.successRate + '%'
  } catch { /* 后端未启动时保持占位 */ }
}

// 最近调用日志：复用 /monitor/call-logs（call_log 双向落库，IN/OUT 各一条）
async function loadRecent() {
  try {
    const d = await http.get('/monitor/call-logs', { params: { page: 1, pageSize: 8 } })
    recentLogs.value = (d.list || []).map(r => ({
      time: r.createdAt, dir: r.direction, iface: r.interfaceId,
      app: r.appId, code: r.statusCode, ms: r.latencyMs, trace: r.traceId
    }))
  } catch { /* 保持现有数据 */ }
}
</script>

<style scoped>
.card-title { color: #909399; font-size: 13px; }
.card-value { font-size: 28px; font-weight: 600; margin-top: 8px; }
.card-sub { color: #c0c4cc; font-size: 12px; margin-top: 4px; }
.panel-head { display: flex; align-items: center; justify-content: space-between; }
.tip { color: #909399; font-size: 12px; margin-top: 8px; }
</style>
