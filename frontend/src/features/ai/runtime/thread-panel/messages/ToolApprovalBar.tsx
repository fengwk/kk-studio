import { useState } from 'react'
import { LoaderCircle } from 'lucide-react'
import type { ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import type {
  ToolApprovalDecision,
  ToolRenderContext,
} from '@/features/ai/runtime/thread-panel/messages/tool-message-view'
import { useI18n } from '@/shared/i18n'

/**
 * 待决审批闸门：只展示持久化决策或当前可操作的审批请求。
 *
 * 决策在点击处理器内同步进入本地 pending——不等待 mutation/服务端回执——随即禁用
 * 两个按钮并展示 live region 指示；请求 settle 后释放。pending 按 invocation 归属：
 * 权威 snapshot 换掉本条审批时，旧的 settle 绝不会影响新审批。
 */
export function ToolApprovalBar({
  context,
  message,
  onDecideApproval,
}: {
  context: ToolRenderContext
  message: ToolDialogueMessage
  onDecideApproval?: (
    message: ToolDialogueMessage,
    decision: ToolApprovalDecision,
  ) => void | Promise<void>
}) {
  const { t } = useI18n()
  const approval = context.approval
  // 决策身份必须是 invocation：宿主可能复用同一审批条渲染另一条 invocation。
  const invocationKey = message.invocationId ?? message.id
  const [decidingKey, setDecidingKey] = useState<string | null>(null)
  if (!approval?.required) {
    return null
  }
  if (approval.decision != null) {
    const decidedText =
      approval.decision === 'ALLOWED'
        ? t('ai.runtime.approval.allowed')
        : t('ai.runtime.approval.denied')
    return (
      <div className="thread-tool-approval is-decided" aria-label={t('ai.runtime.approval.title')}>
        <span className="thread-tool-approval-text">
          {decidedText}
          {approval.reason ? ` — ${approval.reason}` : ''}
        </span>
      </div>
    )
  }
  if (!onDecideApproval) {
    return null
  }
  const pending = context.approvalPending === true || decidingKey === invocationKey
  const decide = (decision: ToolApprovalDecision): void => {
    // 按钮已禁用时不再提交：pending 期间重复点击不会产生第二个请求。
    if (pending) {
      return
    }
    setDecidingKey(invocationKey)
    const settle = () => setDecidingKey((current) => (current === invocationKey ? null : current))
    // 成功与失败都释放本地 pending（onFulfilled/onRejected 同时给出，杜绝 unhandled rejection）；
    // 失败本身仍由宿主的既有错误通道呈现。
    void Promise.resolve(onDecideApproval(message, decision)).then(settle, settle)
  }
  return (
    <div className="thread-tool-approval" role="group" aria-label={t('ai.runtime.approval.title')}>
      <span className="thread-tool-approval-text">{t('ai.runtime.approval.requested')}</span>
      <button
        type="button"
        className="btn-primary"
        disabled={pending}
        onClick={() => decide('ALLOW')}
      >
        {t('ai.runtime.approval.allow')}
      </button>
      <button
        type="button"
        className="ghost-btn danger"
        disabled={pending}
        onClick={() => decide('DENY')}
      >
        {t('ai.runtime.approval.deny')}
      </button>
      {/* 按钮禁用状态不会产生播报，pending 必须由 live region 文本补足。 */}
      {pending ? (
        <span className="thread-tool-approval-pending" role="status">
          <LoaderCircle className="thread-tool-approval-spinner" aria-hidden="true" />
          {t('ai.runtime.approval.deciding')}
        </span>
      ) : null}
    </div>
  )
}
