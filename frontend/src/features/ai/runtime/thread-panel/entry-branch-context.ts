import { createContext, useContext } from 'react'
import type { BranchRequestInput } from '@/features/ai/runtime/useRootThreadControl'

/**
 * 会话里“从此处分支”的统一入口：由根控制面提供，回合 footer（TURN_END）按钮消费。
 *
 * 传入的是该回合真实 TURN_END Entry id；null 表示当前上下文不具备新建分支能力
 * （只读子代理视图、非 Chat 宿主或分支被禁用），footer 不渲染该动作。
 */
export type EntryBranchRequest = ((endEntryId: string) => void) | null

export const EntryBranchContext = createContext<EntryBranchRequest>(null)

export function useEntryBranchRequest(): EntryBranchRequest {
  return useContext(EntryBranchContext)
}

/**
 * 回合 footer 请求到新建分支请求的映射：`endEntryId` 必须是该回合真实的 TURN_END
 * Entry id，作为新分支的 `startEntryId`（`/tree` 的“从此处分支”走同一形状）。
 * 创建仍由目标 pane 的首次输入原子提交，映射阶段不产生任何写操作。
 */
export function branchRequestFromEndEntry({
  endEntryId,
  sessionId,
  sourceLabel,
}: {
  endEntryId: string
  sessionId: string | null
  sourceLabel: string | null
}): BranchRequestInput | null {
  if (sessionId == null || endEntryId.length === 0) {
    return null
  }
  return { sessionId, startEntryId: endEntryId, sourceLabel }
}
