import type {
  DialogueMessage,
  ToolContent,
} from '@/features/ai/runtime/thread-timeline-types'

/**
 * 阅读意图契约：嵌套在 transcript 里的只读区域（工具输出视口、思考正文）在用户回看或
 * 与它交互时，通过冒泡的 DOM 事件通知外层「用户正在读这里」，外层据此暂停自动贴底。
 *
 * - 外层只监听、不猜测：媒体加载、懒渲染、样式切换等布局尺寸变化不构成阅读意图，
 *   也不构成流式信号；
 * - 恢复只有一条路径：外层自己滚回到贴底阈值内，才重新跟随；
 * - 该事件只表达「暂停」，从不表达「恢复」，因此内层滚回自己的底部不会抢走外层控制权。
 *
 * 用法（E 的 ThinkingBlock 后续接同一接口，无需修改本文件）：
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

/**
 * 流式正文的显式更新签名（stream revision）。
 *
 * 只由「仍在流式」的正文文本构成：
 * - assistant：消息文本；
 * - tool：调用参数的 text / json 片段，json 用确定性序列化；
 * - resource（图片、音频、视频等媒体）不参与——异步媒体加载与解码绝不产生 revision，
 *   因此绝不触发外层贴底；
 * - 只有推理思考（没有正文文本）时也不产生 revision，思考区本身默认一行，展开是
 *   用户手动事件。
 *
 * 没有流式内容时返回 null。
 */
export function transcriptStreamRevision(messages: DialogueMessage[]): string | null {
  const parts: string[] = []
  for (const message of messages) {
    if (message.status !== 'streaming') {
      continue
    }
    if (message.role === 'assistant') {
      // 仅有思考、还没有正文时文本为空：签名保持不变，不产生新 revision。
      if ((message.text ?? '').length > 0) {
        parts.push(`${message.id}:${message.text.length}`)
      }
      continue
    }
    if (message.role === 'tool') {
      parts.push(`${message.id}:${contentsLength(message.partialContents ?? [])}`)
      continue
    }
    parts.push(message.id)
  }
  return parts.length > 0 ? parts.join('|') : null
}

function contentsLength(contents: ToolContent[]): number {
  let total = 0
  for (const content of contents) {
    if (content.type === 'text') {
      total += content.text.length
    } else if (content.type === 'json') {
      total += JSON.stringify(content.value)?.length ?? 0
    }
  }
  return total
}
