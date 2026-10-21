import { useEffect, useRef, type MutableRefObject } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createDecisionId } from '@/features/chat/command-batch-plan'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

interface ThreadApprovalsInput {
  threadId: string
  bindingEpochRef: MutableRefObject<number>
  isStillBound: (originThreadId: string, originEpoch: number) => boolean
  setActionError: (message: string | null) => void
  clearConflict: () => void
  reportMutationError: (error: unknown, fallbackKey: string, targetThreadId?: string) => void
}

/**
 * 待决 ToolInvocation 的审批/拒绝。decision id 在重试「同一个」决策期间保持稳定；切换决策（ALLOW -&gt; DENY）铸造新 id——之前的决策已落地时
 * 服务端返回 409，刷新后即可呈现该结果。决策落在哪个 Thread 就刷新哪个 Thread 的 snapshot（子 Thread 审批投影到它自己的 ToolInvocation）。
 */
export function useThreadApprovals({
  threadId,
  bindingEpochRef,
  isStillBound,
  setActionError,
  clearConflict,
  reportMutationError,
}: ThreadApprovalsInput) {
  const queryClient = useQueryClient()
  const decisionIdByInvocationRef = useRef(new Map<string, string>())

  useEffect(() => {
    decisionIdByInvocationRef.current.clear()
  }, [threadId])

  const approvalMutation = useMutation({
    mutationFn: ({
      targetThreadId,
      invocationId,
      decision,
      decisionId,
    }: {
      targetThreadId: string
      invocationId: string
      decision: 'ALLOW' | 'DENY'
      decisionId: string
      operationBindingThreadId: string
      operationEpoch: number
    }) =>
      harnessService.decideApproval(targetThreadId, invocationId, {
        decision,
        decisionId,
        reason: null,
      }),
    onSuccess: async (_result, variables) => {
      if (isStillBound(variables.operationBindingThreadId, variables.operationEpoch)) {
        clearConflict()
      }
      const key = `${variables.targetThreadId}:${variables.invocationId}:${variables.decision}`
      if (decisionIdByInvocationRef.current.get(key) === variables.decisionId) {
        decisionIdByInvocationRef.current.delete(key)
      }
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: queryKeys.threads.snapshot(variables.targetThreadId),
        }),
        queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
      ])
    },
  })

  /**
   * targetThreadId 默认指向本 controller 绑定的 Thread（Bound transcript 的既有审批）；子 Thread 的审批（Task widget）必须显式传入
   * status.threadId。
   */
  function decideApproval(
    invocationId: string,
    decision: 'ALLOW' | 'DENY',
    targetThreadId: string = threadId,
  ): Promise<void> {
    setActionError(null)
    clearConflict()
    const operationBindingThreadId = threadId
    const operationEpoch = bindingEpochRef.current
    // 子 Thread 的审批独立寻址，未知结果重试复用同一个 decisionId。
    const key = `${targetThreadId}:${invocationId}:${decision}`
    const decisionId = decisionIdByInvocationRef.current.get(key) ?? createDecisionId()
    decisionIdByInvocationRef.current.set(key, decisionId)
    return approvalMutation
      .mutateAsync({
        targetThreadId,
        invocationId,
        decision,
        decisionId,
        operationBindingThreadId,
        operationEpoch,
      })
      .then(() => undefined)
      .catch((error: unknown) => {
        if (isStillBound(operationBindingThreadId, operationEpoch)) {
          reportMutationError(error, 'ai.runtime.action.approvalFailed', targetThreadId)
        }
      })
  }

  return { decideApproval, approvalPending: approvalMutation.isPending }
}
