<template>
  <div>
    <el-select :model-value="modelValue.impl" @update:model-value="onImplChange"
               placeholder="选择实现类" style="width: 100%">
      <el-option v-for="m in options" :key="m.impl" :label="`${m.name}（${m.impl}）`" :value="m.impl" />
    </el-select>
    <template v-if="currentMeta">
      <el-form-item v-for="f in currentMeta.fields" :key="f.key" :label="f.label" :required="f.required"
                    style="margin-top: 12px">
        <el-select v-if="f.kind === 'select'" v-model="modelValue.params[f.key]" clearable style="width: 100%">
          <el-option v-for="o in f.options" :key="o" :label="o" :value="o" />
        </el-select>
        <el-input-number v-else-if="f.kind === 'number'" v-model="modelValue.params[f.key]" style="width: 100%" />
        <el-switch v-else-if="f.kind === 'switch'" v-model="modelValue.params[f.key]" />
        <el-input v-else-if="f.kind === 'textarea'" v-model="modelValue.params[f.key]" type="textarea" :rows="3" />
        <el-input v-else-if="f.kind === 'codeMap'" v-model="modelValue.params[f.key]" placeholder="上游码→平台码，逗号分隔" />
        <el-input v-else-if="f.kind === 'secret'" disabled placeholder="凭证值请到「应用管理 → 点击应用 → 凭证」中维护" />
        <el-input v-else v-model="modelValue.params[f.key]" />
      </el-form-item>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'

// 适配器参数编辑器（impl 元数据驱动动态表单）：
// 适配器页与应用弹窗内联创建共用（原型 ADAPTER_FIELDS 模式）。
// modelValue = { impl: '', params: {} }，父组件持有对象引用。
const props = defineProps({
  impls: { type: Array, default: () => [] },
  type: { type: String, default: 'auth' },
  modelValue: { type: Object, required: true }
})
const emit = defineEmits(['update:modelValue'])

const options = computed(() => props.impls.filter((m) => m.type === props.type))
const currentMeta = computed(() => props.impls.find((m) => m.impl === props.modelValue.impl))

function onImplChange(impl) {
  emit('update:modelValue', { impl, params: {} })
}
</script>
