<template>
  <div>
    <el-select :model-value="modelValue.impl" @update:model-value="onImplChange"
               placeholder="选择实现类" style="width: 100%">
      <el-option v-for="m in options" :key="m.impl" :label="`${m.name}（${m.impl}）`" :value="m.impl" />
    </el-select>
    <!-- 信封适配器的语义提示：平台统一归化响应为 { code, msg, data } -->
    <div v-if="currentMeta && currentMeta.impl === 'EnvelopeMessageAdapter'" class="impl-hint">
      <b>平台统一归化响应为 { code, msg, data }</b>
      <p>你配置的是「如何解读上游响应」，平台回给调用方始终是统一信封：</p>
      <ul>
        <li><code>code</code>：上游业务成功（<code>codeField</code> = <code>successValue</code>）时为 0；失败时取 <code>codeMappings</code> 映射的平台码，未命中用 <code>defaultErrorCode</code>；</li>
        <li><code>msg</code>：成功固定 "ok"；失败透传上游 <code>messageField</code> 内容；</li>
        <li><code>data</code>：即上游 <code>envelope</code> 字段内的业务内容（如 fastmoss 的 total/list），失败时为空。</li>
      </ul>
    </div>
    <template v-if="currentMeta">
      <el-form-item v-for="f in currentMeta.fields" :key="f.key" :label="f.label" :required="f.required"
                    style="margin-top: 12px">
        <div style="width: 100%">
          <!-- 提示尽量放 placeholder（输入前即见）；switch 无占位，用行内灰字兜底 -->
          <el-select v-if="f.kind === 'select'" v-model="modelValue.params[f.key]" clearable
                     :placeholder="f.hint || '请选择'" style="width: 100%">
            <el-option v-for="o in f.options" :key="o" :label="o" :value="o" />
          </el-select>
          <el-input-number v-else-if="f.kind === 'number'" v-model="modelValue.params[f.key]"
                           :placeholder="f.hint" style="width: 100%" />
          <div v-else-if="f.kind === 'switch'" style="display: flex; align-items: center; gap: 10px">
            <el-switch v-model="modelValue.params[f.key]" />
            <span v-if="f.hint" class="f-inline-hint">{{ f.hint }}</span>
          </div>
          <el-input v-else-if="f.kind === 'textarea'" v-model="modelValue.params[f.key]" type="textarea"
                    :rows="3" :placeholder="f.hint || '请输入'" />
          <el-input v-else-if="f.kind === 'codeMap'" v-model="modelValue.params[f.key]" type="textarea"
                    :rows="3" :placeholder="f.hint || '上游码→平台码，逗号分隔'" />
          <el-input v-else-if="f.kind === 'secret'" disabled
                    :placeholder="f.hint || '凭证值请到「应用管理 → 点击应用 → 凭证」中维护'" />
          <el-input v-else v-model="modelValue.params[f.key]" :placeholder="f.hint || '请输入'" />
        </div>
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

<style scoped>
.impl-hint {
  background: #f0f7ff;
  border: 1px solid #d6e4ff;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 12px;
  color: #3b4c8f;
  line-height: 1.7;
  margin-bottom: 4px;
}
.impl-hint b { font-size: 13px; }
.impl-hint p { margin: 4px 0 2px; }
.impl-hint ul { margin: 0; padding-left: 18px; }
.impl-hint code {
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  background: rgba(59, 76, 143, 0.08);
  padding: 0 3px;
  border-radius: 3px;
}
.f-inline-hint {
  font-size: 12px;
  color: #a0a4ab;
  line-height: 1.5;
}
</style>
