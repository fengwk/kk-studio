import { AlertCircle, ArrowRight, Play, RotateCcw, Square } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'

interface IssueActionBarProps {
  isBlocked: boolean
  isDone: boolean
  isPaused: boolean
  isWriteBlocked: boolean
  /** 当前阶段在工作流里声明的可转移目标。 */
  availableNextStates: string[]
  onResume: () => void
  onTransition: (toState: string) => void
  onRecover: () => void
  onOpenBlock: () => void
  onOpenStop: () => void
  onReopen: () => void
}

/**
 * Issue 详情顶部的生命周期动作条：继续、流转、阻塞/恢复、终止与重开。
 *
 * <p>展示哪个动作只由当前状态决定：暂停中显示继续，阻塞中显示恢复，未完成时显示阻塞与终止，已完成时显示重开。
 */
export function IssueActionBar({
  isBlocked,
  isDone,
  isPaused,
  isWriteBlocked,
  availableNextStates,
  onResume,
  onTransition,
  onRecover,
  onOpenBlock,
  onOpenStop,
  onReopen,
}: IssueActionBarProps) {
  const { t } = useI18n()
  return (
    <div className="issue-actions-bar">
      <div className="issue-actions-left">
        {isPaused && (
          <Button size="compact" disabled={isWriteBlocked} onClick={onResume}>
            <Play size={13} aria-hidden="true" />
            <span>{t('projects.issue.resumeAction')}</span>
          </Button>
        )}

        {!isBlocked && availableNextStates.map((toState) => (
          <Button
            key={toState}
            size="compact"
            disabled={isWriteBlocked}
            onClick={() => onTransition(toState)}
            title={t('projects.issue.transitionStateTo', { state: toState })}
          >
            <span>{t('projects.issue.transitionToState', { state: toState })}</span>
            <ArrowRight size={12} aria-hidden="true" />
          </Button>
        ))}

        {isBlocked ? (
          <Button
            size="compact"
            disabled={isWriteBlocked}
            onClick={onRecover}
            title={t('projects.issue.recoverActionTitle')}
          >
            <RotateCcw size={13} aria-hidden="true" />
            <span>{t('projects.issue.recoverExecution')}</span>
          </Button>
        ) : !isDone && (
          <Button
            size="compact"
            variant="ghost"
            disabled={isWriteBlocked}
            onClick={onOpenBlock}
            title={t('projects.issue.blockActionTitle')}
          >
            <AlertCircle size={13} aria-hidden="true" />
            <span>{t('projects.state.blocked')}</span>
          </Button>
        )}

        {!isDone && (
          <Button
            size="compact"
            variant="ghost"
            disabled={isWriteBlocked}
            onClick={onOpenStop}
            title={t('projects.issue.stopActionTitle')}
          >
            <Square size={13} aria-hidden="true" />
            <span>{t('projects.issue.stopAction')}</span>
          </Button>
        )}

        {isDone && (
          <Button
            size="compact"
            disabled={isWriteBlocked}
            onClick={onReopen}
            title={t('projects.issue.actionReopenTitle')}
          >
            <RotateCcw size={13} aria-hidden="true" />
            <span>{t('projects.issue.reopen')}</span>
          </Button>
        )}
      </div>
    </div>
  )
}
