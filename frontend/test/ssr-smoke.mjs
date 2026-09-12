/**
 * 组件级冒烟（vite build --ssr + vue/server-renderer）：
 * 防止「工具函数正常、组件正文却恒空」一类回归——2026-09-12 就踩过一次
 * （组件内 JS 里少写 `.value`，computed 取到 undefined，抽屉正文恒显占位符「—」）。
 *
 * 运行：`npm run test:ssr`（或随 `npm test` 一起跑）。
 * 说明：Element Plus 组件在此不注册（渲染为空占位），本冒烟只断言 `<pre>` 正文。
 */
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import PayloadViewer from '../src/components/PayloadViewer.vue'

/** 取 <pre class="pv-body"> 内的正文（SSR 输出已做 HTML 转义） */
function bodyOf(html) {
  const raw = html.match(/<pre class="pv-body"[^>]*>([\s\S]*?)<\/pre>/)?.[1] ?? ''
  return raw
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
}

const CASES = [
  ['JSON 美化', '{"a":1,"seller_id":7494312521977267257}', ['"seller_id": 7494312521977267257', '{\n  "a": 1']],
  ['XML 美化', '<r><a>1</a></r>', ['<r>', '\n  <a>1</a>']],
  ['纯文本原文', 'denied', ['denied']],
  ['截断报文', '{"a":1,"b":"半截...[truncated]', ['"a": 1', '半截']],
  ['空值占位', null, ['—']]
]

async function main() {
  let failed = 0
  for (const [label, text, expects] of CASES) {
    const app = createSSRApp({ render: () => h(PayloadViewer, { text }) })
    app.config.warnHandler = () => {}   // 静音：Element Plus 未在本冒烟注册，组件解析告警与本测试无关
    const body = bodyOf(await renderToString(app))
    const missing = expects.filter((e) => !body.includes(e))
    if (missing.length) {
      failed++
      console.error(`✖ ${label}：正文缺少 ${JSON.stringify(missing)}，实际=${JSON.stringify(body)}`)
    } else {
      console.log(`✔ ${label}：${JSON.stringify(body.slice(0, 48))}${body.length > 48 ? '…' : ''}`)
    }
  }
  if (failed) {
    console.error(`SSR 冒烟失败：${failed}/${CASES.length}`)
    process.exit(1)
  }
  console.log(`SSR 冒烟通过：${CASES.length}/${CASES.length}`)
}

main()
