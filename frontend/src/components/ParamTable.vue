<template>
  <div>
    <el-table :data="rows" size="small">
      <el-table-column label="参数名">
        <template #default="s">
          <el-input v-model="s.row.name" size="small" placeholder="支持点号路径，如 filter.seller_id" />
        </template>
      </el-table-column>
      <el-table-column label="类型" width="140">
        <template #default="s">
          <el-select v-model="s.row.type" size="small">
            <el-option v-for="t in types" :key="t" :label="t" :value="t" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="必填" width="70">
        <template #default="s">
          <el-switch v-model="s.row.required" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="示例值">
        <template #default="s">
          <el-input v-model="s.row.sample" size="small" />
        </template>
      </el-table-column>
      <el-table-column width="70">
        <template #default="s">
          <el-button link type="danger" @click="del(s.$index)">删</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button size="small" style="margin-top: 8px" @click="add">＋ 添加参数</el-button>
  </div>
</template>

<script setup>
import { ref } from 'vue'

// 参数行编辑组件：由父组件经 v-model 传入当前侧的参数行数组（原地编辑）
const props = defineProps({
  modelValue: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

const types = ref(['string', 'number', 'boolean', 'object', 'array'])
const rows = props.modelValue

function add() {
  rows.push({ name: '', type: 'string', required: false, sample: '', sortOrder: rows.length })
  emit('update:modelValue', rows)
}
function del(i) {
  rows.splice(i, 1)
  emit('update:modelValue', rows)
}
</script>
