import { Bot, Eye, Pencil, User } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { Badge } from '@/shared/ui/data/Badge'
import { EmptyState } from '@/shared/ui/feedback/EmptyState'
import type { IssueDetailDTO } from '../../types'

type Issue = IssueDetailDTO['issue']

/** Spec 只读视图：标题、描述与状态元数据。 */
export function IssueSpecView({
  issue,
  editDisabled,
  onEdit,
}: {
  issue: Issue
  editDisabled: boolean
  onEdit: () => void
}) {
  const { t } = useI18n()
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 className="spec-title">{issue.title}</h2>
        <Button
          variant="ghost"
          onClick={onEdit}
          disabled={editDisabled}
          title={t('projects.issue.editSpecTitle')}
        >
          <Pencil size={14} aria-hidden="true" />
          <span>{t('projects.issue.editSpec')}</span>
        </Button>
      </div>

      <div className="spec-desc-block">
        {issue.description ? (
          <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{issue.description}</div>
        ) : (
          <span style={{ color: 'var(--fg-muted)' }}>{t('projects.issue.noSpecDesc')}</span>
        )}
      </div>

      <div className="spec-meta-grid">
        <div className="meta-item">
          <span className="meta-label">{t('projects.issue.currentStageLabel')}</span>
          <span className="meta-value"><code>{issue.state}</code></span>
        </div>
        <div className="meta-item">
          <span className="meta-label">{t('projects.issue.expectedVersionLabel')}</span>
          <span className="meta-value"><code>{issue.version}</code></span>
        </div>
        {issue.blockedFromState && (
          <div className="meta-item">
            <span className="meta-label">{t('projects.issue.blockedFromStageLabel')}</span>
            <span className="meta-value">{issue.blockedFromState}</span>
          </div>
        )}
        {issue.blockReason && (
          <div className="meta-item" style={{ gridColumn: 'span 2' }}>
            <span className="meta-label">{t('projects.issue.blockReasonLabel')}</span>
            <span className="meta-value" style={{ color: 'var(--danger)' }}>{issue.blockReason}</span>
          </div>
        )}
        {issue.pauseDetail && (
          <div className="meta-item" style={{ gridColumn: 'span 2' }}>
            <span className="meta-label">{t('projects.issue.pauseDetailLabel')}</span>
            <span className="meta-value">{issue.pauseDetail}</span>
          </div>
        )}
      </div>
    </div>
  )
}

/** 活动事实流：区分 Agent、人与系统三类行为者。 */
export function IssueActivityTimeline({ activities }: { activities: IssueDetailDTO['activities'] }) {
  const { t } = useI18n()
  return (
    <div className="activity-timeline">
      {activities.length === 0 ? (
        <EmptyState title={t('projects.issue.emptyActivities')} />
      ) : (
        activities.map((act) => (
          <div key={act.sequence} className={`activity-item kind-${act.kind.toLowerCase()}`}>
            <div className="activity-item-header">
              <span className="activity-badge-kind">{act.kind}</span>
              <span className="activity-actor">
                {act.actorType === 'AGENT' ? (
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                    <Bot size={12} aria-hidden="true" />
                    <span>{act.actorAgentName || 'Agent'}</span>
                  </span>
                ) : act.actorType === 'HUMAN' ? (
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                    <User size={12} aria-hidden="true" />
                    <span>{t('projects.issue.actorHuman')}</span>
                  </span>
                ) : (
                  <span>{t('projects.issue.actorSystem')}</span>
                )}
              </span>
              <span className="activity-time">{act.createdAt}</span>
              <span className="activity-seq">#{act.sequence}</span>
            </div>

            {act.body && (
              <div className="activity-body-text">{act.body}</div>
            )}
          </div>
        ))
      )}
    </div>
  )
}

/** Run 执行事实与区间报告。 */
export function IssueRunsPane({ runs }: { runs: IssueDetailDTO['runs'] }) {
  const { t } = useI18n()
  if (runs.length === 0) {
    return <EmptyState title={t('projects.issue.emptyRuns')} />
  }
  return (
    <div className="runs-list">
      {runs.map((r) => (
        <div key={r.id} className="run-card">
          <div className="run-card-header">
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <span className="run-ordinal">Run #{r.ordinal}</span>
              <Badge tone="info" mono>{r.state}</Badge>
              {r.agentName && (
                <Badge tone="accent">
                  <Bot size={12} aria-hidden="true" />
                  {r.agentName}
                </Badge>
              )}
              <Badge mono>{r.status}</Badge>
            </div>
            <span style={{ fontSize: 'var(--font-size-xs)', color: 'var(--fg-muted)' }}>
              {t('projects.issue.durationRemaining', { ms: r.remainingExecutionMs })}
            </span>
          </div>

          <div className="run-card-details">
            <div className="run-detail-row">
              <span>{t('projects.issue.entryRangeLabel')}</span>
              <code>{r.startEntryId} → {r.endEntryId || t('projects.issue.running')}</code>
            </div>
            {r.finalAnswerEntryId && (
              <div className="run-detail-row">
                <span>{t('projects.issue.finalAnswerEntryLabel')}</span>
                <code>{r.finalAnswerEntryId}</code>
              </div>
            )}
            {r.nextState && (
              <div className="run-detail-row">
                <span>{t('projects.issue.handoverTargetLabel')}</span>
                <strong style={{ color: 'var(--accent)' }}>{r.nextState}</strong>
              </div>
            )}
            {r.error && (
              <div className="run-detail-row" style={{ color: 'var(--danger)' }}>
                <span>{t('projects.issue.errorMessageLabel')}</span>
                <span>{r.error}</span>
              </div>
            )}
          </div>
        </div>
      ))}
    </div>
  )
}

/** 各阶段的运行预算与余量。 */
export function IssueStageBudgetList({ stageBudgets }: { stageBudgets: IssueDetailDTO['stageBudgets'] }) {
  const { t } = useI18n()
  if (stageBudgets.length === 0) {
    return <EmptyState title={t('projects.issue.emptyBudgets')} />
  }
  return (
    <div className="budget-grid">
      {stageBudgets.map((b) => (
        <div key={b.state} className="budget-card">
          <div className="budget-card-title">
            <Badge tone="info" mono>{b.state}</Badge>
            <span>{t('projects.issue.maxRunsBudget', { max: b.maxRuns })}</span>
          </div>
          <div className="budget-stats">
            <div>
              <span>{t('projects.issue.usedRunsLabel')}</span>
              <strong>{b.usedRuns}</strong>
            </div>
            <div>
              <span>{t('projects.issue.remainingRunsLabel')}</span>
              <strong style={{ color: Number(b.remainingRuns) > 0 ? 'var(--accent)' : 'var(--danger)' }}>
                {b.remainingRuns}
              </strong>
            </div>
            <div>
              <span>{t('projects.issue.budgetAfterOrdinalLabel')}</span>
              <code>#{b.budgetAfterOrdinal}</code>
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}

/** Issue 绑定的 Agent 线程，可跳转到对应线程。 */
export function IssueAgentThreadsPane({
  agentThreads,
  onOpenThread,
}: {
  agentThreads: IssueDetailDTO['agentThreads']
  onOpenThread?: (threadId: string) => void
}) {
  const { t } = useI18n()
  if (agentThreads.length === 0) {
    return <EmptyState title={t('projects.issue.emptyAgentThreads')} />
  }
  return (
    <div className="thread-list">
      {agentThreads.map((thread) => (
        <div key={thread.threadId} className="thread-item">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Bot size={16} aria-hidden="true" />
            <strong>{thread.agentName}</strong>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <code>{thread.threadId}</code>
            {onOpenThread && (
              <Button
                variant="ghost"
                onClick={() => onOpenThread(thread.threadId)}
                title={t('projects.issue.openAgentThreadTitle')}
              >
                <Eye size={14} aria-hidden="true" />
                <span>{t('projects.issue.openAction')}</span>
              </Button>
            )}
          </div>
        </div>
      ))}
    </div>
  )
}
