import { createContext } from 'react'

/** 节点源位置的最小结构；只有 offset 存在时才能精确切回原文。 */
type SourcePoint = { offset?: number | null }
type SourcePosition = { start?: SourcePoint; end?: SourcePoint }

/**
 * 当前 markdown segment 的原始文本。
 * react-markdown 解析的是分段后的文本，节点 offset 因此相对本段；
 * Mermaid 分段后各段重新编号，绝不能用整条消息的 offset 套用。
 */
export const MarkdownSegmentSourceContext = createContext<string | null>(null)

/**
 * 用表格节点的 offset 取回该表格在本段中的原始 Markdown：
 * 含表头、对齐分隔行与全部单元格原文（转义竖线、链接、加粗、行内代码照原样保留）。
 * 位置缺失时返回 null，调用方不渲染复制按钮，而不是给出空源码。
 */
export function readMarkdownTableSource(
  segmentSource: string | null,
  position: SourcePosition | undefined,
): string | null {
  const start = position?.start?.offset
  const end = position?.end?.offset
  if (
    segmentSource === null
    || typeof start !== 'number'
    || typeof end !== 'number'
    || end <= start
  ) {
    return null
  }
  return segmentSource.slice(start, end)
}
