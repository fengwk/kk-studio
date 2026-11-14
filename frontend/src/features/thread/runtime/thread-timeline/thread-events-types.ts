import { translate, type TranslationKey } from '@/shared/i18n'
import type { TurnUsage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'

/**
 * Event 投影的独立模型：不依赖 DialogueMessage 与 transcript 渲染。
 *
 * durable Entry 全类型都会保留且**恰好一条记录**（含控制边界 TURN_START /
 * TURN_END / COMPACTION）；活跃 model/tool invocation 是单条 synthetic 记录
 * （Provider delta token 绝不逐条成行），并锚定在所属 durable Entry 之后。
 */
export type ThreadEventSource = 'entry' | 'active-model' | 'active-tool' | 'attempt-failure'

export type ThreadEventKind =
  | 'ROOT'
  | 'TURN_START'
  | 'SETTINGS'
  | 'USER_MESSAGE'
  | 'ASSISTANT_MESSAGE'
  | 'TOOL_CALL'
  | 'TOOL_RESULT'
  | 'NOTIFICATION'
  | 'FORK'
  | 'MODEL_ATTEMPT_FAILURE'
  | 'ASSISTANT_ERROR'
  | 'ASSISTANT_ABORTED'
  | 'UNKNOWN_ENTRY'
  | 'CUSTOM_MESSAGE'
  | 'COMPACTION'
  | 'TURN_END'
  | 'ACTIVE_MODEL_INVOCATION'
  | 'ACTIVE_TOOL_INVOCATION'

export type ThreadEventStatus = 'pending' | 'running' | 'completed' | 'failed' | 'stopped'

export interface ThreadEventDetailRow {
  label: string
  value: string
}

export interface ThreadEventRecord {
  id: string
  source: ThreadEventSource
  /** durable Entry id；synthetic 活跃记录为 null（不冒充持久事实）。 */
  entryId: string | null
  /** 所属 Turn 的 TURN_START entryId；TURN_START 之前的记录为 null。 */
  turnStartEntryId: string | null
  /** 1-based Turn 序号；TURN_START 之前的记录为 0。 */
  turnNumber: number
  kind: ThreadEventKind
  status: ThreadEventStatus
  /**
   * 调试标签：durable Entry 使用语义标题（消息按真实 USER/ASSISTANT/TOOL 角色，通知按真实
   * kind），synthetic 使用内部 kind 名。entryType 只在详情行保留，不覆盖语义标题。
   */
  title: string
  /** 单行摘要：durable Entry 使用压缩后的 payload JSON。 */
  summary: string
  /** epoch 毫秒。 */
  createdAt: number
  /** 结构化详情行（detail widget 渲染为 label/value）。 */
  details: ThreadEventDetailRow[]
  /** 事件的完整原始 JSON（durable Entry 为 payload JSON，synthetic 活跃记录为其 DTO / realtime stream 的 pretty JSON）。 */
  rawJson: string | null
  /** ASSISTANT / COMPACTION 实际携带模型输出 metadata，可读取历史请求预览；非模型记录无入口。 */
  historicalPreviewEligible?: boolean
}

/** durable 记录的公共字段（kind/status/title/summary/details 由各分支补全）。 */
export type RecordBase = Pick<
  ThreadEventRecord,
  'id' | 'source' | 'entryId' | 'turnStartEntryId' | 'turnNumber' | 'createdAt' | 'rawJson'
> & {
  details: ThreadEventDetailRow[]
}

export interface EntryTurnContext {
  turnStartEntryId: string | null
  turnNumber: number
  pendingUsage: TurnUsage | null
}

const STATUS_TEXT_KEY: Record<ThreadEventStatus, TranslationKey> = {
  pending: 'ai.runtime.event.status.PENDING',
  running: 'ai.runtime.event.status.RUNNING',
  completed: 'ai.runtime.event.status.COMPLETED',
  failed: 'ai.runtime.event.status.FAILED',
  stopped: 'ai.runtime.event.status.STOPPED',
}

/** 五态状态本地化。 */
export function eventStatusText(status: ThreadEventStatus): string {
  return translate(STATUS_TEXT_KEY[status])
}
