import {
  createResourcePart,
  createTextPart,
  mergeTextParts,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import type { HarnessCancelledUserMessageDTO } from '@/shared/api/contracts/ai-runtime'

/** 把 Stop 取消消息按 sequence 顺序前置到当前草稿；消息边界固定为两个换行。 */
export function prependCancelledMessages(
  cancelled: HarnessCancelledUserMessageDTO[],
  currentDraft: ComposerPart[],
): ComposerPart[] {
  const prefix: ComposerPart[] = []
  for (const message of cancelled) {
    const messageParts = messageJsonToParts(message.messageJson)
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

export function messageJsonToParts(messageJson: string): ComposerPart[] {
  let parsed: unknown
  try {
    parsed = JSON.parse(messageJson)
  } catch {
    return [createTextPart(messageJson)]
  }
  if (!isRecord(parsed) || !Array.isArray(parsed.contents)) {
    return [createTextPart(stableText(parsed))]
  }
  return parsed.contents.flatMap(contentToParts)
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
  ) {
    return [
      createResourcePart(
        content.blobId,
        content.name,
        typeof content.preview === 'string' ? content.preview : undefined,
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
