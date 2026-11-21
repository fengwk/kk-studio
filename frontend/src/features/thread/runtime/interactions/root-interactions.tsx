/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, useMemo, type ReactNode } from 'react'
import {
  ApprovalReviewProvider,
  visibleApprovalInvocationIds,
} from '@/features/thread/runtime/interactions/approval-review-context'
import {
  useInteractionsController,
  type UseInteractionsControllerResult,
} from '@/features/thread/runtime/interactions/useInteractionsController'

const RootInteractionsContext = createContext<UseInteractionsControllerResult | null>(null)

/**
 * 执行根 pane 的交互供给：唯一持有 interaction controller，并把它实际渲染的审批卡
 * 调用 ID 交给整棵 pane。
 *
 * 时间线（transcript）与根控制区是同一个 pane 下的兄弟节点，只有在这里包一层，
 * 工具卡才能知道「同一调用的审批卡就在这个 pane 里可见」。`approvalsVisible` 为否时
 * （Debug 主视图接管、根控制区隐藏）审批区虽然仍挂载但不可见，此时不下发任何调用 ID，
 * 让工具卡继续保留 diff。
 */
export function RootInteractionsProvider({
  rootThreadId,
  approvalsVisible,
  children,
}: {
  /** 执行根：交互条目按该根过滤。 */
  rootThreadId: string
  approvalsVisible: boolean
  children: ReactNode
}) {
  const interactions = useInteractionsController(rootThreadId)
  const approvalIds = useMemo(
    () => (approvalsVisible ? visibleApprovalInvocationIds(interactions.items) : []),
    [approvalsVisible, interactions.items],
  )
  return (
    <RootInteractionsContext.Provider value={interactions}>
      <ApprovalReviewProvider toolInvocationIds={approvalIds}>
        {children}
      </ApprovalReviewProvider>
    </RootInteractionsContext.Provider>
  )
}

/** 根控制区读取同一份交互条目；不在执行根 pane 内时没有可渲染的交互。 */
export function useRootInteractions(): UseInteractionsControllerResult | null {
  return useContext(RootInteractionsContext)
}
