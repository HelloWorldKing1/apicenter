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
    return () => h('span', { class: 'el-stub' }, slots.default ? slots.default() : [])
  }
}
const EL_COMPONENTS = ['el-tag', 'el-button', 'el-radio-group', 'el-radio-button',
  'el-dropdown', 'el-dropdown-menu', 'el-dropdown-item']

const longJson = '{"items":[' + Array.from({ length: 80 }, (_, i) => `{"id":${i}}`).join(',') + ']}'
const pngBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/wFvpM0AAAAASUVORK5CYII='

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
  ['空值占位', { text: null }, { text: ['—'] }]
]

async function main() {
  let failed = 0
  for (const [label, props, expect] of CASES) {
    const app = createSSRApp({ render: () => h(PayloadViewer, props) })
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
