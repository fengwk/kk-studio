import type {
  MetaDialogueMessage,
  TurnUsage,
} from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import type { SettingsSnapshot } from '@/features/thread/runtime/thread-timeline/entry-event-projection'

/**
 * 逐 turn 的投影上下文。Turn usage 不紧跟 Assistant：summary 先挂起，在相应
 * TURN_END 之后才投影为 TurnSummary；新 turn 开始时丢弃未关闭 turn 的残留。
 */
export interface EntryProjectionContext {
  pendingTurnSummary: MetaDialogueMessage | null
  lastSettings: SettingsSnapshot | null
  pendingTurnUsage?: TurnUsage | null
}
