import { Hourglass } from 'lucide-react'
import type { InteractionDTO } from '@/shared/api/contracts/ai-interaction'
import { ApprovalCard } from '@/features/ai/runtime/interactions/ApprovalCard'
import { QuestionnaireCard } from '@/features/ai/runtime/interactions/QuestionnaireCard'
import { useI18n } from '@/shared/i18n'

/**
 * 交互卡片主体：等待输入 -> 问卷，等待审批 -> 审批，环境等待 -> 只读状态。
 *
 * 根面板控制区与全局交互中心共用同一主体，因此来源身份（threadId/interactionId）
 * 始终写回原始调用；环境等待是 `(真实执行根, 所需环境)` 聚合，没有可回写的调用主键，
 * 因此只展示等待的环境与调用数，不渲染任何允许/拒绝/作答入口，也不接收 onSuccess。
 */
export function InteractionCardBody({
  item,
  onSuccess,
}: {
  item: InteractionDTO
  onSuccess?: () => void
}) {
  const { t } = useI18n()
  if (item.type === 'ENVIRONMENT_WAIT') {
    return (
      <div className="interaction-environment-wait" role="status">
        <Hourglass size={14} aria-hidden="true" />
        <span>
          {t('ai.interaction.environmentWaitingCount', {
            count: item.waitingCount,
            environmentName: item.environmentName,
          })}
        </span>
      </div>
    )
  }
  if (item.type === 'INPUT') {
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
