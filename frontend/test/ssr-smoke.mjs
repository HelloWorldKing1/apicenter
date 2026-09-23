/**
 * 组件级冒烟（vite build --ssr + vue/server-renderer）：
 * 防止「工具函数正常、组件正文却恒空」一类回归——2026-09-12 就踩过一次
 * （组件内 JS 里少写 `.value`，computed 取到 undefined，抽屉正文恒显占位符「—」）。
 * 另覆盖 P2 能力：语法高亮标记、头串拆行、二进制提示、长报文折叠、空值占位。
 *
 * 运行：`npm run test:ssr`（或随 `npm test` 一起跑）。
 * 说明：Element Plus 组件在此不注册（渲染为空占位）；断言针对组件整体渲染出的文本/HTML 片段。
 */
import { createSSRApp, h } from 'vue'
import { routeLocationKey, routerKey } from 'vue-router'
import { renderToString } from 'vue/server-renderer'
import PayloadViewer from '../src/components/PayloadViewer.vue'
import ParamImportDialog from '../src/components/ParamImportDialog.vue'
import InterfaceParamsTab from '../src/components/InterfaceParamsTab.vue'
import InterfaceStepsTab from '../src/components/InterfaceStepsTab.vue'
// 请求体输入框（测试接口 / 模拟回调共用，2026-09-22）：按「入站协议」提示 + 美化按钮 + 自带样式
import RequestBodyEditor from '../src/components/RequestBodyEditor.vue'
import Login from '../src/views/Login.vue'
import Users from '../src/views/Users.vue'
// 调用方管理页（B4 入站鉴权）
import Clients from '../src/views/Clients.vue'
import InboundAuth from '../src/views/InboundAuth.vue'

function decode(html) {
  return html
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
}

/** 渲染文本：先剥标签**再**解码实体（顺序反了会把报文里的 &lt;tag&gt; 当标签剥掉）；零宽占位去掉 */
function textOf(rawHtml) {
  return decode(rawHtml.replace(/<[^>]+>/g, '')).replace(/\u200b/g, '')
}

/** Element Plus 轻量替身：渲染默认插槽为 span，使依赖 el-* 的文案也能断言（本冒烟不引入 Element Plus 运行时） */
const ElStub = {
  name: 'ElStub',
  setup(props, { slots, attrs }) {
    // 传一个空 scope：el-table-column 之类的作用域插槽会解构 { row }，不传会直接抛错；
    // 顺带渲染 label 属性：表单控件标签（如「用户名」）是属性而非插槽内容，不渲染就断言不到。
    // 注意：本替身未声明 props ⇒ 传入的属性全在 **attrs** 上（props 里是空的）。
    return () => h('span', { class: 'el-stub' }, [
      attrs.label ? h('span', String(attrs.label)) : null,
      // v1.2：卡片头/脚也会承载要断言的文案（如「改后立即生效」）—— 一并渲染，
      // 避免出现「组件确实接上了、但冒烟断言看不到」的假绿。
      slots.header ? slots.header({ row: {}, column: {}, $index: 0 }) : null,
      slots.default ? slots.default({ row: {}, column: {}, $index: 0 }) : [],
      slots.footer ? slots.footer({ row: {}, column: {}, $index: 0 }) : null
    ])
  }
}
const EL_COMPONENTS = ['el-tag', 'el-button', 'el-radio-group', 'el-radio-button',
  'el-dropdown', 'el-dropdown-menu', 'el-dropdown-item', 'el-dialog', 'el-input',
  'el-table', 'el-table-column', 'el-switch', 'el-select', 'el-option', 'el-input-number', 'el-alert',
  'el-form', 'el-form-item', 'el-card',
  // 2026-09-23（B4）：调用方管理页用到抽屉/分页/空态，补进替身清单（替身渲染默认插槽，断言才能看到文案）
  'el-drawer', 'el-pagination', 'el-descriptions', 'el-descriptions-item', 'el-empty']

const longJson = '{"items":[' + Array.from({ length: 80 }, (_, i) => `{"id":${i}}`).join(',') + ']}'
const pngBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/wFvpM0AAAAASUVORK5CYII='

/** 请求参数 tab 的表单夹具（拆分出的 InterfaceParamsTab 直接编辑该对象） */
const paramsForm = {
  ifType: 'OUTBOUND', passthrough: false,
  inParams: [], outParams: [],
  inBodyType: 'json', inBodyRaw: '', inFormRows: [],
  outBodyType: 'none', outBodyRaw: '', outFormRows: []
}

/** 前置步骤 tab 的表单夹具（编排 PS-7） */
const stepsForm = { ifType: 'OUTBOUND', steps: [] }

const CASES = [
  // [标签, props, 断言]
  ['JSON 美化 + 高亮标记', { text: '{"a":1,"seller_id":7494312521977267257}' },
    { html: ['tk-key', 'tk-number'], text: ['"seller_id": 7494312521977267257', '"a": 1'] }],
  ['XML 美化 + 高亮标记', { text: '<r><a k="1">t</a></r>' },
    { html: ['tk-tag', 'tk-attrvalue'], text: ['<a k="1">t</a>'] }],
  ['纯文本原文', { text: 'denied' }, { text: ['denied'] }],
  ['截断报文', { text: '{"a":1,"b":"半截...[truncated]' }, { text: ['半截', '已截断'] }],
  ['头串拆行（值含 | 不误拆）',
    { text: 'Accept: application/json | Content-Type: application/json | X-Note: a | b', headers: true },
    { text: ['X-Note: a | b', 'Content-Type: application/json'] }],
  ['form 拆行', { text: 'name=%E5%BC%A0%E4%B8%89&age=18' }, { text: ['name = 张三', 'age = 18'] }],
  ['二进制提示', { text: '\u0000\u0001PK\u0003\u0004' }, { text: ['二进制内容'] }],
  ['Base64 图片（完整 → 内联预览）', { text: pngBase64 }, { html: ['data:image/png;base64'], text: ['PNG 图片'] }],
  ['长报文折叠', { text: longJson }, { text: ['展开全部（共'] }],
  ['空值占位', { text: null }, { text: ['—'] }],
  // 参数快速导入弹窗（P2 姊妹功能）：确认 setup 不炸、选项与空态正常渲染
  ['参数导入弹窗（空态）', { __component: 'ParamImportDialog', modelValue: true, side: 'IN', sideLabel: '入站侧' },
    { text: ['等待粘贴', '覆盖同名并追加', '全部必填', '从本侧请求体带入', '将导入 0 条'] }],
  // 请求参数 tab（2026-09-12 从 Interfaces.vue 拆出）：两侧面板 + 快速导入入口 + Body 控制
  ['请求参数 Tab（拆分后）', { __component: 'InterfaceParamsTab', form: paramsForm },
    { text: ['入站侧', '出站侧', '来源 → 平台', '平台 → 目标', '快速导入参数', '透传（出站 = 入站原样）', '暂无参数'] }],
  ['请求参数 Tab（透传模式提示）',
    { __component: 'InterfaceParamsTab', form: { ...paramsForm, passthrough: true } },
    { text: ['透传模式：出站报文 = 入站报文原样转发'] }],
  // 前置步骤 Tab（编排，PS-7）：空态 + 上限口径 + 入口按钮（含 <script setup> 里 .value/绑定回归防线）
  ['前置步骤 Tab（空态）', { __component: 'InterfaceStepsTab', form: stepsForm, ifaces: [] },
    { text: ['前置步骤', '暂无前置步骤', '添加前置步骤', '最多 5 步', '不配 = 与现在行为完全一致'] }],
  // 调用方管理页（B4）：表头 / 新建入口 / 凭证提示都要渲染出来（防“逻辑对了但组件没接上”）
  ['调用方管理页',
    { __component: 'Clients' },
    { text: ['新建调用方', '调用方标识', '鉴权方式', 'IP 名单', 'QPS / 日配额', '状态'] }],
  // 入站鉴权页（v1.2 C3）：平台设置（页面可改、改即生效）+ 凭证池（三级属主）
  ['入站鉴权页（平台设置 + 凭证池）',
    { __component: 'InboundAuth' },
    { text: ['平台设置（改后', '立即生效', '平台默认鉴权方式', '强制自报主体', '影响面', '保存设置',
             '入站鉴权凭证池', '发放新凭证', '备注（发给谁 / 何时）', '类型', '指纹'],
      html: ['placeholder="（未配置 ⇒ 未绑定接口一律拒绝 40108）"'] }],
  // 请求体输入框（2026-09-22）：格式随「入站协议」——提示语 + 格式标签 + 美化按钮都要渲染出来
  ['请求体输入框（入站 XML）',
    { __component: 'RequestBodyEditor', modelValue: '<request><event_id>evt-1</event_id></request>', protocolIn: 'XML' },
    { text: ['XML · 按「入站协议」', '必须填 XML', '40002', '美化结构'],
      html: ['placeholder="<request><requestId>'] }],   // placeholder（示例）是属性；decode() 已还原实体 ⇒ 按解码后的字串断言
  // 实时格式校验（2026-09-22）：内容与入站协议不符时，输入即给出黄条提示（不必先点美化）
  ['请求体输入框（XML 入站 + 填了 JSON：实时提示）',
    { __component: 'RequestBodyEditor', modelValue: '{}', protocolIn: 'XML' },
    { text: ['不是 XML', '40002'] }],
  ['请求体输入框（入站 JSON）',
    { __component: 'RequestBodyEditor', modelValue: '{"event_id":"evt-1"}', protocolIn: 'JSON' },
    { text: ['JSON · 按「入站协议」', '必须填合法 JSON', '美化结构'] }],
  // 注：ElStub 给 el-table-column 作用域插槽传的是空 row，行内文案（步骤名 / 目标 code）无法在此断言——
  // 那一层由 PreStepIntegrationTest（后端）与界面手测覆盖；此处只验证「有步骤分支 + 操作入口 + 弹窗渲染」
  ['前置步骤 Tab（有步骤）',
    { __component: 'InterfaceStepsTab',
      form: { ifType: 'OUTBOUND', steps: [
        { seq: 0, stepCode: 'auth', targetInterfaceId: 7, failurePolicy: 'ABORT', enabled: true }
      ] },
      ifaces: [{ id: 7, code: 'IF-AUTH', name: '取 token', ifType: 'OUTBOUND', status: 'PUBLISHED' }],
      selfId: 99 },
    { text: ['阻断后续', '可用字段', '添加前置步骤', '编辑',
             '本接口的入站报文原样', '不参与取值', 'rename: seller_id → filter.seller_id'] }],
  // 登录 / 注册页（2026-09-18 账号登录）：无 router 环境下也必须能渲染（SSR 只跑 setup，不跑 onMounted）
  ['登录页（默认登录态）',
    { __component: 'Login' },
    { text: ['API 中心', '管理控制台', '登录', '注册', '用户名', '密码', '登 录', '还没有账号？'] }],
  // 注意：placeholder 是**属性**，不进文本 → 用 html 断言；「无权限分级」是 el-alert 的默认插槽
  ['账号管理（空态）', { __component: 'Users' },
    { text: ['新建账号', '刷新', '角色模型（OWNER / ADMIN / VIEWER）', '没有匹配的账号',
             '只读（VIEWER）', '拥有者（OWNER）'],
      html: ['placeholder="按用户名 / 显示名搜索"'] }],
  ['前置步骤 Tab（入站接口不支持）',
    { __component: 'InterfaceStepsTab', form: { ifType: 'INBOUND', steps: [] }, ifaces: [] },
    { text: ['入站回调接口不支持前置步骤'] }]
]

async function main() {
  let failed = 0
  for (const [label, props, expect] of CASES) {
    const COMPONENTS = { ParamImportDialog, InterfaceParamsTab, InterfaceStepsTab, RequestBodyEditor, Login, Users, Clients, InboundAuth }
    const component = COMPONENTS[props.__component] || PayloadViewer
    const app = createSSRApp({ render: () => h(component, props) })
    EL_COMPONENTS.forEach((name) => app.component(name, ElStub))
    // 指令替身：`v-loading` 仅 Element Plus 运行时有实现，SSR 冒烟里注册空指令即可
    // （不注册会在 render 阶段抛 `Cannot read properties of undefined (reading 'getSSRProps')`）
    app.directive('loading', {})
    // 轻量 router 替身：组件里 useRoute/useRouter 拿到的对象可读可调用（不引入真实 router）
    app.provide(routeLocationKey, { path: '/login', query: {}, meta: {} })
    app.provide(routerKey, { replace: () => {}, push: () => {} })
    const rawHtml = await renderToString(app)
    const html = decode(rawHtml)
    const text = textOf(rawHtml)
    const missing = [
      ...(expect.html || []).filter((e) => !html.includes(e)),
      ...(expect.text || []).filter((e) => !text.includes(e))
    ]
    if (missing.length) {
      failed++
      console.error(`✖ ${label}：缺少 ${JSON.stringify(missing)}，实际文本=${JSON.stringify(text.replace(/\s+/g, ' ').slice(0, 140))}`)
    } else {
      console.log(`✔ ${label}`)
    }
  }
  if (failed) {
    console.error(`SSR 冒烟失败：${failed}/${CASES.length}`)
    process.exit(1)
  }
  console.log(`SSR 冒烟通过：${CASES.length}/${CASES.length}`)
}

main()
