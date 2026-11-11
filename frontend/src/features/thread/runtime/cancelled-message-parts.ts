import {
  createResourcePart,
  createTextPart,
  mergeTextParts,
  type ComposerPart,
} from '@/features/thread/composer/composer-parts'
import type { HarnessCancelledInputDTO } from '@/shared/api/generated/contracts'
import { isRecord } from '@/shared/lib/value'

/**
 * Stop 回执里可恢复的人工输入回填：
 * - USER_MESSAGE 回到 composer 草稿；
 * CUSTOM_MESSAGE / NOTIFICATION 不会出现在 cancelledInputs 中，也就不会进入草稿。
 */

/** 回执中的一条 USER_MESSAGE 变成可编辑 parts；其它类型返回空。 */
export function cancelledInputMessageParts(input: HarnessCancelledInputDTO): ComposerPart[] {
  if (input.type !== 'USER_MESSAGE') {
    return []
  }
  const payload = input.payload
  // canonical 形态是 ThreadCommandPayload JSON `{message:<AgentMessage>}`。
  if (!isRecord(payload) || !('message' in payload)) {
    return [createTextPart(typeof payload === 'string' ? payload : stableText(payload))]
  }
  return payloadMessageToParts(payload.message)
}

/**
 * 把 Stop 取消的 USER_MESSAGE 按 sequence 顺序前置到当前草稿；消息边界固定为两个换行。
 * 没有任何可恢复消息时原样返回当前草稿（保持引用不变）。
 */
export function prependCancelledMessages(
  cancelled: readonly HarnessCancelledInputDTO[],
  currentDraft: ComposerPart[],
): ComposerPart[] {
  const prefix: ComposerPart[] = []
  for (const input of cancelled) {
    const messageParts = cancelledInputMessageParts(input)
    if (messageParts.length === 0) {
      continue
    }
    if (prefix.length > 0) {
      prefix.push(createTextPart('\n\n'))
    }
    prefix.push(...messageParts)
  }
  if (prefix.length === 0) {
    return currentDraft
  }
  if (currentDraft.length > 0) {
    prefix.push(createTextPart('\n\n'))
  }
  return mergeTextParts([...prefix, ...currentDraft])
}

export function payloadMessageToParts(message: unknown): ComposerPart[] {
  if (message == null) {
    return []
  }
  if (!isRecord(message) || !Array.isArray(message.contents)) {
    return [createTextPart(stableText(message))]
  }
  return message.contents.flatMap(contentToParts)
}

function contentToParts(content: unknown): ComposerPart[] {
  if (!isRecord(content) || typeof content.type !== 'string') {
    return [createTextPart(stableText(content))]
  }
  if (content.type === 'text' || content.type === 'thinking') {
    return [createTextPart(typeof content.text === 'string' ? content.text : stableText(content))]
  }
  if (content.type === 'json' && typeof content.json === 'string') {
    // 契约中 json 内容就是 JSON 原文，直接作为文本回填，不再二次序列化。
    return [createTextPart(content.json)]
  }
  if (
    content.type === 'resource'
    && typeof content.blobId === 'string'
    && content.blobId.trim()
    && typeof content.name === 'string'
    && content.name.trim()
    && (content.preview == null || typeof content.preview === 'string')
    && (content.imageTier == null
      || content.imageTier === '720P'
      || content.imageTier === '1080P'
      || content.imageTier === 'ORIGINAL')
  ) {
    return [
      createResourcePart(
        content.blobId,
        content.name,
        typeof content.preview === 'string' ? content.preview : undefined,
        typeof content.imageTier === 'string' ? content.imageTier : undefined,
      ),
    ]
  }
  // USER role 的未知 durable content 不应静默丢失；保留 canonical object 文本供用户检查/编辑。
  return [createTextPart(stableText(content))]
}

function stableText(value: unknown): string {
  try {
    const text = JSON.stringify(value)
    return text === undefined ? String(value) : text
  } catch {
    return String(value)
  }
}
