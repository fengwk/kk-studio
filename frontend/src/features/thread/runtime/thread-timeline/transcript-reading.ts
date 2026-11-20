/**
 * 阅读意图契约：嵌套在 transcript 里的只读区域（工具输出视口、思考正文）在用户回看或
 * 与它交互时，通过冒泡的 DOM 事件上报「用户正在读这里」，并携带来源标识。
 *
 * 外层 transcript 的跟随状态只由真实用户滚动决定（见 `useChatTranscriptAutoScroll`）：
 * 展开详情、选择文本、媒体加载与懒渲染都不翻转跟随，因此本事件不参与跟随状态，
 * 也从不表达「恢复」。
 *
 * 工具、思考和压缩卡片共用此接口：
 * `announceTranscriptReading(element, 'thinking')`，其中 `element` 必须是
 * transcript 滚动容器（`.thread-dialogue`）的后代节点。
 */
export const TRANSCRIPT_READING_INTENT_EVENT = 'thread-transcript-reading-intent'

export function announceTranscriptReading(element: Element, source: string): void {
  element.dispatchEvent(new CustomEvent(TRANSCRIPT_READING_INTENT_EVENT, {
    bubbles: true,
    detail: { source },
  }))
}
