/**
 * 大报文格式化 Worker（> MAX_FORMAT_CHARS 时由 PayloadViewer 启用）。
 *
 * 为什么需要：扫描式缩进本身很快（实测 250KB ≈ 9ms），但主线程上「格式化 + 上万行 token 渲染」
 * 叠加仍会掉帧；放到 Worker 后主线程先渲染原文，完成后无缝替换为美化结果。
 * 注意：Worker 只做「格式化」，不改写任何 token（复用 utils/payload.mjs 同一套实现）。
 */
import { analyzePayload, WORKER_FORMAT_LIMIT } from '../utils/payload.mjs'

self.onmessage = (event) => {
  const { key, text, contentType } = event.data || {}
  if (typeof text !== 'string' || text.length > WORKER_FORMAT_LIMIT) {
    // 超过 Worker 上限：回 null，调用方保持原文并给出提示
    self.postMessage({ key, view: null })
    return
  }
  try {
    self.postMessage({ key, view: analyzePayload(text, contentType) })
  } catch (e) {
    self.postMessage({ key, view: null })
  }
}
