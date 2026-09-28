/**
 * SFC 模板**结构**静态检查（2026-09-25 新增）。
 *
 * <p>起因：`views/Monitor.vue` 的「接入鉴权」`<el-tab-pane>` 漏了闭合标签（B4 `cc4d7dc` 引入），
 * Vue 编译器**宽容解析**不报错 ⇒ 该 pane 被**嵌进前一个 pane（告警）**里。
 * 症状极具迷惑性：**Tab 能点、内容区一片空白**（`el-table` 的 `v-show` 判定为真，
 * 但祖先 pane 是 `display:none`）—— 数据请求 **200、有行**，控制台也无报错，
 * 于是「审计没写」被误判了整整两天（详见《技术踩坑记录》§15）。
 *
 * <p>注意：**计数平衡（6 个开 / 6 个闭）看不出问题**，所以这里按**嵌套顺序**检查：
 * `el-tab-pane` 不允许嵌套，每个 pane 必须在下一个 pane 开始前闭合。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'

const ROOT = process.cwd()

/** 收集所有 SFC（views + components + layout，都是会被渲染的模板） */
function sfcFiles() {
  const out = []
  for (const dir of ['src/views', 'src/components', 'src/layout']) {
    let names
    try {
      names = readdirSync(path.join(ROOT, dir))
    } catch {
      continue
    }
    for (const n of names) {
      if (n.endsWith('.vue')) {
        out.push(path.join(dir, n))
      }
    }
  }
  return out
}

/** 行号（1-based）：报告里能直接跳到出错那行 */
function lineOf(text, index) {
  return text.slice(0, index).split('\n').length
}

test('el-tab-pane 必须在自己内部闭合（不允许嵌套进上一个 pane）', () => {
  const problems = []
  for (const rel of sfcFiles()) {
    const src = readFileSync(path.join(ROOT, rel), 'utf8')
    const re = /<(\/?)el-tab-pane\b[^>]*?(\/?)>/g
    let depth = 0
    let m
    while ((m = re.exec(src)) !== null) {
      const isClose = m[1] === '/'
      const selfClosing = m[2] === '/'
      if (selfClosing) {
        continue
      }
      if (isClose) {
        if (depth === 0) {
          problems.push(`${rel}:${lineOf(src, m.index)} 多出来的 </el-tab-pane>（没有对应的开标签）`)
        } else {
          depth -= 1
        }
      } else {
        if (depth > 0) {
          problems.push(`${rel}:${lineOf(src, m.index)} 这个 <el-tab-pane> 被嵌在上一个 pane 里` +
            `（上一个 pane 缺 </el-tab-pane>：切到该 Tab 会看到内容区空白）`)
        }
        depth += 1
      }
    }
    if (depth !== 0) {
      problems.push(`${rel}: 有 ${depth} 个 <el-tab-pane> 没有闭合`)
    }
  }
  assert.deepEqual(problems, [], `模板结构问题：\n  ${problems.join('\n  ')}`)
})
