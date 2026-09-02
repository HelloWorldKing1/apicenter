<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <div>
          <el-select v-model="filterApp" clearable placeholder="按应用筛选" style="width: 200px" @change="load">
            <el-option v-for="a in apps" :key="a.appId" :label="`${a.name}（${a.appId}）`" :value="a.appId" />
          </el-select>
        </div>
        <el-button type="primary" @click="openCreate">＋ 新建接口</el-button>
      </div>

      <el-table :data="ifaces" v-loading="loading" @row-click="openDetail">
        <el-table-column prop="code" label="标识" width="130" />
        <el-table-column prop="name" label="名称" width="180" show-overflow-tooltip />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.ifType === 'OUTBOUND' ? 'primary' : 'success'">
              {{ row.ifType === 'OUTBOUND' ? '出站' : '入站' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="method" label="方法" width="70" />
        <el-table-column prop="path" label="平台侧路径" show-overflow-tooltip />
        <el-table-column label="协议" width="110">
          <template #default="{ row }">{{ row.protocolIn }}→{{ row.protocolOut }}</template>
        </el-table-column>
        <el-table-column prop="appName" label="应用" width="130" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'PUBLISHED' ? 'success' : row.status === 'OFFLINE' ? 'info' : 'warning'">
              {{ { DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '下线' }[row.status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link @click.stop="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click.stop="openEdit(row)">编辑</el-button>
            <el-button link type="success" v-if="['DRAFT','OFFLINE'].includes(row.status)"
                       @click="act(row, 'publish')">发布</el-button>
            <el-button link type="info" v-if="row.status === 'PUBLISHED'" @click="act(row, 'offline')">下线</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建 / 编辑大弹窗（原型接口弹窗平移，类型联动） -->
    <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? `编辑接口 · ${form.code}` : '新建接口'"
               width="900px" top="4vh" :close-on-click-modal="false">
      <el-form :model="form" label-width="110px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="接口标识" required><el-input v-model="form.code" :disabled="dialog.isEdit" /></el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="接口名称" required><el-input v-model="form.name" /></el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="接口类型" required>
              <el-radio-group v-model="form.ifType" @change="onTypeChange">
                <el-radio-button value="OUTBOUND">出站中转</el-radio-button>
                <el-radio-button value="INBOUND">入站回调</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="HTTP 方法" required>
              <el-select v-model="form.method" style="width: 100%">
                <el-option v-for="m in ['POST','GET','PUT','DELETE']" :key="m" :label="m" :value="m" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="平台侧路径" required><el-input v-model="form.path" placeholder="/api/xxx" /></el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item :label="form.ifType === 'OUTBOUND' ? '上游路径' : '回调地址'" required>
              <el-input v-model="targetField"
                        :placeholder="form.ifType === 'OUTBOUND' ? '拼 app.base_url，如 /shop/v1/creatorList' : '送达目标 URL（必填）'" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="入站协议">
              <el-select v-model="form.protocolIn" style="width: 100%" @change="onProtocolInChange">
                <el-option v-for="p in ['JSON','XML']" :key="p" :label="p" :value="p" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="出站协议">
              <div style="display: flex; align-items: center; gap: 8px">
                <el-select v-model="form.protocolOut" :disabled="form.protoSame" style="flex: 1">
                  <el-option v-for="p in ['JSON','XML']" :key="p" :label="p" :value="p" />
                </el-select>
                <el-switch v-model="form.protoSame" size="small" active-text="同入站" />
              </div>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="超时(ms) / 重试">
              <el-input-number v-model="form.timeoutMs" :min="100" style="width: 110px" />
              <el-input-number v-model="form.maxRetries" :min="0" style="width: 80px; margin-left: 8px" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="归属应用" required>
              <el-select v-model="form.appId" style="width: 100%" @change="onAppChange">
                <el-option v-for="a in apps" :key="a.appId" :label="`${a.name}（${a.appId}）`" :value="a.appId" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="归属分组" required>
              <el-select v-model="form.groupId" style="width: 100%">
                <el-option v-for="g in groupOptions" :key="g.id" :label="g.name" :value="g.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="描述"><el-input v-model="form.desc" /></el-form-item>

        <!-- 请求参数：入站 / 出站两侧 -->
        <el-divider content-position="left">请求参数</el-divider>
        <el-tabs v-model="paramTab">
          <el-tab-pane label="入站侧（来源→平台）" name="IN">
            <ParamTable v-model="inParams" />
          </el-tab-pane>
          <el-tab-pane :label="form.ifType === 'INBOUND' ? '出站侧（送达报文，必填）' : '出站侧（平台→目标）'" name="OUT">
            <ParamTable v-model="outParams" />
          </el-tab-pane>
        </el-tabs>

        <!-- Body -->
        <el-divider content-position="left">请求体 Body</el-divider>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="入站 Body">
              <el-select v-model="form.inBodyType" style="width: 160px">
                <el-option v-for="t in BODY_TYPES" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
            <el-input v-if="['json','xml'].includes(form.inBodyType)" v-model="form.inBodyRaw" type="textarea" :rows="6"
                      placeholder="Body 模板（JSON/XML）" />
            <template v-if="['form-data','x-www-form-urlencoded'].includes(form.inBodyType)">
              <el-table :data="form.inFormRows" size="small">
                <el-table-column label="键">
                  <template #default="{ row }"><el-input v-model="row.key" size="small" /></template>
                </el-table-column>
                <el-table-column label="值">
                  <template #default="{ row }"><el-input v-model="row.value" size="small" /></template>
                </el-table-column>
                <el-table-column width="60">
                  <template #default="{ $index }">
                    <el-button link type="danger" @click="form.inFormRows.splice($index, 1)">删</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-button size="small" style="margin-top: 8px" @click="form.inFormRows.push({ key: '', value: '' })">＋ 添加键值</el-button>
            </template>
          </el-col>
          <el-col :span="12">
            <el-form-item label="出站 Body">
              <el-select v-model="form.outBodyType" style="width: 160px">
                <el-option v-for="t in BODY_TYPES" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
            <el-input v-if="['json','xml'].includes(form.outBodyType)" v-model="form.outBodyRaw" type="textarea" :rows="6"
                      placeholder="Body 模板（JSON/XML）" />
            <template v-if="['form-data','x-www-form-urlencoded'].includes(form.outBodyType)">
              <el-table :data="form.outFormRows" size="small">
                <el-table-column label="键">
                  <template #default="{ row }"><el-input v-model="row.key" size="small" /></template>
                </el-table-column>
                <el-table-column label="值">
                  <template #default="{ row }"><el-input v-model="row.value" size="small" /></template>
                </el-table-column>
                <el-table-column width="60">
                  <template #default="{ $index }">
                    <el-button link type="danger" @click="form.outFormRows.splice($index, 1)">删</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-button size="small" style="margin-top: 8px" @click="form.outFormRows.push({ key: '', value: '' })">＋ 添加键值</el-button>
            </template>
          </el-col>
        </el-row>

        <!-- 字段映射（接口级，入站→出站；空 = 整体透传） -->
        <el-divider content-position="left">字段映射（空 = 整体透传）</el-divider>
        <el-table :data="form.mappings" size="small">
          <el-table-column label="入站字段 source">
            <template #default="{ row }">
              <el-select v-model="row.source" size="small" clearable filterable allow-create
                         placeholder="从入站参数选择（default 时可空）">
                <el-option v-for="p in inParamNames" :key="p" :label="p" :value="p" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="操作 op" width="140">
            <template #default="{ row }">
              <el-select v-model="row.op" size="small" @change="() => (row.param = '')">
                <el-option v-for="op in OPS" :key="op" :label="op" :value="op" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="出站字段 target">
            <template #default="{ row }">
              <el-select v-model="row.target" size="small" clearable filterable allow-create
                         placeholder="从出站参数选择">
                <el-option v-for="p in outParamNames" :key="p" :label="p" :value="p" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="操作参数 param" width="200">
            <template #default="{ row }">
              <el-input v-if="['enumMap','default','condition'].includes(row.op)" v-model="row.param" size="small"
                        :placeholder="row.op === 'enumMap' ? 'PENDING→0, DONE→1' : row.op === 'condition' ? '如 amount > 0' : '常量值'" />
              <el-select v-else-if="row.op === 'typeCast'" v-model="row.param" size="small">
                <el-option v-for="t in ['STRING','INT','DECIMAL','BOOL','DATE']" :key="t" :label="t" :value="t" />
              </el-select>
              <el-select v-else-if="row.op === 'aggregate'" v-model="row.param" size="small">
                <el-option v-for="a in ['SUM','MAX','MIN','CONCAT']" :key="a" :label="a" :value="a" />
              </el-select>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="空值策略" width="130">
            <template #default="{ row }">
              <el-select v-model="row.nullStrategy" size="small">
                <el-option v-for="n in ['KEEP','NULL','DEFAULT','ERROR']" :key="n" :label="n" :value="n" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column width="70">
            <template #default="{ $index }">
              <el-button link type="danger" @click="form.mappings.splice($index, 1)">删</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" style="margin-top: 8px" @click="addMapping">＋ 添加映射</el-button>

        <!-- 响应 / ack 字段 -->
        <el-divider content-position="left">{{ form.ifType === 'OUTBOUND' ? '出站响应字段' : 'ack 回执字段' }}</el-divider>
        <el-table :data="form.fieldDefs" size="small">
          <el-table-column label="字段名">
            <template #default="{ row }"><el-input v-model="row.name" size="small" /></template>
          </el-table-column>
          <el-table-column label="类型" width="160">
            <template #default="{ row }">
              <el-select v-model="row.type" size="small">
                <el-option v-for="t in ['string','number','boolean','object','array']" :key="t" :label="t" :value="t" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="说明">
            <template #default="{ row }"><el-input v-model="row.desc" size="small" /></template>
          </el-table-column>
          <el-table-column width="70">
            <template #default="{ $index }">
              <el-button link type="danger" @click="form.fieldDefs.splice($index, 1)">删</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" style="margin-top: 8px" @click="addFieldDef">＋ 添加字段</el-button>

        <!-- 适配器绑定（留空 = 继承应用默认） -->
        <el-divider content-position="left">适配器绑定（留空 = 继承应用默认）</el-divider>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="报文适配器">
              <el-select v-model="form.messageAdapterId" clearable style="width: 100%">
                <el-option v-for="a in messageAdapters" :key="a.id" :label="`${a.name}（${a.impl}）`" :value="a.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item :label="form.ifType === 'OUTBOUND' ? '供应商签名' : '回调验签'">
              <el-select v-model="form.authAdapterId" clearable style="width: 100%">
                <el-option v-for="a in authAdapters" :key="a.id" :label="`${a.name}（${a.impl}）`" :value="a.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 接口详情（原型详情页平移：基本信息 + 适配器链可视化） -->
    <el-drawer v-model="detail.visible" :title="`接口详情 · ${detail.row.code || ''}`" size="640px">
      <el-descriptions :column="2" border v-if="detail.row.id">
        <el-descriptions-item label="名称" :span="2">{{ detail.row.name }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          <el-tag size="small" :type="detail.row.ifType === 'OUTBOUND' ? 'primary' : 'success'">
            {{ detail.row.ifType === 'OUTBOUND' ? '出站中转' : '入站回调' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="detail.row.status === 'PUBLISHED' ? 'success' : 'info'">
            {{ { DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '下线' }[detail.row.status] }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="方法与路径" :span="2">{{ detail.row.method }} {{ detail.row.path }}</el-descriptions-item>
        <el-descriptions-item label="归属">{{ detail.row.appName }} / {{ detail.row.groupName }}</el-descriptions-item>
        <el-descriptions-item label="版本">v{{ detail.row.version }}</el-descriptions-item>
        <el-descriptions-item label="上游路径" :span="2" v-if="detail.row.ifType === 'OUTBOUND'">{{ detail.row.upstreamPath }}</el-descriptions-item>
        <el-descriptions-item label="回调地址" :span="2" v-else>{{ detail.row.callbackUrl }}</el-descriptions-item>
        <el-descriptions-item label="超时 / 重试">{{ detail.row.timeoutMs }}ms / {{ detail.row.maxRetries }} 次</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ (detail.row.createdAt || '').replace('T', ' ').slice(0, 19) }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ detail.row.desc || '—' }}</el-descriptions-item>
      </el-descriptions>

      <h4>适配器链（运行时，M0-01 六阶段）</h4>
      <el-timeline>
        <el-timeline-item v-for="s in chainSteps" :key="s.title" :timestamp="s.title" placement="top">
          <div class="chain-desc">{{ s.desc }}</div>
        </el-timeline-item>
      </el-timeline>

      <div class="detail-actions">
        <el-button type="primary" @click="openEdit(detail.row)">编辑</el-button>
        <el-button v-if="['DRAFT','OFFLINE'].includes(detail.row.status)" type="success"
                   @click="act(detail.row, 'publish')">发布</el-button>
        <el-button v-if="detail.row.status === 'PUBLISHED'" type="info"
                   @click="act(detail.row, 'offline')">下线</el-button>
        <el-button type="danger" @click="remove(detail.row)">删除</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/api/http'
import ParamTable from '@/components/ParamTable.vue'

const OPS = ['rename', 'typeCast', 'enumMap', 'default', 'condition', 'aggregate']
const BODY_TYPES = ['none', 'json', 'xml', 'form-data', 'x-www-form-urlencoded']

// ---------- 列表 ----------
const ifaces = ref([])
const apps = ref([])
const groups = ref([])
const adapters = ref([])
const filterApp = ref('')
const loading = ref(false)
const paramTab = ref('IN')

const authAdapters = computed(() => adapters.value.filter((a) => a.type === 'auth' && a.enabled))
const messageAdapters = computed(() => adapters.value.filter((a) => a.type === 'message' && a.enabled))

async function load() {
  loading.value = true
  try {
    ifaces.value = await http.get('/interfaces', { params: { appId: filterApp.value || undefined } })
    apps.value = await http.get('/apps')
    groups.value = await http.get('/groups')
    adapters.value = await http.get('/adapters')
  } finally {
    loading.value = false
  }
}
onMounted(load)

// ---------- 表单 ----------
const dialog = reactive({ visible: false, isEdit: false, editId: 0 })
const form = reactive(emptyForm())

function emptyForm() {
  return {
    code: '', name: '', ifType: 'OUTBOUND', method: 'POST', path: '',
    protocolIn: 'JSON', protocolOut: 'JSON', protoSame: true, appId: '', groupId: null,
    upstreamPath: '', callbackUrl: '', status: null, timeoutMs: 3000, maxRetries: 4, desc: '',
    version: 1,
    params: [], inBodyType: 'none', inBodyRaw: '', inFormRows: [],
    outBodyType: 'none', outBodyRaw: '', outFormRows: [],
    mappings: [], fieldDefs: [], messageAdapterId: null, authAdapterId: null
  }
}

const groupOptions = computed(() => groups.value.filter((g) => g.appId === form.appId))

// 目标字段双向代理（v-model 不能绑定三元表达式，按类型切换 upstreamPath / callbackUrl）
const targetField = computed({
  get: () => (form.ifType === 'OUTBOUND' ? form.upstreamPath : form.callbackUrl),
  set: (v) => {
    if (form.ifType === 'OUTBOUND') {
      form.upstreamPath = v
    } else {
      form.callbackUrl = v
    }
  }
})

// 入站 / 出站参数行的双向代理（模板内不能 v-model 到 filter() 表达式）
const inParams = computed({
  get: () => form.params.filter((p) => p.side === 'IN'),
  set: (v) => {
    form.params = [...form.params.filter((p) => p.side !== 'IN'), ...v.map((x) => ({ ...x, side: 'IN' }))]
  }
})
const outParams = computed({
  get: () => form.params.filter((p) => p.side === 'OUT'),
  set: (v) => {
    form.params = [...form.params.filter((p) => p.side !== 'OUT'), ...v.map((x) => ({ ...x, side: 'OUT' }))]
  }
})

// 字段映射 source/target 下拉选项（原型：从两侧已配置参数选择）
const inParamNames = computed(() => inParams.value.map((p) => p.name).filter(Boolean))
const outParamNames = computed(() => outParams.value.map((p) => p.name).filter(Boolean))

/** 协议联动（原型 protoSame）：「出站协议与入站协议一致」开关 */
function onProtocolInChange() {
  if (form.protoSame) {
    form.protocolOut = form.protocolIn
  }
}

function openCreate() {
  Object.assign(form, emptyForm())
  dialog.isEdit = false
  dialog.visible = true
}

async function openEdit(row) {
  const d = await http.get(`/interfaces/${row.id}`)
  const inBody = d.bodies?.find((b) => b.side === 'IN')
  const outBody = d.bodies?.find((b) => b.side === 'OUT')
  Object.assign(form, emptyForm(), {
    code: d.code, name: d.name, ifType: d.ifType, method: d.method, path: d.path,
    protocolIn: d.protocolIn, protocolOut: d.protocolOut, protoSame: d.protocolIn === d.protocolOut,
    appId: d.appId, groupId: d.groupId,
    upstreamPath: d.upstreamPath || '', callbackUrl: d.callbackUrl || '',
    timeoutMs: d.timeoutMs, maxRetries: d.maxRetries, desc: d.desc, version: d.version,
    params: d.params.map((p) => ({ side: p.side, name: p.name, type: p.type, required: p.required, sample: p.sample, sortOrder: p.sortOrder })),
    inBodyType: inBody?.bodyType || 'none', inBodyRaw: inBody?.raw || '',
    inFormRows: parseFormRows(inBody?.form),
    outBodyType: outBody?.bodyType || 'none', outBodyRaw: outBody?.raw || '',
    outFormRows: parseFormRows(outBody?.form),
    mappings: d.mappings.map((m) => ({ source: m.source, op: m.op, target: m.target, param: m.param, nullStrategy: m.nullStrategy, sortOrder: m.sortOrder })),
    fieldDefs: d.fieldDefs.map((f) => ({ kind: f.kind, name: f.name, type: f.type, desc: f.desc, sortOrder: f.sortOrder })),
    messageAdapterId: d.bindings?.find((b) => b.role === 'MESSAGE')?.adapterId || null,
    authAdapterId: (d.bindings?.find((b) => b.role === 'AUTH') || d.bindings?.find((b) => b.role === 'CALLBACK_AUTH'))?.adapterId || null
  })
  dialog.isEdit = true
  dialog.editId = d.id
  dialog.visible = true
}

/** form 键值对 JSON 字符串 ⇄ 行数组（后端 form 字段为 JSON 字符串） */
function parseFormRows(json) {
  try {
    const arr = JSON.parse(json || '[]')
    return Array.isArray(arr) ? arr.map(([key, value]) => ({ key, value })) : []
  } catch {
    return []
  }
}

/** 类型切换：清空互斥字段（设计 §3.1 类型互斥字段按类型清空） */
function onTypeChange() {
  form.upstreamPath = ''
  form.callbackUrl = ''
  form.fieldDefs = []
  form.authAdapterId = null
}
function onAppChange() {
  form.groupId = null
}

function addMapping() {
  form.mappings.push({ source: '', op: 'rename', target: '', param: '', nullStrategy: 'KEEP', sortOrder: form.mappings.length })
}
function addFieldDef() {
  form.fieldDefs.push({ kind: form.ifType === 'OUTBOUND' ? 'RESP' : 'ACK', name: '', type: 'string', desc: '', sortOrder: form.fieldDefs.length })
}

// ---------- 提交 ----------
async function save() {
  const bodies = [
    { side: 'IN', bodyType: form.inBodyType, raw: form.inBodyRaw, form: toFormJson(form.inFormRows) },
    { side: 'OUT', bodyType: form.outBodyType, raw: form.outBodyRaw, form: toFormJson(form.outFormRows) }
  ]
  const bindings = [
    { role: 'MESSAGE', adapterId: form.messageAdapterId, version: null },
    form.ifType === 'OUTBOUND'
      ? { role: 'AUTH', adapterId: form.authAdapterId, version: null }
      : { role: 'CALLBACK_AUTH', adapterId: form.authAdapterId, version: null }
  ]
  const payload = {
    code: form.code, name: form.name, ifType: form.ifType, method: form.method, path: form.path,
    protocolIn: form.protocolIn, protocolOut: form.protocolOut, appId: form.appId, groupId: form.groupId,
    upstreamPath: form.ifType === 'OUTBOUND' ? form.upstreamPath : null,
    callbackUrl: form.ifType === 'INBOUND' ? form.callbackUrl : null,
    status: null, timeoutMs: form.timeoutMs, maxRetries: form.maxRetries, desc: form.desc,
    version: form.version, params: form.params, bodies, mappings: form.mappings,
    fieldDefs: form.fieldDefs, bindings
  }
  if (dialog.isEdit) {
    await http.put(`/interfaces/${dialog.editId}`, payload)
  } else {
    await http.post('/interfaces', payload)
  }
  ElMessage.success('保存成功')
  dialog.visible = false
  load()
}

function toFormJson(rows) {
  return JSON.stringify((rows || []).filter((r) => r.key).map((r) => [r.key, r.value || '']))
}

// ---------- 接口详情（原型详情页 + 适配器链可视化） ----------
const detail = reactive({ visible: false, row: {} })

async function openDetail(row) {
  detail.row = await http.get(`/interfaces/${row.id}`)
  detail.visible = true
}

const chainSteps = computed(() => {
  const d = detail.row
  if (!d.id) return []
  const messageBinding = d.bindings?.find((b) => b.role === 'MESSAGE')
  const authBinding = d.bindings?.find((b) => b.role === 'AUTH' || b.role === 'CALLBACK_AUTH')
  return [
    {
      title: '① 入站鉴权',
      desc: d.ifType === 'OUTBOUND'
        ? '调用方鉴权（平台统一，范围外）'
        : `回调验签：${authBinding?.adapterId || '继承应用默认'}`
    },
    { title: '② 协议解码', desc: `入站协议 ${d.protocolIn}` },
    { title: '③ 报文适配', desc: `报文适配器：${messageBinding?.adapterId || '继承应用默认'}` },
    {
      title: '④ 字段映射',
      desc: (d.mappings?.length || 0) === 0
        ? '无映射规则（整体透传）'
        : `${d.mappings.length} 条映射规则（白名单输出）`
    },
    { title: '⑤ 协议编码', desc: `出站协议 ${d.protocolOut}` },
    {
      title: '⑥ 出站鉴权',
      desc: d.ifType === 'OUTBOUND'
        ? `供应商签名：${authBinding?.adapterId || '继承应用默认'}`
        : '向回调地址签名（默认无）'
    }
  ]
})

async function act(row, action) {
  await http.post(`/interfaces/${row.id}/${action}`)
  ElMessage.success('操作成功')
  if (detail.visible) {
    detail.row = await http.get(`/interfaces/${row.id}`)
  }
  load()
}
async function remove(row) {
  await ElMessageBox.confirm(`删除接口「${row.name}」？其 6 张配置子表将级联删除`, '确认', { type: 'warning' })
  await http.delete(`/interfaces/${row.id}`)
  ElMessage.success('已删除')
  load()
}
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; margin-bottom: 14px; }
.muted { color: #c0c4cc; font-size: 12px; }
h4 { margin: 20px 0 10px; color: #303133; }
.chain-desc { color: #606266; font-size: 13px; }
.detail-actions { margin-top: 20px; display: flex; gap: 8px; }
</style>
