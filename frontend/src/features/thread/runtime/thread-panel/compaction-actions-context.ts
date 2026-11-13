import { createContext, useContext } from 'react'
import {
  manualCompactionDisabledReasonKey,
  type CommandCompactionState,
} from '@/features/thread/runtime/thread-panel/thread-commands'
import type { TranslationKey } from '@/shared/i18n'

/**
 * 主 Thread 压缩卡上的“重新压缩”动作：由根控制面提供，卡片只消费。
 *
 * 可用性与命令面板同源（`GET compaction-availability`），不另造一条本地规则；
 * null 表示当前上下文没有手动压缩能力（只读视图或非根宿主），卡片不渲染该动作。
 */
export interface CompactionActions {
  compact: () => void
  compactPending: boolean
  availability: CommandCompactionState | null
}

export const CompactionActionsContext = createContext<CompactionActions | null>(null)

export function useCompactionActions(): CompactionActions | null {
  return useContext(CompactionActionsContext)
}

/** 重新压缩按钮的禁用原因；null 表示可以提交。pending 优先于可用性查询。 */
export function compactionRetryDisabledReasonKey(
  actions: CompactionActions,
): TranslationKey | null {
  if (actions.compactPending) {
    return 'ai.runtime.entry.compactionRetryPending'
  }
  const availability = actions.availability
  if (availability == null || availability.status === 'checking') {
    return 'ai.runtime.command.disabled.compaction.checking'
  }
  if (availability.status === 'failed') {
    return 'ai.runtime.command.disabled.compaction.checkFailed'
  }
  if (!availability.availability.available) {
    return manualCompactionDisabledReasonKey(availability.availability.disabledReason)
  }
  return null
}
