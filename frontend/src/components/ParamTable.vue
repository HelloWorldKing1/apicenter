<template>
  <div>
    <el-table :data="modelValue" size="small">
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
      <el-table-column width="50">
        <template #default="s">
          <el-button link type="danger" @click="del(s.$index)">×</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="modelValue.length === 0" class="empty-hint">暂无参数</div>
    <el-button size="small" class="add-btn" @click="add">＋ 添加参数</el-button>
  </div>
</template>

<script setup>
import { ref } from 'vue'

// 参数行编辑组件：v-model 绑定父组件的**真实数组引用**（原地编辑，直接生效）。
// 注意：不要在 setup 里缓存 props.modelValue——computed 代理下引用会失效；
// 模板始终用 modelValue，行级增删由父组件的响应式数组承担。
const props = defineProps({
  modelValue: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

const types = ref(['string', 'number', 'boolean', 'object', 'array'])

function add() {
  props.modelValue.push({ name: '', type: 'string', required: false, sample: '', sortOrder: props.modelValue.length })
  emit('update:modelValue', props.modelValue)
}
function del(i) {
  props.modelValue.splice(i, 1)
  emit('update:modelValue', props.modelValue)
}
</script>

<style scoped>
.add-btn { margin-top: 8px; }
.empty-hint {
  text-align: center;
  color: #c0c4cc;
  font-size: 13px;
  padding: 10px 0;
}
</style>
