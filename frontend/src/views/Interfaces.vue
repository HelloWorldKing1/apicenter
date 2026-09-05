<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <div class="toolbar-filters">
          <el-select v-model="filterApp" clearable placeholder="应用" style="width: 160px" @change="onFilterAppChange">
            <el-option v-for="a in apps" :key="a.appId" :label="`${a.name}（${a.appId}）`" :value="a.appId" />
          </el-select>
          <el-select v-model="filterGroup" clearable placeholder="分组" style="width: 140px" @change="load">
            <el-option v-for="g in filterGroups" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
          <el-select v-model="filterType" clearable placeholder="类型" style="width: 120px" @change="load">
            <el-option label="出站中转" value="OUTBOUND" />
            <el-option label="入站回调" value="INBOUND" />
          </el-select>
          <el-select v-model="filterStatus" clearable placeholder="状态" style="width: 120px" @change="load">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已发布" value="PUBLISHED" />
            <el-option label="下线" value="OFFLINE" />
          </el-select>
          <el-input v-model="filterKeyword" placeholder="名称 / 标识 / 路径" clearable style="width: 200px" @input="onKeywordInput" />
        </div>
        <el-button type="primary" @click="openCreate">＋ 新建接口</el-button>
      </div>

      <el-table :data="ifaces" v-loading="loading" @row-click="openDetail">
        <el-table-column prop="code" label="标识" width="180" />
        <el-table-column prop="name" label="名称" width="180" show-overflow-tooltip />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.ifType === 'OUTBOUND' ? 'primary' : 'success'">
              {{ row.ifType === 'OUTBOUND' ? '出站' : '入站' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="方法" width="90">
          <template #default="{ row }">
            <span class="method-text" :class="`method-${row.method}`">{{ row.method }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="平台侧路径" show-overflow-tooltip />
        <el-table-column label="协议" width="140">
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
                       @click.stop="act(row, 'publish')">发布</el-button>
            <el-button link type="info" v-if="row.status === 'PUBLISHED'" @click.stop="act(row, 'offline')">下线</el-button>
            <el-button link type="danger" @click.stop="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ============ 新建 / 编辑弹窗（Postman 风格，参考原型 §接口弹窗） ============ -->
    <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? `编辑接口 · ${form.code}` : '新建接口'"
               width="960px" top="3vh" :close-on-click-modal="false" class="iface-dialog">
      <!-- 请求地址栏（签名元素：方法 + 平台路径 → 上游目标） -->
      <div class="request-bar">
        <el-select v-model="form.method" class="method-select" :class="`method-${form.method}`" size="large">
          <el-option v-for="m in ['POST','GET','PUT','DELETE']" :key="m" :label="m" :value="m" />
        </el-select>
        <el-input v-model="form.path" class="path-input" placeholder="平台侧路径，如 /api/orders" />
        <span class="arrow">→</span>
        <el-input v-model="targetField" class="target-input"
                  :placeholder="form.ifType === 'OUTBOUND' ? '上游路径（拼应用服务地址），如 /shop/v1/creatorList' : '回调地址（送达目标 URL，必填）'" />
      </div>

      <!-- 基础信息（紧凑一行） -->
      <div class="basic-grid">
        <div class="basic-item">
          <span class="basic-label">接口名称</span>
          <el-input v-model="form.name" placeholder="如：FastMoss 达人列表" />
        </div>
        <div class="basic-item">
          <span class="basic-label">标识</span>
          <el-input v-model="form.code" :disabled="dialog.isEdit" placeholder="IF-FM-001" />
        </div>
        <div class="basic-item">
          <span class="basic-label">类型</span>
          <el-radio-group v-model="form.ifType" @change="onTypeChange">
            <el-radio-button value="OUTBOUND">出站中转</el-radio-button>
            <el-radio-button value="INBOUND">入站回调</el-radio-button>
          </el-radio-group>
        </div>
        <div class="basic-item">
          <span class="basic-label">应用</span>
          <el-select v-model="form.appId" placeholder="选择应用" @change="onAppChange" style="width: 100%">
            <el-option v-for="a in apps" :key="a.appId" :label="`${a.name}（${a.appId}）`" :value="a.appId" />
          </el-select>
        </div>
        <div class="basic-item">
          <span class="basic-label">分组</span>
          <el-select v-model="form.groupId" placeholder="先选应用" style="width: 100%" ref="groupSelectRef"
                     @visible-change="onGroupPopVisible">
            <el-option v-for="g in groupOptions" :key="g.id" :label="g.name" :value="g.id" />
            <!-- 分组内联创建（设计稿：doc/设计稿-分组下拉内联创建.html）：底部动作行原位变形为迷你表单 -->
            <template #footer>
              <button v-if="!groupCreating" type="button" class="group-new-action" @click="openGroupCreate">
                <span class="plus">＋</span> 新建分组
              </button>
              <div v-else class="group-new-form" :class="{ 'err-input': newGroupError }"
                   @keydown.esc.prevent.stop="cancelGroupCreate">
                <div class="group-new-context">创建到 <b>{{ selectedAppLabel }}</b> 之下</div>
                <div class="group-new-row">
                  <el-input ref="groupCreateInput" v-model="newGroupName" size="small" maxlength="64"
                            placeholder="输入分组名称，回车创建" @keydown.enter.prevent="createGroupInline" />
                  <el-button size="small" type="primary" :loading="creatingGroup" @click="createGroupInline">创建</el-button>
                  <el-button size="small" text @click="cancelGroupCreate">取消</el-button>
                </div>
                <div v-if="newGroupError" class="group-new-err">{{ newGroupError }}</div>
              </div>
            </template>
          </el-select>
        </div>
        <div class="basic-item">
          <span class="basic-label">描述</span>
          <el-input v-model="form.desc" placeholder="可选" />
        </div>
      </div>

      <!-- 主分区（Postman 式 tab） -->
      <el-tabs v-model="mainTab" class="main-tabs">
        <!-- ===== Tab 1 请求参数（入站 / 出站，每侧 Params / Body 双子 tab，照原型） ===== -->
        <el-tab-pane label="请求参数" name="params">
          <div v-for="side in ['IN', 'OUT']" :key="side" class="side-block">
            <div class="side-head">
              <span class="side-name">{{ side === 'IN' ? '入站侧' : '出站侧' }}</span>
              <span class="side-desc">{{ side === 'IN' ? '来源 → 平台' : (form.ifType === 'INBOUND' ? '送达报文（必填）· 平台 → 调用方' : '平台 → 目标') }}</span>
              <div class="subtabs">
                <button type="button" class="subtab" :class="{ active: reqTab[side] === 'params' }"
                        @click="reqTab[side] = 'params'">Params</button>
                <button type="button" class="subtab" :class="{ active: reqTab[side] === 'body' }"
                        @click="reqTab[side] = 'body'">Body</button>
              </div>
            </div>

            <!-- 透传模式开关（仅出站接口的出站侧；透传 = 后端不做字段映射，出站报文原样转发） -->
            <div v-if="side === 'OUT' && form.ifType === 'OUTBOUND'" class="passthrough-bar">
              <span class="side-desc">转发方式</span>
              <el-radio-group v-model="form.passthrough" size="small">
                <el-radio-button :value="true">透传（出站 = 入站原样）</el-radio-button>
                <el-radio-button :value="false">自定义映射</el-radio-button>
              </el-radio-group>
            </div>

            <!-- 透传模式：出站侧编辑区整体隐藏（仅出站接口生效，入站回调的送达报文不受影响） -->
            <div v-if="side === 'OUT' && form.ifType === 'OUTBOUND' && form.passthrough" class="empty-hint">
              透传模式：出站报文 = 入站报文原样转发，无需配置出站侧参数；字段映射不生效
            </div>
            <template v-else>
            <!-- Params 子面板 -->
            <div v-if="reqTab[side] === 'params'">
              <ParamTable v-if="side === 'IN'" v-model="form.inParams" />
              <ParamTable v-else v-model="form.outParams" />
            </div>

            <!-- Body 子面板 -->
            <div v-else>
              <div class="body-types">
                <button v-for="t in BODY_TYPES" :key="t" type="button" class="subtab"
                        :class="{ active: sideBody(side).type === t }" @click="sideBody(side).type = t">{{ t }}</button>
              </div>
              <div v-if="sideBody(side).type === 'none'" class="empty-hint">无请求体</div>
              <textarea v-else-if="['json','xml'].includes(sideBody(side).type)" v-model="sideBody(side).raw"
                        class="raw-editor" :placeholder="RAW_PLACEHOLDER[sideBody(side).type]"></textarea>
              <template v-else>
                <el-table :data="sideBody(side).formRows" size="small" class="kv-table">
                  <el-table-column label="键" min-width="40%">
                    <template #default="{ row }"><el-input v-model="row.key" size="small" placeholder="键" /></template>
                  </el-table-column>
                  <el-table-column label="值">
                    <template #default="{ row }"><el-input v-model="row.value" size="small" placeholder="值" /></template>
                  </el-table-column>
                  <el-table-column width="50">
                    <template #default="{ $index }">
                      <el-button link type="danger" @click="sideBody(side).formRows.splice($index, 1)">×</el-button>
                    </template>
                  </el-table-column>
                </el-table>
                <el-button size="small" class="add-btn" @click="sideBody(side).formRows.push({ key: '', value: '' })">＋ 添加</el-button>
              </template>
            </div>
            </template>
          </div>
        </el-tab-pane>

        <!-- ===== Tab 2 字段映射（入站 → 出站，下拉 + 中文操作，照原型） ===== -->
        <el-tab-pane label="字段映射" name="mappings">
          <div v-if="form.ifType === 'OUTBOUND' && form.passthrough" class="empty-hint">
            透传模式下后端不进行字段映射；如需映射，请在「请求参数 → 出站侧」切换为自定义映射
          </div>
          <template v-else>
          <div class="side-desc" style="margin-bottom: 8px">规则为空 = 整体透传；非空 = 仅输出 target 命中字段（白名单）</div>
          <el-table :data="form.mappings" size="small">
            <el-table-column label="入站字段">
              <template #default="{ row }">
                <el-select v-model="row.source" size="small" clearable filterable allow-create placeholder="选入站字段（default 时可空）">
                  <el-option v-for="p in inParamNames" :key="p" :label="p" :value="p" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130">
              <template #default="{ row }">
                <el-select v-model="row.op" size="small" @change="() => (row.param = '')">
                  <el-option v-for="(label, op) in MAP_OPS" :key="op" :label="label" :value="op" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="出站字段">
              <template #default="{ row }">
                <el-select v-model="row.target" size="small" clearable filterable allow-create placeholder="选出站字段">
                  <el-option v-for="p in outParamNames" :key="p" :label="p" :value="p" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作参数" width="190">
              <template #default="{ row }">
                <el-input v-if="['enumMap','default','condition'].includes(row.op)" v-model="row.param" size="small"
                          :placeholder="row.op === 'enumMap' ? 'PENDING→0, DONE→1' : row.op === 'condition' ? '如 amount > 0' : '默认值'" />
                <el-select v-else-if="row.op === 'typeCast'" v-model="row.param" size="small" placeholder="目标类型">
                  <el-option v-for="t in ['STRING','INT','DECIMAL','BOOL','DATE']" :key="t" :label="t" :value="t" />
                </el-select>
                <el-select v-else-if="row.op === 'aggregate'" v-model="row.param" size="small" placeholder="聚合方式">
                  <el-option v-for="a in ['CONCAT','SUM','MAX','MIN']" :key="a" :label="a" :value="a" />
                </el-select>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="空值策略" width="120">
              <template #default="{ row }">
                <el-select v-model="row.nullStrategy" size="small">
                  <el-option v-for="(label, k) in MAP_NULL" :key="k" :label="label" :value="k" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column width="50">
              <template #default="{ $index }">
                <el-button link type="danger" @click="form.mappings.splice($index, 1)">×</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="form.mappings.length === 0" class="empty-hint">暂无映射，点下方「＋ 添加映射」</div>
          <el-button size="small" class="add-btn" @click="addMapping">＋ 添加映射</el-button>
          </template>
        </el-tab-pane>

        <!-- ===== Tab 3 响应 · ack（按类型互斥展示） ===== -->
        <el-tab-pane :label="form.ifType === 'OUTBOUND' ? '出站响应字段' : 'ack 回执字段'" name="resp">
          <div class="side-desc" style="margin-bottom: 8px">
            {{ form.ifType === 'OUTBOUND' ? '出站方返回给平台的字段（出站 = 供应商返回）' : '平台回供应商的 ack 回执结构（固定 code/message，与送达解耦）' }}
          </div>
          <el-table :data="form.fieldDefs" size="small">
            <el-table-column label="字段名" min-width="30%">
              <template #default="{ row }"><el-input v-model="row.name" size="small" /></template>
            </el-table-column>
            <el-table-column label="类型" width="140">
              <template #default="{ row }">
                <el-select v-model="row.type" size="small">
                  <el-option v-for="t in ['string','number','boolean','object','array']" :key="t" :label="t" :value="t" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="说明">
              <template #default="{ row }"><el-input v-model="row.desc" size="small" placeholder="可选" /></template>
            </el-table-column>
            <el-table-column width="50">
              <template #default="{ $index }">
                <el-button link type="danger" @click="form.fieldDefs.splice($index, 1)">×</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="form.fieldDefs.length === 0" class="empty-hint">暂无字段</div>
          <el-button size="small" class="add-btn" @click="addFieldDef">＋ 添加字段</el-button>
        </el-tab-pane>

        <!-- ===== Tab 4 高级（协议 / 超时 / 适配器绑定，原型折叠区平移） ===== -->
        <el-tab-pane label="高级" name="adv">
          <div class="adv-grid">
            <div class="adv-item">
              <span class="basic-label">入站协议</span>
              <el-select v-model="form.protocolIn" style="width: 100%" @change="onProtocolInChange">
                <el-option v-for="p in ['JSON','XML']" :key="p" :label="p" :value="p" />
              </el-select>
            </div>
            <div class="adv-item">
              <span class="basic-label">出站协议</span>
              <div style="display: flex; align-items: center; gap: 8px">
                <el-select v-model="form.protocolOut" :disabled="form.protoSame" style="flex: 1">
                  <el-option v-for="p in ['JSON','XML']" :key="p" :label="p" :value="p" />
                </el-select>
                <el-switch v-model="form.protoSame" size="small" active-text="同入站" />
              </div>
            </div>
            <div class="adv-item">
              <span class="basic-label">读超时(ms)</span>
              <el-input-number v-model="form.timeoutMs" :min="100" style="width: 100%" />
            </div>
            <div class="adv-item">
              <span class="basic-label">最大重试</span>
              <el-input-number v-model="form.maxRetries" :min="0" style="width: 100%" />
            </div>
            <div class="adv-item">
              <span class="basic-label">报文适配器</span>
              <el-select v-model="form.messageAdapterId" clearable placeholder="继承应用默认" style="width: 100%">
                <el-option v-for="a in messageAdapters" :key="a.id" :label="`${a.name}（${a.impl}）`" :value="a.id" />
              </el-select>
            </div>
            <div class="adv-item">
              <span class="basic-label">{{ form.ifType === 'OUTBOUND' ? '供应商签名' : '回调验签' }}</span>
              <el-select v-model="form.authAdapterId" clearable placeholder="继承应用默认" style="width: 100%">
                <el-option v-for="a in authAdapters" :key="a.id" :label="`${a.name}（${a.impl}）`" :value="a.id" />
              </el-select>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>

      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 测试接口弹窗（管理面调试：真实走一遍出站链路，仅 OUTBOUND） ============ -->
    <el-dialog v-model="test.visible" :title="`测试接口 · ${detail.row.code || ''}`" width="760px" top="5vh">
      <div class="test-layout">
        <div class="test-side">
          <div class="side-desc" style="margin-bottom: 8px">请求体（预填接口入站 Body 模板，可编辑）</div>
          <textarea v-model="test.body" class="raw-editor test-body"></textarea>
          <el-button type="primary" :loading="test.sending" style="margin-top: 10px" @click="sendTest">
            发送请求
          </el-button>
        </div>
        <div class="test-side">
          <div class="side-desc" style="margin-bottom: 8px">响应（统一信封 { code, msg, data }）</div>
          <pre class="test-resp" :class="{ 'resp-error': test.isError }">{{ test.resp }}</pre>
        </div>
      </div>
    </el-dialog>

    <!-- ============ 模拟回调弹窗（M3 手动验收：INBOUND 调试，真实网关路径 + HMAC 自签名） ============ -->
    <el-dialog v-model="cbTest.visible" :title="`模拟回调 · ${detail.row.code || ''}`" width="760px" top="5vh">
      <div class="test-layout">
        <div class="test-side">
          <div class="side-desc" style="margin-bottom: 8px">
            回调报文（预填入站 Body 模板，可编辑；平台按 HMAC 回调验签约定自动签名后自调网关）
          </div>
          <textarea v-model="cbTest.body" class="raw-editor test-body"></textarea>
          <el-button type="primary" :loading="cbTest.sending" style="margin-top: 10px" @click="sendCallbackTest">
            发送回调
          </el-button>
        </div>
        <div class="test-side">
          <div class="side-desc" style="margin-bottom: 8px">供应商视角 ack + 送达状态</div>
          <pre class="test-resp" :class="{ 'resp-error': cbTest.isError }">{{ cbTest.resp }}</pre>
        </div>
      </div>
    </el-dialog>

    <!-- ============ 接口详情（原型详情页平移：基本信息 + 适配器链可视化） ============ -->
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
        <el-descriptions-item label="方法与路径" :span="2">
          <div class="path-row">
            <span class="mono">{{ detail.row.method }} {{ detail.row.path }}</span>
            <el-button link size="small" type="primary" @click="copyPath">复制</el-button>
          </div>
        </el-descriptions-item>
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
        <!-- M3：测试接口仅 OUTBOUND；INBOUND 用「模拟回调」（否则入站接口会错误地走出站链路） -->
        <el-button v-if="detail.row.ifType === 'OUTBOUND'" type="warning"
                   @click="openTest(detail.row)">测试接口</el-button>
        <el-button v-else type="warning" @click="openCallbackTest(detail.row)">模拟回调</el-button>
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
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import http from '@/api/http'
import ParamTable from '@/components/ParamTable.vue'

const route = useRoute()

const BODY_TYPES = ['none', 'form-data', 'x-www-form-urlencoded', 'json', 'xml']
/** raw 编辑器占位示例（属性内嵌多行字符串含双引号会破坏模板解析，提为常量） */
const RAW_PLACEHOLDER = {
  json: '{\n  "key": "value"\n}',
  xml: '<root></root>'
}
/** 映射操作与空值策略的中文标签（照原型 MAP_OPS / MAP_NULL） */
const MAP_OPS = { rename: '重命名', typeCast: '类型转换', enumMap: '枚举映射', default: '默认值', condition: '条件', aggregate: '聚合' }
const MAP_NULL = { KEEP: '保留原值', NULL: '置空', DEFAULT: '默认值', ERROR: '报错' }

// ---------- 列表 ----------
const ifaces = ref([])
const apps = ref([])
const groups = ref([])
const adapters = ref([])
const filterApp = ref('')
const filterGroup = ref(null)
const filterType = ref('')
const filterStatus = ref('')
const filterKeyword = ref('')
const loading = ref(false)

const authAdapters = computed(() => adapters.value.filter((a) => a.type === 'auth' && a.enabled))
const messageAdapters = computed(() => adapters.value.filter((a) => a.type === 'message' && a.enabled))
const filterGroups = computed(() => groups.value.filter((g) => g.appId === filterApp.value))

// 筛选只查接口列表；字典（应用/分组/适配器）仅在首次加载拉取（评审中危 #10 拆请求）
async function load() {
  loading.value = true
  try {
    ifaces.value = await http.get('/interfaces', { params: {
      appId: filterApp.value || undefined,
      groupId: filterGroup.value || undefined,
      ifType: filterType.value || undefined,
      status: filterStatus.value || undefined,
      keyword: filterKeyword.value || undefined
    }})
  } finally {
    loading.value = false
  }
}

async function loadDicts() {
  apps.value = await http.get('/apps')
  groups.value = await http.get('/groups')
  adapters.value = await http.get('/adapters')
}

// 搜索防抖（评审中危 #10）
let keywordTimer
function onKeywordInput() {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(load, 300)
}

onMounted(async () => {
  await Promise.all([load(), loadDicts()])
  // 从分组管理「分组详情 → 点击接口」跳转而来，自动打开接口详情
  const id = Number(route.query.id)
  if (id) {
    openDetail({ id })
  }
})

/** 应用变化 → 清空分组并重查 */
function onFilterAppChange() {
  filterGroup.value = null
  load()
}

// ---------- 表单 ----------
const dialog = reactive({ visible: false, isEdit: false, editId: 0 })
const mainTab = ref('params')
const reqTab = reactive({ IN: 'params', OUT: 'params' })
const form = reactive(emptyForm())

function emptyForm() {
  return {
    code: '', name: '', ifType: 'OUTBOUND', method: 'POST', path: '',
    protocolIn: 'JSON', protocolOut: 'JSON', protoSame: true, appId: '', groupId: null,
    upstreamPath: '', callbackUrl: '', status: null, timeoutMs: 3000, maxRetries: 4, desc: '',
    version: 1,
    // 透传模式（仅出站接口）：出站报文 = 入站原样转发，后端不做字段映射（提交空映射规则）
    passthrough: true,
    // 入站/出站参数拆为两个真实数组（ParamTable 原地编辑需要引用直连；保存时组装 side）
    inParams: [], outParams: [],
    inBodyType: 'none', inBodyRaw: '', inFormRows: [],
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

// 字段映射 source/target 下拉选项（原型：从两侧已配置参数选择）
const inParamNames = computed(() => form.inParams.map((p) => p.name).filter(Boolean))
const outParamNames = computed(() => form.outParams.map((p) => p.name).filter(Boolean))

/**
 * 每侧 Body 状态（类型 / raw / form 键值行）。
 * 注意：必须用 getter/setter 代理写回 form——普通对象字面量会导致
 * 模板内点击赋值只落在临时对象上（类型切换、raw 输入均失效）。
 */
function sideBody(side) {
  const key = side === 'IN' ? 'in' : 'out'
  return {
    get type() {
      return form[key + 'BodyType']
    },
    set type(v) {
      form[key + 'BodyType'] = v
    },
    get raw() {
      return form[key + 'BodyRaw']
    },
    set raw(v) {
      form[key + 'BodyRaw'] = v
    },
    formRows: form[key + 'FormRows'] // 数组引用，行级增删直接生效
  }
}

/** 协议联动（原型 protoSame） */
function onProtocolInChange() {
  if (form.protoSame) {
    form.protocolOut = form.protocolIn
  }
}

function openCreate() {
  Object.assign(form, emptyForm())
  reqTab.IN = 'params'
  reqTab.OUT = 'params'
  mainTab.value = 'params'
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
    passthrough: (d.mappings?.length || 0) === 0, // 回显推断：无映射规则 = 透传模式
    inParams: d.params.filter((p) => p.side === 'IN').map(toParamRow),
    outParams: d.params.filter((p) => p.side === 'OUT').map(toParamRow),
    inBodyType: inBody?.bodyType || 'none', inBodyRaw: inBody?.raw || '',
    inFormRows: parseFormRows(inBody?.form),
    outBodyType: outBody?.bodyType || 'none', outBodyRaw: outBody?.raw || '',
    outFormRows: parseFormRows(outBody?.form),
    mappings: d.mappings.map((m) => ({ source: m.source, op: m.op, target: m.target, param: m.param, nullStrategy: m.nullStrategy, sortOrder: m.sortOrder })),
    fieldDefs: d.fieldDefs.map((f) => ({ kind: f.kind, name: f.name, type: f.type, desc: f.desc, sortOrder: f.sortOrder })),
    messageAdapterId: d.bindings?.find((b) => b.role === 'MESSAGE')?.adapterId || null,
    authAdapterId: (d.bindings?.find((b) => b.role === 'AUTH') || d.bindings?.find((b) => b.role === 'CALLBACK_AUTH'))?.adapterId || null
  })
  reqTab.IN = 'params'
  reqTab.OUT = 'params'
  mainTab.value = 'params'
  dialog.isEdit = true
  dialog.editId = d.id
  dialog.visible = true
}

/** 后端参数行 → 表单行（去 side） */
function toParamRow(p) {
  return { name: p.name, type: p.type, required: p.required, sample: p.sample, sortOrder: p.sortOrder }
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
function toFormJson(rows) {
  return JSON.stringify((rows || []).filter((r) => r.key).map((r) => [r.key, r.value || '']))
}

/**
 * 类型切换：清空互斥字段（设计 §3.1 类型互斥字段按类型清空）。
 * 出站侧语义随类型变化（OUTBOUND=目标请求 / INBOUND=送达报文），
 * 连同映射与出站侧参数一并清空，避免旧语义残留错提交（评审中危 #9）。
 */
function onTypeChange() {
  form.upstreamPath = ''
  form.callbackUrl = ''
  form.fieldDefs = []
  form.authAdapterId = null
  form.mappings = []
  form.outParams = []
  form.outFormRows = []
  form.outBodyType = 'none'
  form.outBodyRaw = ''
}
function onAppChange() {
  form.groupId = null
  cancelGroupCreate()
}

// ---------- 分组内联创建（设计稿：doc/设计稿-分组下拉内联创建.html） ----------
const groupSelectRef = ref()
const groupCreateInput = ref()
const groupCreating = ref(false)    // 输入态开关：footer 动作行 ⇄ 迷你表单
const creatingGroup = ref(false)    // 请求中（防重复提交）
const newGroupName = ref('')
const newGroupError = ref('')

/** 上下文行：明示新分组挂在当前已选应用之下 */
const selectedAppLabel = computed(() => {
  const a = apps.value.find((x) => x.appId === form.appId)
  return a ? `${a.name}（${a.appId}）` : ''
})

function openGroupCreate() {
  newGroupName.value = ''
  newGroupError.value = ''
  groupCreating.value = true
  nextTick(() => groupCreateInput.value?.focus())
}

function cancelGroupCreate() {
  groupCreating.value = false
  creatingGroup.value = false
  newGroupName.value = ''
  newGroupError.value = ''
}

/** 下拉收起时静默放弃输入，回到列表态（与「点外部 = 取消」一致） */
function onGroupPopVisible(visible) {
  if (!visible) cancelGroupCreate()
}

async function createGroupInline() {
  if (creatingGroup.value) return
  const name = newGroupName.value.trim()
  if (!name) {
    newGroupError.value = '请输入分组名称'
    return
  }
  // 应用内重名本地预检（即时反馈）；后端唯一约束兜底
  if (groupOptions.value.some((g) => g.name === name)) {
    newGroupError.value = '该应用下已存在同名分组'
    return
  }
  newGroupError.value = ''
  creatingGroup.value = true
  try {
    // 复用分组页同一接口（POST /api/admin/groups，sortOrder 默认 0）；无返回 id，按名称回查
    await http.post('/groups', { appId: form.appId, name, sortOrder: 0 })
    groups.value = await http.get('/groups')
    form.groupId = groups.value.find((g) => g.appId === form.appId && g.name === name)?.id
    ElMessage.success(`已创建分组「${name}」`)
    groupSelectRef.value?.blur() // 收起下拉（新分组已选中）；若未收起，状态也已被重置
    cancelGroupCreate()
  } catch (e) {
    // 失败保留下拉与输入便于重试；错误提示由 http 拦截器统一弹出
  } finally {
    creatingGroup.value = false
  }
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
  // 透传模式（仅出站接口）：提交空映射规则、清空出站侧参数（后端零映射直通）
  const passthrough = form.ifType === 'OUTBOUND' && form.passthrough
  const params = [
    ...form.inParams.map((p, i) => ({ ...p, side: 'IN', sortOrder: p.sortOrder ?? i })),
    ...(passthrough ? [] : form.outParams.map((p, i) => ({ ...p, side: 'OUT', sortOrder: p.sortOrder ?? i })))
  ]
  const payload = {
    code: form.code, name: form.name, ifType: form.ifType, method: form.method, path: form.path,
    protocolIn: form.protocolIn, protocolOut: form.protocolOut, appId: form.appId, groupId: form.groupId,
    upstreamPath: form.ifType === 'OUTBOUND' ? form.upstreamPath : null,
    callbackUrl: form.ifType === 'INBOUND' ? form.callbackUrl : null,
    status: null, timeoutMs: form.timeoutMs, maxRetries: form.maxRetries, desc: form.desc,
    version: form.version, params, bodies, mappings: passthrough ? [] : form.mappings,
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

// ---------- 接口详情（原型详情页 + 适配器链可视化） ----------
const detail = reactive({ visible: false, row: {} })

async function openDetail(row) {
  detail.row = await http.get(`/interfaces/${row.id}`)
  detail.visible = true
}

// ---------- 一键复制完整 URL（host + 平台侧路径） ----------
// dev 联调时后端在 8080（vite dev server 是 5173）；生产 build 后前端由后端 serve，同源 origin 即后端地址
async function copyPath() {
  const host = import.meta.env.DEV ? 'http://localhost:8080' : window.location.origin
  const text = `${host}${detail.row.path}`
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制：' + text)
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

// ---------- 测试接口（管理面调试：POST /api/admin/interfaces/{id}/test，仅 OUTBOUND） ----------
const test = reactive({ visible: false, body: '{}', sending: false, resp: '', isError: false })

function openTest(row) {
  const inBody = row.bodies?.find((b) => b.side === 'IN')
  test.body = inBody && inBody.raw ? inBody.raw : '{}'
  test.resp = ''
  test.isError = false
  test.visible = true
}

async function sendTest() {
  test.sending = true
  test.resp = ''
  try {
    // 发 JSON 对象（axios 序列化为 application/json，后端 byte[] 原样收）——避免字符串被表单编码
    const obj = JSON.parse(test.body)
    const result = await http.post(`/interfaces/${detail.row.id}/test`, obj)
    test.isError = false
    test.resp = JSON.stringify({ code: 0, msg: 'ok', data: result }, null, 2)
  } catch (e) {
    test.isError = true
    test.resp = e.response?.data
      ? JSON.stringify(e.response.data, null, 2)
      : (e.message || '请求失败')
  } finally {
    test.sending = false
  }
}

// ---------- 模拟回调（M3：POST /api/admin/interfaces/{id}/test-callback，仅 INBOUND；接口需已发布） ----------
const cbTest = reactive({ visible: false, body: '{}', sending: false, resp: '', isError: false })

function openCallbackTest(row) {
  const inBody = row.bodies?.find((b) => b.side === 'IN')
  cbTest.body = inBody && inBody.raw ? inBody.raw : '{}'
  cbTest.resp = ''
  cbTest.isError = false
  cbTest.visible = true
}

async function sendCallbackTest() {
  cbTest.sending = true
  cbTest.resp = ''
  try {
    const obj = JSON.parse(cbTest.body)
    const result = await http.post(`/interfaces/${detail.row.id}/test-callback`, obj)
    cbTest.isError = false
    cbTest.resp = JSON.stringify(result, null, 2)
  } catch (e) {
    cbTest.isError = true
    cbTest.resp = e.response?.data
      ? JSON.stringify(e.response.data, null, 2)
      : (e.message || '请求失败')
  } finally {
    cbTest.sending = false
  }
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
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; margin-bottom: 14px; }
.toolbar-filters { display: flex; gap: 10px; flex-wrap: wrap; }
.muted { color: #c0c4cc; font-size: 12px; }
h4 { margin: 20px 0 10px; color: #303133; }
.chain-desc { color: #606266; font-size: 13px; }
.mono { font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 13px; }
.path-row { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.detail-actions { margin-top: 20px; display: flex; gap: 8px; }

/* ---------- Postman 风格（签名元素：请求地址栏） ---------- */
.method-text { font-weight: 700; font-size: 12px; }
.method-POST { color: #FF6C37; }
.method-GET { color: #3BA776; }
.method-PUT { color: #4A90D9; }
.method-DELETE { color: #D9534F; }

.request-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #F5F6FA;
  border: 1px solid #E8EAF0;
  border-radius: 8px;
  padding: 10px 14px;
  margin-bottom: 14px;
}
.request-bar .method-select { width: 110px; }
.request-bar .method-select :deep(.el-select__wrapper) {
  background: #fff;
}
.request-bar .method-select :deep(.el-select__selected-item) {
  color: #303133;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.request-bar .path-input,
.request-bar .target-input { flex: 1; }
.request-bar :deep(.el-input__wrapper) {
  background: #fff;
  border-radius: 4px;
}
.request-bar :deep(.el-input__inner) {
  color: #303133;
  font-family: 'SF Mono', Menlo, Consolas, monospace;
}
.request-bar .arrow { color: #FF6C37; font-weight: 700; font-size: 16px; }

/* ---------- 基础信息 ---------- */
.basic-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px 14px;
  margin-bottom: 10px;
}
.basic-item .basic-label {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

/* ---------- 分区 tab ---------- */
.main-tabs { margin-top: 4px; }

.side-block {
  border: 1px solid #e8eaf0;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 12px;
}
.passthrough-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  padding: 6px 10px;
  background: #f5f6fa;
  border-radius: 4px;
}
.side-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.side-name { font-weight: 600; font-size: 14px; color: #303133; }
.side-desc { font-size: 12px; color: #909399; }
.subtabs { margin-left: auto; display: flex; gap: 4px; }
.subtab {
  border: 1px solid #dcdfe6;
  background: #fff;
  color: #606266;
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 4px;
  cursor: pointer;
}
.subtab.active {
  background: #1F2739;
  border-color: #1F2739;
  color: #fff;
}

.body-types { display: flex; gap: 4px; margin-bottom: 10px; }

.raw-editor {
  width: 100%;
  min-height: 140px;
  box-sizing: border-box;
  background: #282C34;
  color: #abb2bf;
  border: none;
  border-radius: 6px;
  padding: 12px;
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  resize: vertical;
}
.raw-editor:focus { outline: 1px solid #4A90D9; }

.kv-table { margin-bottom: 8px; }

.empty-hint {
  text-align: center;
  color: #c0c4cc;
  font-size: 13px;
  padding: 18px 0;
}

/* ---------- 测试接口弹窗 ---------- */
.test-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.test-body { min-height: 260px; }
.test-resp {
  min-height: 260px;
  margin: 0;
  background: #282C34;
  color: #98c379;
  border-radius: 6px;
  padding: 12px;
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
.test-resp.resp-error { color: #e06c75; }

.add-btn { margin-top: 4px; }

/* ---------- 高级配置 ---------- */
.adv-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px 14px;
}
.adv-item .basic-label {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

/* ===== 分组下拉 · 内联创建（设计稿：doc/设计稿-分组下拉内联创建.html） ===== */
.group-new-action {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  border: none;
  background: none;
  cursor: pointer;
  padding: 7px 20px 7px 11px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  font-family: inherit;
  text-align: left;
  border-top: 1px solid var(--el-border-color-light);
  margin-top: 4px;
}
.group-new-action:hover,
.group-new-action:focus-visible {
  color: var(--el-color-primary);
  background: var(--el-fill-color-light);
  outline: none;
}
.group-new-action .plus {
  font-weight: 600;
}
.group-new-form {
  border-top: 1px solid var(--el-border-color-light);
  margin-top: 4px;
  padding: 8px 11px 6px;
}
.group-new-context {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  margin-bottom: 6px;
}
.group-new-context::before {
  content: '';
  width: 2px;
  height: 12px;
  background: var(--el-color-primary);
  border-radius: 1px;
}
.group-new-context b {
  color: var(--el-color-primary);
  font-weight: 600;
}
.group-new-row {
  display: flex;
  gap: 6px;
}
.group-new-row .el-button + .el-button {
  margin-left: 0;
}
.group-new-err {
  font-size: 12px;
  color: var(--el-color-danger);
  margin-top: 5px;
}
.group-new-err::before {
  content: '⚠ ';
}
.group-new-form.err-input :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
</style>
