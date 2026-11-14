import { extractResultEditReview } from '@/features/thread/runtime/mutation-review'
import { getRecordList, parseRawJsonText } from '@/features/thread/runtime/payload-json'
import type { ToolDialogueMessage } from '@/features/thread/runtime/thread-timeline-types'
import { toToolContents } from '@/features/thread/runtime/thread-timeline/content-utils'

/**
 * 在 durable Entry 边界将持久化的 ToolResultMessageContent 解码为规范共享结果语义。
 * durable 边界只认 detailsJson 字段；缺 detailsJson 或损坏时 details 均为 undefined，
 * 绝不透传或旁路未知的 details 对象。
 */
export function decodeDurableToolResultContent(content: Record<string, unknown>): Record<string, unknown> {
  let details: Record<string, unknown> | undefined
  const rawDetailsJson = content.detailsJson
  if (typeof rawDetailsJson === 'string' && rawDetailsJson.trim()) {
    try {
      const parsed = JSON.parse(rawDetailsJson)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        details = parsed as Record<string, unknown>
      }
    } catch {
      // 损坏的 detailsJson 解析失败，details 保持 undefined，不假造或旁路未知对象
    }
  }
  return {
    ...content,
    // AgentMessageJsonCodec 的 durable content.json 是明确的 JSON 原文；
    // ToolResultJsonCodec 的实时和 invocation content.json 则已是节点。
    contents: getRecordList(content.contents).flatMap((item) => {
      if (item.type !== 'json') {
        return [item]
      }
      return typeof item.json === 'string'
        ? [{ ...item, json: parseRawJsonText(item.json) }]
        : []
    }),
    details,
  }
}

/**
 * 从规范 ToolResult JSON 解析有序展示内容和错误。
 * durable Entry 与尚未物化的 invocation result 共用这一事实，避免两套结果格式。
 */
export function projectToolResultContent(
  content: Record<string, unknown>,
  argumentsJson: string,
): Pick<ToolDialogueMessage, 'arguments' | 'contents' | 'details' | 'errorMessage' | 'status' | 'editReview'> {
  const error = content.error === true
  const contents = toToolContents(getRecordList(content.contents))
  // 错误直接使用工具结果原文：不再套用通用「工具执行失败」文案；没有可展示原文时
  // 只有错误态（hasError 仍为 true），不伪造摘要。
  const rawError = contents
    .flatMap((item) => (item.type === 'text' ? [item.text] : []))
    .join('\n')
    .trim()
  const editReview = extractResultEditReview(content) ?? undefined
  return {
    arguments: argumentsJson,
    contents,
    details: content.details != null && typeof content.details === 'object' && !Array.isArray(content.details)
      ? content.details as Record<string, unknown> : undefined,
    errorMessage: error && rawError ? rawError : undefined,
    status: error ? 'error' : 'done',
    editReview,
  }
}
