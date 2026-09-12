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
import { renderToString } from 'vue/server-renderer'
import PayloadViewer from '../src/components/PayloadViewer.vue'
import ParamImportDialog from '../src/components/ParamImportDialog.vue'
import InterfaceParamsTab from '../src/components/InterfaceParamsTab.vue'

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
  setup(props, { slots }) {
    // 传一个空 scope：el-table-column 之类的作用域插槽会解构 { row }，不传会直接抛错
    return () => h('span', { class: 'el-stub' },
      slots.default ? slots.default({ row: {}, column: {}, $index: 0 }) : [])
  }
}
const EL_COMPONENTS = ['el-tag', 'el-button', 'el-radio-group', 'el-radio-button',
  'el-dropdown', 'el-dropdown-menu', 'el-dropdown-item', 'el-dialog', 'el-input',
  'el-table', 'el-table-column', 'el-switch', 'el-select', 'el-option', 'el-input-number']

const longJson = '{"items":[' + Array.from({ length: 80 }, (_, i) => `{"id":${i}}`).join(',') + ']}'
const pngBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/wFvpM0AAAAASUVORK5CYII='

/** 请求参数 tab 的表单夹具（拆分出的 InterfaceParamsTab 直接编辑该对象） */
const paramsForm = {
  ifType: 'OUTBOUND', passthrough: false,
  inParams: [], outParams: [],
  inBodyType: 'json', inBodyRaw: '', inFormRows: [],
  outBodyType: 'none', outBodyRaw: '', outFormRows: []
}

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
    { text: ['透传模式：出站报文 = 入站报文原样转发'] }]
]

async function main() {
  let failed = 0
  for (const [label, props, expect] of CASES) {
    const COMPONENTS = { ParamImportDialog, InterfaceParamsTab }
    const component = COMPONENTS[props.__component] || PayloadViewer
    const app = createSSRApp({ render: () => h(component, props) })
    EL_COMPONENTS.forEach((name) => app.component(name, ElStub))
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
