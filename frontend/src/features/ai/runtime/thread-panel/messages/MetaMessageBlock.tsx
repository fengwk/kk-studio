import { Coins, GitBranch } from 'lucide-react'
import type { MetaDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { useEntryBranchRequest } from '@/features/ai/runtime/thread-panel/entry-branch-context'
import { useI18n } from '@/shared/i18n'

/**
 * 特殊 entry / 回合摘要：左对齐，与正文同列，用图标区分。
 *
 * 当消息绑定到真正的 TURN_END（`endEntryId` 非空）时，它是一次已关闭回合的 footer：
 * 即使没有 usage 文本，也必须展示结束信息与「从此处分支」入口。入口走会话里唯一的
 * 分支 Context（由根控制面注入，与 `/tree` 共用同一条命名/目标流程）；
 * 无该能力时（只读子代理视图、非 Chat 宿主、分支禁用）不渲染按钮，绝不在此自造流程。
 */
export function MetaMessageBlock({ message }: { message: MetaDialogueMessage }) {
  const { t } = useI18n()
  const requestBranch = useEntryBranchRequest()
  const title = message.kind === 'turn_usage' && message.text
    ? `${message.text}\n${t('ai.runtime.usage.metaTooltip')}`
    : undefined
  const endEntryId = message.endEntryId ?? null
  const canBranch = endEntryId != null && requestBranch != null
  const branchLabel = t('ai.chat.branch.fromHere')
  return (
    <section
      className={`thread-block thread-block-meta kind-${message.kind}`}
      data-meta-kind={message.kind}
      data-turn-end={endEntryId != null ? endEntryId : undefined}
    >
      <div className="thread-meta-row">
        <span className="thread-meta-icon" aria-hidden="true">
          <Coins />
        </span>
        <div className="thread-block-body thread-meta-text" title={title}>
          {message.text}
        </div>
        {canBranch ? (
          <button
            type="button"
            className="ghost-inline-btn thread-meta-branch-btn"
            aria-label={branchLabel}
            title={branchLabel}
            data-testid="thread-turn-end-branch"
            onClick={() => requestBranch(endEntryId)}
          >
            <GitBranch size={12} aria-hidden="true" />
          </button>
        ) : null}
      </div>
    </section>
  )
}
