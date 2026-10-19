import { AlertCircle, Clock, RotateCcw, ShieldAlert, XCircle } from 'lucide-react'
import { Badge } from '@/shared/ui/data/Badge'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import type { ProjectIssueSnapshotDTO } from '../types'

export interface IssueCardProps {
  item: ProjectIssueSnapshotDTO
  availableNextStates?: string[]
  onClick: () => void
  onTransition?: (toState: string) => void
  onBlock?: () => void
  onRecover?: () => void
  onReopen?: () => void
  onResolveUnknown?: () => void
}

/**
 * 看板 Issue 卡：非交互外壳 + 显式标题导航按钮，快捷动作是彼此平级的共享 Button，
 * 不在卡片级 button 里嵌套动作，也不使用 feature 私有按钮皮肤。
 */
export function IssueCard({
  item,
  availableNextStates = [],
  onClick,
  onTransition,
  onBlock,
  onRecover,
  onReopen,
  onResolveUnknown,
}: IssueCardProps) {
  const { t } = useI18n()
  const { issue, currentOrLatestRun } = item
  const isBlocked = issue.state === 'BLOCKED' || Boolean(issue.blockedFromState)
  const isUnknown = issue.pauseReason === 'UNKNOWN' || currentOrLatestRun?.status === 'UNKNOWN'
  const isWaiting = currentOrLatestRun?.status === 'WAITING'
  const isFailed = currentOrLatestRun?.status === 'FAILED'
  const isDone = issue.state === 'DONE'

  return (
    <article
      className={`issue-card ${isBlocked ? 'is-blocked' : ''} ${isUnknown ? 'is-unknown' : ''}`}
      data-testid={`issue-card-${issue.id}`}
    >
      <div className="issue-card-header">
        <div className="issue-card-heading">
          <span className="issue-number">#{issue.number}</span>
          <Badge tone="info" mono>{issue.state}</Badge>
        </div>

        <div className="issue-badges-row">
          {isBlocked && (
            <Badge
              tone="danger"
              title={t('projects.issue.blockedBadgeTitle', {
                reason: issue.blockReason || t('projects.issue.reasonUnspecified'),
              })}
            >
              <AlertCircle size={12} aria-hidden="true" />
              BLOCKED
            </Badge>
          )}
          {isUnknown && (
            <Badge tone="info" title={t('projects.issue.unknownBadgeTitle')}>
              <ShieldAlert size={12} aria-hidden="true" />
              UNKNOWN
            </Badge>
          )}
          {issue.pauseReason === 'USER' && (
            <Badge tone="warning" title={t('projects.issue.pausedBadgeTitle')}>
              PAUSED
            </Badge>
          )}
          {issue.pauseReason === 'ERROR' && (
            <Badge tone="danger" title={t('projects.issue.errorBadgeTitle')}>
              ERROR
            </Badge>
          )}
          {isWaiting && (
            <Badge tone="warning" title={t('projects.issue.waitingBadgeTitle')}>
              <Clock size={12} aria-hidden="true" />
              WAITING
            </Badge>
          )}
          {isFailed && (
            <Badge tone="danger" title={t('projects.issue.failedBadgeTitle')}>
              <XCircle size={12} aria-hidden="true" />
              FAILED
            </Badge>
          )}
          {issue.archivedAt && (
            <Badge tone="neutral">{t('projects.archived')}</Badge>
          )}
        </div>
      </div>

      <button
        type="button"
        className="issue-card-title-btn"
        onClick={onClick}
        title={issue.title}
        aria-label={`Issue #${issue.number} ${issue.title}`}
      >
        <span className="issue-title">{issue.title}</span>
      </button>

      {currentOrLatestRun && (
        <div className="issue-card-meta-row">
          <Badge tone="info">
            #{currentOrLatestRun.ordinal} {currentOrLatestRun.state}
            {currentOrLatestRun.agentName ? ` · ${currentOrLatestRun.agentName}` : ''}
            {` · ${currentOrLatestRun.status}`}
          </Badge>
        </div>
      )}

      {issue.blockReason && isBlocked && (
        <p className="issue-block-reason" title={issue.blockReason}>
          {t('projects.issue.blockReasonPrefix', { reason: issue.blockReason })}
        </p>
      )}

      {/* 快捷操作区：与标题导航平级的共享按钮，不冒泡成卡片导航 */}
      <div className="issue-card-quick-actions">
        {isUnknown && onResolveUnknown && (
          <Button
            size="compact"
            variant="ghost"
            danger
            onClick={onResolveUnknown}
            title={t('projects.issue.actionVerifyTitle')}
          >
            <ShieldAlert size={12} aria-hidden="true" />
            <span>{t('projects.issue.actionVerify')}</span>
          </Button>
        )}

        {isBlocked && onRecover && (
          <Button
            size="compact"
            onClick={onRecover}
            title={t('projects.issue.actionRecoverTitle', {
              state: issue.blockedFromState || t('projects.issue.originalStage'),
            })}
          >
            <RotateCcw size={12} aria-hidden="true" />
            <span>{t('projects.issue.actionRecover')}</span>
          </Button>
        )}

        {isDone && onReopen && (
          <Button
            size="compact"
            variant="ghost"
            onClick={onReopen}
            title={t('projects.issue.actionReopenTitle')}
          >
            <RotateCcw size={12} aria-hidden="true" />
            <span>{t('projects.issue.actionReopen')}</span>
          </Button>
        )}

        {!isBlocked && !isDone && onBlock && (
          <Button
            size="compact"
            variant="ghost"
            onClick={onBlock}
            title={t('projects.issue.actionBlockTitle')}
          >
            <AlertCircle size={12} aria-hidden="true" />
            <span>{t('projects.issue.actionBlock')}</span>
          </Button>
        )}

        {/* 根据工作流 next 转移白名单提供的快捷流转 */}
        {!isBlocked && availableNextStates.length > 0 && onTransition && (
          <div className="issue-card-transitions">
            {availableNextStates.map((nextState) => (
              <Button
                key={nextState}
                size="compact"
                onClick={() => onTransition(nextState)}
                title={t('projects.issue.transitionTo', { state: nextState })}
              >
                <span>→ {nextState}</span>
              </Button>
            ))}
          </div>
        )}
      </div>
    </article>
  )
}
