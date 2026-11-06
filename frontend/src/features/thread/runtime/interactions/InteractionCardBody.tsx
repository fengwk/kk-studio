import { Hourglass } from 'lucide-react'
import type { InteractionDTO } from '@/shared/api/generated/contracts'
import { ApprovalCard } from '@/features/thread/runtime/interactions/ApprovalCard'
import { QuestionnaireCard } from '@/features/thread/runtime/interactions/QuestionnaireCard'
import { useI18n } from '@/shared/i18n'

/**
 * 交互卡片主体：等待输入 -> 问卷，等待审批 -> 审批，环境等待 -> 只读状态。
 *
 * 根面板控制区与全局交互中心共用同一主体，因此来源身份（threadId/toolInvocationId）
 * 始终写回原始调用；环境等待是 `(真实执行根, 所需环境)` 聚合，没有可回写的调用主键，
 * 因此只展示等待的环境与调用数，不渲染任何允许/拒绝/作答入口，也不接收 onSuccess。
 *
 * 生成契约用判别联合表达三种条目：每个分支只声明该形态存在的字段，因此无需运行时解码。
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
        interactionId={item.toolInvocationId}
        threadId={item.threadId}
        argumentsJson={item.argumentsJson}
        result={null}
        onSuccess={onSuccess}
      />
    )
  }
  return (
    <ApprovalCard
      threadId={item.threadId}
      invocationId={item.toolInvocationId}
      toolName={item.toolName}
      approval={item.approval}
      argumentsJson={item.argumentsJson}
      onSuccess={onSuccess}
    />
  )
}
