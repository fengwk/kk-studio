import { readWorkspace, type ApprovalDecision } from '../workspace/projection'

// The preview decides against the current Thread snapshot without a server mutation.
export function useApprovalDecision({ threadId, invocationId, onSuccess }: {
  threadId: string; invocationId: string; onSuccess?: () => void;
}) {
  return { busy: false, error: null, isNonRecoverable: false, async decide({ decision, denyReason, decided, mutationReviewMissing }: {
    decision: ApprovalDecision; denyReason: string; decided: boolean; mutationReviewMissing: boolean;
  }) {
    if (decided || (decision === 'ALLOW' && mutationReviewMissing)) return
    for (const pane of readWorkspace()?.panes ?? []) {
      if (pane.kind !== 'thread' || pane.approval?.threadId !== threadId || pane.approval.invocationId !== invocationId) continue
      pane.approval.decide(decision, denyReason.trim())
      onSuccess?.()
      return
    }
  } }
}
