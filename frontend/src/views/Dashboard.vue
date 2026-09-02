<template>
  <div>
    <!-- 概览统计卡(M1 静态占位;M4 接真实指标) -->
    <el-row :gutter="16">
      <el-col :span="6" v-for="card in cards" :key="card.title">
        <el-card shadow="hover">
          <div class="card-title">{{ card.title }}</div>
          <div class="card-value">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header>使用提示</template>
      <p>当前里程碑：M1 骨架 —— 应用 / 分组 / 接口 / 适配器配置闭环。</p>
      <p>种子数据：fastmoss 黄金用例（应用 + Bearer 凭证 + 接口 IF-FM-001）随应用启动幂等导入。</p>
      <p>下一步：M2 出站链路（适配器链引擎 + 动态映射引擎 + 状态机），出口基准 = fastmoss 黄金用例端到端跑通。</p>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const cards = ref([
  { title: '应用数', value: '—' },
  { title: '接口数', value: '—' },
  { title: '今日调用量', value: '—' },
  { title: '成功率', value: '—' }
])
</script>

<style scoped>
.card-title { color: #909399; font-size: 13px; }
.card-value { font-size: 28px; font-weight: 600; margin-top: 8px; }
</style>
