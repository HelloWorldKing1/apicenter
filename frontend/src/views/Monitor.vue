<template>
  <div>
    <!-- 监控统计卡（M4 接真实指标） -->
    <el-row :gutter="16">
      <el-col :span="6" v-for="card in cards" :key="card.title">
        <el-card shadow="hover">
          <div class="card-title">{{ card.title }}</div>
          <div class="card-value">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 调用日志表（原型形态平移；M4 接 call_log 真实数据） -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>调用日志（M4 接真实数据）</template>
      <el-table :data="logs" size="small">
        <el-table-column prop="time" label="时间" width="170" />
        <el-table-column prop="iface" label="接口" width="200" show-overflow-tooltip />
        <el-table-column prop="app" label="应用" width="120" />
        <el-table-column label="状态码" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.code < 400 ? 'success' : row.code < 500 ? 'warning' : 'danger'">
              {{ row.code }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ms" label="耗时" width="90" />
        <el-table-column prop="trace" label="traceId（贯穿全链路）" show-overflow-tooltip />
      </el-table>
    </el-card>

    <!-- 告警列表（原型形态平移；M4 接 alert_rule 触发数据） -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>告警（M4 接真实数据）</template>
      <el-table :data="alerts" size="small">
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.level === '严重' ? 'danger' : 'warning'">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="msg" label="内容" />
        <el-table-column prop="time" label="时间" width="170" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const cards = ref([
  { title: '今日调用量', value: '—' },
  { title: '成功率', value: '—' },
  { title: 'P99 延迟', value: '—' },
  { title: '待补偿 / 死信 / 对账中', value: '— / — / —' }
])

const logs = ref([])
const alerts = ref([])
</script>

<style scoped>
.card-title { color: #909399; font-size: 13px; }
.card-value { font-size: 28px; font-weight: 600; margin-top: 8px; }
</style>
