import type { HarnessUserMessageContentDTO } from '@/shared/api/contracts/ai-runtime'
import { createUuid } from '@/shared/lib/uuid'

/**
 * Composer 草稿的 ordered parts 模型。
 *
 * 每个 part 都携带客户端 UUID（partId）；text part 持有纯文本，attachment part
 * 引用共享存储的上传（uploadId）。typed '@filename' 永远保持为文本，只有文件
 * 粘贴/拖放/选择（或显式附件选择）才会创建 attachment part。
 */
export type ComposerPart =
  | { type: 'text'; partId: string; text: string }
  | { type: 'attachment'; partId: string; uploadId: string; filename: string }

export function createPartId(): string {
  return createUuid()
}

export function createTextPart(text: string): ComposerPart {
  return { type: 'text', partId: createPartId(), text }
}

export function createAttachmentPart(uploadId: string, filename: string): ComposerPart {
  return { type: 'attachment', partId: createPartId(), uploadId, filename }
}

/** attachment parts 之外的纯文本（用于展示与 text-only shorthand）。 */
export function partsToText(parts: ComposerPart[]): string {
  return parts
    .map((part) => (part.type === 'text' ? part.text : ''))
    .join('')
}

/** 是否有可发送内容：非空白文本或任意 attachment part。 */
export function hasMessageContent(parts: ComposerPart[]): boolean {
  return parts.some((part) =>
    part.type === 'attachment' || part.text.trim() !== '',
  )
}

/** 首 part 是否为以 '/' 开头的 slash 文本（slash 模式只从 composer 开头进入）。 */
export function slashQueryOf(parts: ComposerPart[]): string | null {
  const first = parts[0]
  if (first?.type !== 'text' || !first.text.startsWith('/')) {
    return null
  }
  return first.text.slice(1)
}

/** 合并相邻 text parts 为规范形态（DOM 提取天然合并，状态侧保持同构）。 */
export function mergeTextParts(parts: ComposerPart[]): ComposerPart[] {
  const merged: ComposerPart[] = []
  for (const part of parts) {
    if (part.type === 'attachment') {
      merged.push(part)
      continue
    }
    const previous = merged[merged.length - 1]
    if (previous?.type === 'text') {
      merged[merged.length - 1] = { ...previous, text: previous.text + part.text }
    } else {
      merged.push(part)
    }
  }
  return merged
}

/**
 * 合并相邻 text parts、去掉消息两端的空白（textarea 时代的 trim 语义），
 * 保留 attachment parts 的相对位置。供发送/序列化前规范化。
 */
export function trimMessageParts(parts: ComposerPart[]): ComposerPart[] {
  const result = mergeTextParts(parts)
  while (result.length > 0) {
    const first = result[0]
    if (first?.type !== 'text' || first.text.trim() !== '') {
      break
    }
    result.shift()
  }
  while (result.length > 0) {
    const last = result[result.length - 1]
    if (last?.type !== 'text' || last.text.trim() !== '') {
      break
    }
    result.pop()
  }
  const first = result[0]
  const last = result[result.length - 1]
  if (first?.type === 'text') {
    result[0] = { ...first, text: first.text.replace(/^\s+/, '') }
  }
  if (last?.type === 'text') {
    result[result.length - 1] = { ...last, text: last.text.replace(/\s+$/, '') }
  }
  return result.filter((part) => part.type === 'attachment' || part.text.length > 0)
}

/**
 * 规范化后 parts 的稳定序列化键（text 内容 + attachment 的客户端 uploadId 与
 * partId）。用于 DOM 同步与发送恢复检测。
 *
 * text 部分只含内容（DOM 提取会为文本节点重建 partId，不能参与键）；attachment
 * 用 uploadId + partId 标识 occurrence——同名/同句柄的不同 occurrence 键也不同，
 * 替换其中一颗 pill 时 DOM 会重建为正确的 partId，而不是保留过期身份。
 */
export function partsKey(parts: ComposerPart[]): string {
  return JSON.stringify(
    parts.map((part) =>
      part.type === 'text' ? ['t', part.text] : ['a', part.uploadId, part.partId],
    ),
  )
}

/** 按 partId 移除 part（pill 键盘删除/外部同步）。 */
export function removePartsByIds(parts: ComposerPart[], partIds: ReadonlySet<string>): ComposerPart[] {
  return parts.filter((part) => !partIds.has(part.partId))
}

/** 移除引用指定上传（localId 或服务端 upload 句柄）的全部 attachment parts。 */
export function removePartsByUpload(
  parts: ComposerPart[],
  uploadIds: ReadonlySet<string>,
): ComposerPart[] {
  return parts.filter(
    (part) => part.type !== 'attachment' || !uploadIds.has(part.uploadId),
  )
}

/**
 * 序列化为 USER_MESSAGE contents：TEXT parts -> {type:'TEXT'}，attachment parts
 * -> {type:'ATTACHMENT', uploadId}，保持 ordered 语义。
 */
export function partsToMessageContents(parts: ComposerPart[]): HarnessUserMessageContentDTO[] {
  return parts.map((part) =>
    part.type === 'text'
      ? { type: 'TEXT', text: part.text }
      : { type: 'ATTACHMENT', uploadId: part.uploadId },
  )
}
