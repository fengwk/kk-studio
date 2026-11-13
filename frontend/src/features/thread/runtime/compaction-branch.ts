import { asRecord, getRecordList } from '@/features/thread/runtime/payload-json'
import {
  contentText,
  parseAssistantUsage,
} from '@/features/thread/runtime/thread-timeline/content-utils'
import type { TurnUsage } from '@/features/thread/runtime/thread-timeline-types'
import { getString } from '@/shared/lib/value'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

/**
 * 一次压缩执行的只读投影：只包含分支自己的 Entry（调用方用 afterEntryId 从 FORK 之后加载）。
 *
 * 继承量取分支第一次模型调用的上下文输入估计（未缓存输入 + 缓存读写），它正是分支
 * 从主对话继承的上下文规模；未知时保持 null，不用 0 伪造。
 */
export interface CompactionBranchModelCall {
  entryId: string
  createdAt: number
  usage: TurnUsage
}

export interface CompactionBranchToolError {
  id: string
  toolName: string
  text: string
}

export interface CompactionBranchProjection {
  /** 分支首条用户消息：固定压缩指令原文。 */
  instruction: string | null
  inheritedContextTokens: number | null
  modelCalls: CompactionBranchModelCall[]
  /** 被停用的平台工具调用：保留工具名与错误正文原文。 */
  toolErrors: CompactionBranchToolError[]
}

export function projectCompactionBranch(
  entries: readonly HarnessSessionEntryDTO[],
): CompactionBranchProjection {
  let instruction: string | null = null
  const modelCalls: CompactionBranchModelCall[] = []
  const toolErrors: CompactionBranchToolError[] = []
  for (const entry of entries) {
    // FORK 是分支锚点事实，不是执行内容。游标读取后的首条 Entry 就是它。
    if (entry.entryType !== 'MESSAGE' && entry.entryType !== 'CUSTOM_MESSAGE') {
      continue
    }
    const payload = asRecord(entry.payload)
    const message = asRecord(payload.message)
    const role = getString(message.role)
    const contents = getRecordList(message.contents)
    if (role === 'USER') {
      if (instruction == null) {
        const text = contents.map(contentText).filter(Boolean).join('\n').trim()
        instruction = text === '' ? null : text
      }
      continue
    }
    if (role === 'ASSISTANT') {
      const usage = parseAssistantUsage(payload.assistantMetadata, entry.usageCost)
      if (usage != null) {
        modelCalls.push({ entryId: entry.entryId, createdAt: entry.createdAt, usage })
      }
      continue
    }
    if (role === 'TOOL') {
      for (const content of contents) {
        if (getString(content.type) !== 'tool_result' || content.error !== true) {
          continue
        }
        const toolCallId = getString(content.toolCallId)
        toolErrors.push({
          id: `${entry.entryId}:${toolCallId}`,
          toolName: getString(content.toolName),
          text: getRecordList(content.contents).map(contentText).filter(Boolean).join('\n'),
        })
      }
    }
  }
  return {
    instruction,
    inheritedContextTokens: modelCalls[0]?.usage.contextInputTokens ?? null,
    modelCalls,
    toolErrors,
  }
}
