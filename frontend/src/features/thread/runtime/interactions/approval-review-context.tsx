/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, useMemo, type ReactNode } from 'react'
import type { InteractionDTO } from '@/shared/api/generated/contracts'

/**
 * 本 pane 内“审批卡作为冻结审查唯一展示位”的精确调用集合。
 *
 * 时间线工具卡只按**同一调用 ID** 是否在集合内决定是否让出 diff：审批卡分页未加载、
 * 首屏仍在读取、根控制区被隐藏，或该视图根本没有任何审批卡（子 Agent 只读面板、
 * 待处理收件箱是另一个页面）时集合为空，工具卡必须继续交出 diff。因此这里传的是
 * 真实渲染审批卡的调用 ID，而不是“审批区已打开”这类布尔值。
 */
const ApprovalReviewContext = createContext<ReadonlySet<string> | null>(null)

export function ApprovalReviewProvider({
  toolInvocationIds,
  children,
}: {
  /** 当前实际渲染审批卡的调用 ID；问卷与环境等待不是审批位。 */
  toolInvocationIds: readonly string[]
  children: ReactNode
}) {
  const ids = useMemo(() => new Set(toolInvocationIds), [toolInvocationIds])
  return (
    <ApprovalReviewContext.Provider value={ids}>
      {children}
    </ApprovalReviewContext.Provider>
  )
}

/** 该调用是否已在同一 pane 的审批卡里展示；没有 Provider 时永远为否。 */
export function useApprovalReviewVisibleElsewhere(toolInvocationId: string | undefined): boolean {
  const ids = useContext(ApprovalReviewContext)
  return ids != null && toolInvocationId != null && ids.has(toolInvocationId)
}

/** 交互条目里真正会渲染审批卡的调用：环境等待是聚合项，问卷渲染的是问卷卡。 */
export function visibleApprovalInvocationIds(items: readonly InteractionDTO[]): string[] {
  return items.flatMap((item) => (item.type === 'APPROVAL' ? [item.toolInvocationId] : []))
}
