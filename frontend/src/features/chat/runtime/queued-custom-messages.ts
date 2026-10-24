import { asRecord, getRecordList, getString, parsePayload } from '@/features/chat/runtime/payload-json'
import type { HarnessThreadCommandDTO } from '@/shared/api/generated/contracts'

export interface QueuedCustomMessage {
  id: string
  sequence: string
  text: string
}

/**
 * 从 snapshot.queuedCommands 直接派生只读待处理补充要求（QUEUED CUSTOM_MESSAGE）。
 *
 * - 仅保留 state === 'QUEUED' 且 type === 'CUSTOM_MESSAGE' 的命令；
 * - 原始 payload.message.contents 里的实际文本提取为展示正文，空文本忽略；
 * - 顺序保留 queuedCommands 原始顺序，不进人类可编辑队列 USER_MESSAGE / GOAL，不写删重排；
 * - 当后端执行完毕且快照中状态不再是 QUEUED 时，自然从派生列表中移除，历史沿用 durable entry。
 */
export function extractQueuedCustomMessages(
  queuedCommands: readonly HarnessThreadCommandDTO[],
): QueuedCustomMessage[] {
  const result: QueuedCustomMessage[] = []
  for (const command of queuedCommands) {
    if (command.state !== 'QUEUED' || command.type !== 'CUSTOM_MESSAGE') {
      continue
    }
    const text = extractCustomMessageText(command.payloadJson)
    if (!text.trim()) {
      continue
    }
    result.push({
      id: command.idempotencyKey,
      sequence: command.sequence,
      text,
    })
  }
  return result
}

function extractCustomMessageText(payloadJson: string | null): string {
  const payload = parsePayload(payloadJson)
  const message = asRecord(payload.message)
  const contents = getRecordList(message.contents)
  const textParts: string[] = []
  for (const item of contents) {
    if (item.type === 'text') {
      const text = getString(item.text)
      if (text) {
        textParts.push(text)
      }
    }
  }
  return textParts.join('\n')
}
