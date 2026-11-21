import { AlertCircle, ArrowRight, Clock, RotateCcw, XCircle } from 'lucide-react'
import { Badge } from '@/shared/ui/data/Badge'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import type { IssueRunStatus, ProjectIssueSnapshotDTO } from '@/shared/api/generated/contracts'

/** Run 状态按执行事实命名；卡片不直接展示内部枚举码。 */
const RUN_STATUS_LABEL_KEY: Record<IssueRunStatus, TranslationKey> = {
  RUNNING: 'projects.issue.running',
  WAITING: 'projects.issue.runStatus.waiting',
  COMPLETED: 'projects.issue.runStatus.completed',
  FAILED: 'projects.issue.runStatus.failed',
  CANCELLED: 'projects.issue.runStatus.cancelled',
}

export interface IssueCardProps {
  item: ProjectIssueSnapshotDTO
  /** 工作流阶段编码到名称的映射；卡片已在所属列中，阶段只按名称出现。 */
  stateNames: ReadonlyMap<string, string>
  availableNextStates?: string[]
  onClick: () => void
  onTransition?: (toState: string) => void
  onBlock?: () => void
  onRecover?: () => void
  onReopen?: () => void
}

/**
 * 看板 Issue 卡：非交互外壳 + 显式标题导航按钮，快捷动作是彼此平级的共享 Button，
 * 不在卡片级 button 里嵌套动作，也不使用 feature 私有按钮皮肤。
 *
 * <p>卡片所属列已经表达当前阶段，因此卡头只留编号；阶段编码只作为诊断信息保留在提示里，
 * 动作用目标阶段名称表达，外观是次级/无框按钮，主动作留在页头。
 */
export function IssueCard({
  item,
  stateNames,
  availableNextStates = [],
  onClick,
  onTransition,
  onBlock,
  onRecover,
  onReopen,
}: IssueCardProps) {
  const { t } = useI18n()
  const { issue, currentOrLatestRun } = item
  const isBlocked = issue.state === 'BLOCKED' || Boolean(issue.blockedFromState)
  const isWaiting = currentOrLatestRun?.status === 'WAITING'
  const isFailed = currentOrLatestRun?.status === 'FAILED'
  const isDone = issue.state === 'DONE'

  const stateName = (state: string) => stateNames.get(state) ?? state

  // Run 摘要：只在与当前阶段不同时补出该 Run 的阶段名称，避免重复显示当前阶段。
  const runStage =
    currentOrLatestRun && currentOrLatestRun.state !== issue.state
      ? stateName(currentOrLatestRun.state)
      : null
  const runFacts = currentOrLatestRun
    ? [
      `#${currentOrLatestRun.ordinal}`,
      runStage,
      currentOrLatestRun.agentName,
      t(RUN_STATUS_LABEL_KEY[currentOrLatestRun.status]),
    ]
      .filter(Boolean)
      .join(' · ')
    : null

  return (
    <article
      className={`issue-card ${isBlocked ? 'is-blocked' : ''}`}
      data-testid={`issue-card-${issue.id}`}
    >
      <div className="issue-card-header">
        <div className="issue-card-heading">
          <span className="issue-number">#{issue.number}</span>
        </div>

        <div className="issue-badges-row">
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

      {runFacts && <p className="issue-card-meta">{runFacts}</p>}

      {issue.blockReason && isBlocked && (
        <p className="issue-block-reason" title={issue.blockReason}>
          {t('projects.issue.blockReasonPrefix', { reason: issue.blockReason })}
        </p>
      )}

      {/* 快捷操作区：与标题导航平级的共享按钮，不冒泡成卡片导航 */}
      <div className="issue-card-quick-actions">
        {isBlocked && onRecover && (
          <Button
            size="compact"
            variant="ghost"
            onClick={onRecover}
            title={t('projects.issue.actionRecoverTitle', {
              state: issue.blockedFromState
                ? stateName(issue.blockedFromState)
                : t('projects.issue.originalStage'),
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

        {/* 根据工作流 next 转移白名单提供的快捷流转，按钮写明目标阶段名称 */}
        {!isBlocked && availableNextStates.length > 0 && onTransition && (
          <div className="issue-card-transitions">
            {availableNextStates.map((nextState) => (
              <Button
                key={nextState}
                size="compact"
                variant="ghost"
                onClick={() => onTransition(nextState)}
              >
                <span>{t('projects.issue.transitionToState', { state: stateName(nextState) })}</span>
                <ArrowRight size={12} aria-hidden="true" />
              </Button>
            ))}
          </div>
        )}
      </div>
    </article>
  )
}
