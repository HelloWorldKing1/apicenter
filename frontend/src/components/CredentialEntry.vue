<template>
  <div class="cred-card" :class="{ 'cred-card--readonly': !editable }">
    <!-- 状态行：新建态「待创建」；编辑态遮显状态（永不回显明文） -->
    <div class="cred-status">
      <el-tag v-if="pending" size="small" type="info">待创建</el-tag>
      <template v-else-if="credential">
        <el-tag size="small" :type="statusType">{{ statusText }}</el-tag>
        <span class="cred-fingerprint">****{{ credential.fingerprint }}</span>
        <span class="cred-time">{{ fmt(credential.activatedAt) }} 生效</span>
        <span v-if="isRotating" class="cred-time">· 并存至 {{ fmt(credential.rotatingUntil) }}</span>
      </template>
      <el-tag v-else size="small" type="warning">未配置</el-tag>
    </div>

    <!-- 输入区：仅「该适配器需要凭证」时出现（是否显示由父组件判定，见 §4.4 规则 1） -->
    <template v-if="editable">
      <div v-for="f in fields" :key="f.key" class="cred-row">
        <span class="cred-label">{{ f.label }}<em v-if="f.required"> *</em></span>
        <el-input v-model="draft[f.key]" type="password" show-password
                  placeholder="粘贴供应商提供的明文密钥（保存后仅显示尾 4 位指纹）" />
      </div>
      <div v-if="fields.length > 1" class="cred-hint">
        复合凭证：字段名取自适配器实现元数据，保存时 JSON 化后整体加密（如 <code>{"secretId":"..","secretKey":".."}</code>）。
      </div>
      <div class="cred-row">
        <span class="cred-label">写入方式</span>
        <el-radio-group v-model="localMode" size="small">
          <el-radio value="update">更新（旧值并存 24h）</el-radio>
          <el-radio value="reset">重置（旧值立即失效）</el-radio>
        </el-radio-group>
      </div>
      <div v-if="warning" class="cred-alert">{{ warning }}</div>
      <div class="cred-hint">
        ⓘ 留空 = 不改动；保存后仅显示尾 4 位指纹，明文永不回显；接口版本回滚不会回滚凭证。
      </div>
    </template>
    <div v-else class="cred-hint">
      当前适配器不需要凭证（无鉴权）；已有凭证可到「应用详情 → 凭证区」管理。
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

// 应用弹窗内的凭证卡片（方案 A，2026-09-11；详见《应用凭证配置改造方案》§4）。
// 纯展示组件：字段清单（含「未声明 secret 字段」的通用兜底）与提交载荷组装都在 Apps.vue，
// 本组件只负责 rendering + 状态行 + 双绑定（modelValue = { [fieldKey]: 明文 }，mode = update|reset）。
const props = defineProps({
  fields: { type: Array, required: true },       // [{ key, label, required }]
  credential: { type: Object, default: null },   // CredentialView（遮显）| null
  pending: { type: Boolean, default: false },    // 新建态：应用尚未落库
  editable: { type: Boolean, default: true },    // false = 适配器不需要凭证，仅展示状态
  warning: { type: String, default: '' },
  modelValue: { type: Object, required: true },
  mode: { type: String, default: 'update' }
})
const emit = defineEmits(['update:modelValue', 'update:mode'])

const draft = computed(() => props.modelValue)   // 直接改父对象属性（v-model 双向）
const localMode = computed({ get: () => props.mode, set: (v) => emit('update:mode', v) })

const isRotating = computed(() => props.credential?.status === 'ROTATING')
const statusText = computed(() => {
  const st = props.credential?.status
  if (st === 'ACTIVE') return 'ACTIVE'
  if (st === 'ROTATING') return props.credential?.expired ? 'ROTATING·已过期' : 'ROTATING'
  return st || '—'
})
const statusType = computed(() => ({ ACTIVE: 'success', ROTATING: 'warning' }[props.credential?.status] || 'info'))
const fmt = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')
</script>

<style scoped>
.cred-card {
  width: 100%;
  background: #fafbfc;
  border-left: 2px solid #409eff;
  border-radius: 6px;
  padding: 10px 12px;
}
.cred-card--readonly { border-left-color: #dcdfe6; }
.cred-status { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.cred-fingerprint { font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12px; color: #303133; }
.cred-time { font-size: 12px; color: #909399; }
.cred-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.cred-label { width: 106px; flex: none; font-size: 13px; color: #606266; }
.cred-label em { color: #f56c6c; font-style: normal; }
.cred-alert {
  background: #fdf6ec; border: 1px solid #f5dab1; border-radius: 4px;
  padding: 6px 10px; font-size: 12px; color: #b88230; line-height: 1.6; margin-bottom: 6px;
}
.cred-hint { font-size: 12px; color: #909399; line-height: 1.6; }
.cred-hint code {
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  background: rgba(64, 158, 255, 0.08); padding: 0 3px; border-radius: 3px;
}
</style>
