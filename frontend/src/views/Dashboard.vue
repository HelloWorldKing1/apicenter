<template>
  <div>
    <!-- 概览统计卡（应用/接口接后端真数据；调用量/成功率为 M4 指标） -->
    <el-row :gutter="16">
      <el-col :span="6" v-for="card in cards" :key="card.title">
        <el-card shadow="hover">
          <div class="card-title">{{ card.title }}</div>
          <div class="card-value">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 最近调用日志（原型平移；call_log 为 M4 落库，当前为形态占位） -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>最近调用日志（M4 接入 call_log 真实数据）</template>
      <el-table :data="recentLogs" size="small">
        <el-table-column prop="time" label="时间" width="170" />
        <el-table-column prop="iface" label="接口" width="180" />
        <el-table-column prop="app" label="应用" width="120" />
        <el-table-column label="状态码" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.code === 200 ? 'success' : 'danger'">{{ row.code }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ms" label="耗时" width="90" />
        <el-table-column prop="trace" label="traceId（贯穿全链路）" show-overflow-tooltip />
      </el-table>
    </el-card>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header>使用提示</template>
      <p>当前里程碑：M2 已完成 —— 出站链路（链引擎 + 映射引擎 + 状态机）黄金用例端到端跑通。</p>
      <p>种子数据：fastmoss 黄金用例（应用 + Bearer 凭证 + 接口 IF-FM-001）随应用启动幂等导入。</p>
      <p>下一步：M3 协议与入站（XML 编解码 + 入站回调链路）。</p>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '@/api/http'

const cards = ref([
  { title: '应用数', value: '—' },
  { title: '接口数', value: '—' },
  { title: '今日调用量', value: '—（M4）' },
  { title: '成功率', value: '—（M4）' }
])

// 最近调用日志形态占位（M4 接 call_log；原型为写死模拟数据）
const recentLogs = ref([])

onMounted(async () => {
  try {
    const apps = await http.get('/apps')
    const ifaces = await http.get('/interfaces')
    cards.value[0].value = apps.length
    cards.value[1].value = ifaces.length
  } catch { /* 后端未启动时保持占位 */ }
})
</script>

<style scoped>
.card-title { color: #909399; font-size: 13px; }
.card-value { font-size: 28px; font-weight: 600; margin-top: 8px; }
</style>
