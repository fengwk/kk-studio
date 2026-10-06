import type { InteractionDTO } from '@/shared/api/contracts/ai-interaction'
import { ApprovalCard } from '@/features/ai/runtime/interactions/ApprovalCard'
import { QuestionnaireCard } from '@/features/ai/runtime/interactions/QuestionnaireCard'

/**
 * 交互卡片主体：等待输入 -> 问卷，等待审批 -> 审批。
 *
 * 根面板控制区与全局交互中心共用同一主体，因此来源身份（threadId/interactionId）
 * 始终写回原始调用；其它状态只展示原始载荷，不提供隐式操作。
 */
export function InteractionCardBody({
  item,
  onSuccess,
}: {
  item: InteractionDTO
  onSuccess?: () => void
}) {
  if (item.status === 'WAITING_INPUT') {
    return (
      <QuestionnaireCard
        interactionId={item.interactionId}
        threadId={item.threadId}
        argumentsJson={item.argumentsJson}
        resultJson={null}
        onSuccess={onSuccess}
      />
    )
  }
  if (item.status === 'WAITING_APPROVAL') {
    return (
      <ApprovalCard
        threadId={item.threadId}
        invocationId={item.interactionId}
        toolName={item.toolName}
        approvalJson={item.approvalJson}
        argumentsJson={item.argumentsJson}
        onSuccess={onSuccess}
      />
    )
  }
  return <pre className="interaction-raw-pre">{item.argumentsJson}</pre>
}
