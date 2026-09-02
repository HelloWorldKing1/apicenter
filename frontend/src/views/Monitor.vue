<template>
  <div>
    <!-- 接口监控(M1 静态占位;M4 接入调用日志/指标/链路/告警真实数据) -->
    <el-row :gutter="16">
      <el-col :span="6" v-for="card in cards" :key="card.title">
        <el-card shadow="hover">
          <div class="card-title">{{ card.title }}</div>
          <div class="card-value">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header>监控能力规划(M4 落地)</template>
      <el-table :data="plan" size="small">
        <el-table-column prop="item" label="能力" width="200" />
        <el-table-column prop="desc" label="说明" />
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

const plan = ref([
  { item: '调用日志', desc: 'AOP 统一拦截 + traceId 贯穿 + 敏感字段脱敏，落 call_log' },
  { item: '指标', desc: 'Micrometer/Prometheus：调用量 / 成功率 / P50/P95/P99，按接口 / 应用聚合' },
  { item: '链路追踪', desc: 'OpenTelemetry 自动埋点 + 适配器节点手动 span' },
  { item: '告警', desc: '成功率下降 / 延迟超限 / 死信堆积 / 补偿失败，通知渠道邮件 / IM' },
  { item: '对账与死信', desc: 'UNKNOWN 人工对账入口 + 死信查看与重放' }
])
</script>

<style scoped>
.card-title { color: #909399; font-size: 13px; }
.card-value { font-size: 28px; font-weight: 600; margin-top: 8px; }
</style>
