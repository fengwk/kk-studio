import {
  createResourcePart,
  createTextPart,
  mergeTextParts,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import type { HarnessCancelledInputDTO } from '@/shared/api/contracts/ai-runtime'

/**
 * Stop 回执里可恢复的人工输入回填：
 * - USER_MESSAGE 回到 composer 草稿；
 * - GOAL 回到 Goal 编辑区文本。
 * CUSTOM_MESSAGE / NOTIFICATION 不会出现在 cancelledInputs 中，也就不会进入草稿。
 */

/** 回执中的一条 USER_MESSAGE 变成可编辑 parts；其它类型返回空。 */
export function cancelledInputMessageParts(input: HarnessCancelledInputDTO): ComposerPart[] {
  if (input.type !== 'USER_MESSAGE') {
    return []
  }
  const payload = parsePayloadJson(input.payloadJson)
  // canonical 形态是 ThreadCommandPayload JSON `{message:<AgentMessage>}`。
  if (!isRecord(payload) || !('message' in payload)) {
    return [createTextPart(typeof payload === 'string' ? payload : stableText(payload))]
  }
  return payloadMessageToParts(payload.message)
}

/** 回执中的一条 GOAL 变成 Goal 编辑区文本；其它类型或空文本返回 null。 */
export function cancelledInputGoalText(input: HarnessCancelledInputDTO): string | null {
  if (input.type !== 'GOAL') {
    return null
  }
  const payload = parsePayloadJson(input.payloadJson)
  const text = isRecord(payload) ? payload.text : null
  return typeof text === 'string' && text.length > 0 ? text : null
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

/**
 * 把 Stop 取消的 GOAL 合并回 Goal 编辑区：多个目标用空行连接并整体前置于已有文本。
 * 已包含相同目标文本时保持引用不变，避免刷新或重复交付造成重复回填。
 */
export function mergeCancelledGoalTexts(
  cancelled: readonly HarnessCancelledInputDTO[],
  currentGoalText: string | null,
): string | null {
  const restored = cancelled
    .map(cancelledInputGoalText)
    .filter((text): text is string => text != null && text.trim().length > 0)
  if (restored.length === 0) {
    return currentGoalText
  }
  const block = restored.join('\n\n')
  const current = currentGoalText ?? ''
  if (current.trim().length === 0) {
    return block
  }
  if (current.includes(block)) {
    return currentGoalText
  }
  return `${block}\n\n${current}`
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

function parsePayloadJson(payloadJson: string): unknown {
  try {
    return JSON.parse(payloadJson)
  } catch {
    return payloadJson
  }
}

function contentToParts(content: unknown): ComposerPart[] {
  if (!isRecord(content) || typeof content.type !== 'string') {
    return [createTextPart(stableText(content))]
  }
  if (content.type === 'text' || content.type === 'thinking') {
    return [createTextPart(typeof content.text === 'string' ? content.text : stableText(content))]
  }
  if (content.type === 'json') {
    return [createTextPart(jsonContentText(content.json))]
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

function jsonContentText(value: unknown): string {
  if (typeof value === 'string') {
    return value
  }
  return stableText(value)
}

function stableText(value: unknown): string {
  try {
    const text = JSON.stringify(value)
    return text === undefined ? String(value) : text
  } catch {
    return String(value)
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value)
}
