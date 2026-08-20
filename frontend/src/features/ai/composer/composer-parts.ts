import type { HarnessUserMessageContentDTO } from '@/shared/api/contracts/ai-runtime'
import { createUuid } from '@/shared/lib/uuid'

/**
 * Composer 草稿的 ordered parts 模型。
 *
 * 每个 part 都携带客户端 UUID（partId）；text part 持有纯文本，attachment part
 * 引用共享存储的上传（uploadId），resource part 引用当前 Session 已拥有的 durable
 * blob。typed '@filename' 永远保持为文本，只有文件粘贴/拖放/选择（或显式附件选择）
 * 才会创建 attachment part。
 */
export type ComposerPart =
  | { type: 'text'; partId: string; text: string }
  | { type: 'attachment'; partId: string; uploadId: string; filename: string }
  | {
    type: 'resource'
    partId: string
    blobId: string
    name: string
    preview?: string
  }

export function createPartId(): string {
  return createUuid()
}

export function createTextPart(text: string): ComposerPart {
  return { type: 'text', partId: createPartId(), text }
}

export function createAttachmentPart(uploadId: string, filename: string): ComposerPart {
  return { type: 'attachment', partId: createPartId(), uploadId, filename }
}

export function createResourcePart(
  blobId: string,
  name: string,
  preview?: string,
): ComposerPart {
  return {
    type: 'resource',
    partId: createPartId(),
    blobId,
    name,
    ...(preview !== undefined ? { preview } : {}),
  }
}

/** 非文本 parts 之外的纯文本（用于展示与 text-only shorthand）。 */
export function partsToText(parts: ComposerPart[]): string {
  return parts
    .map((part) => (part.type === 'text' ? part.text : ''))
    .join('')
}

/** 是否有可发送内容：非空白文本或任意 attachment/resource part。 */
export function hasMessageContent(parts: ComposerPart[]): boolean {
  return parts.some((part) =>
    part.type !== 'text' || part.text.trim() !== '',
  )
}

/**
 * 是否为以 '/' 开头的纯文本命令草稿。
 *
 * Slash 只是打开命令菜单的文本快捷入口；一旦存在附件，就按普通消息处理，绝不让
 * 命令选择静默清空附件。
 */
export function slashQueryOf(parts: ComposerPart[]): string | null {
  if (parts.length === 0) {
    return null
  }
  let text = ''
  for (const part of parts) {
    if (part.type !== 'text') {
      return null
    }
    text += part.text
  }
  return text.startsWith('/') ? text.slice(1) : null
}

/** 合并相邻 text parts 为规范形态（DOM 提取天然合并，状态侧保持同构）。 */
export function mergeTextParts(parts: ComposerPart[]): ComposerPart[] {
  const merged: ComposerPart[] = []
  for (const part of parts) {
    if (part.type !== 'text') {
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
 * 保留 attachment/resource parts 的相对位置。供发送/序列化前规范化。
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
  return result.filter((part) => part.type !== 'text' || part.text.length > 0)
}

/**
 * 规范化后 parts 的稳定序列化键（text 内容 + attachment/resource 的 durable
 * identity 与 partId）。用于 DOM 同步与发送恢复检测。
 *
 * text 部分只含内容（DOM 提取会为文本节点重建 partId，不能参与键）；非文本 part
 * 用引用字段 + partId 标识 occurrence——同名/同句柄的不同 occurrence 键也不同。
 */
export function partsKey(parts: ComposerPart[]): string {
  return JSON.stringify(
    parts.map((part) => {
      if (part.type === 'text') {
        return ['t', part.text]
      }
      if (part.type === 'attachment') {
        return ['a', part.uploadId, part.filename, part.partId]
      }
      return ['r', part.blobId, part.name, part.preview ?? null, part.partId]
    }),
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
 * 序列化为 USER_MESSAGE contents：TEXT / ATTACHMENT / RESOURCE，保持 ordered 语义。
 */
export function partsToMessageContents(parts: ComposerPart[]): HarnessUserMessageContentDTO[] {
  return parts.map((part) => {
    if (part.type === 'text') {
      return { type: 'TEXT', text: part.text }
    }
    if (part.type === 'attachment') {
      return { type: 'ATTACHMENT', uploadId: part.uploadId }
    }
    return {
      type: 'RESOURCE',
      blobId: part.blobId,
      name: part.name,
      ...(part.preview !== undefined ? { preview: part.preview } : {}),
    }
  })
}
