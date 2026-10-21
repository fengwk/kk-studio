import { useState } from 'react'
import { AlertTriangle, RefreshCw } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import type { PendingIssueAction } from '../../pending-action-sidecar'

interface IssueAlertsProps {
  storageLoadError: string | null
  corruptActionError: string | null
  pendingUnknownAction: PendingIssueAction | null
  conflictDetail: string | null
  displayError: string | null
  isActionPending: boolean
  /** 正在编辑且远端版本已前进时可显式确认覆盖风险；null 表示不提供该选项。 */
  onConfirmOverwrite: (() => void) | null
  confirmOverwriteDisabled: boolean
  onDiscardCorruptAction: () => void
  onRetryPendingAction: () => void
  onDiscardPendingAction: () => void
  onReloadFreshData: () => void
}

/**
 * Issue 详情的告警区：侧车读取失败、损坏的挂起记录、结果未知的未决写操作、版本冲突与一般错误。未知写操作只能精确重试或经二次确认放弃。
 */
export function IssueAlerts({
  storageLoadError,
  corruptActionError,
  pendingUnknownAction,
  conflictDetail,
  displayError,
  isActionPending,
  onConfirmOverwrite,
  confirmOverwriteDisabled,
  onDiscardCorruptAction,
  onRetryPendingAction,
  onDiscardPendingAction,
  onReloadFreshData,
}: IssueAlertsProps) {
  const { t } = useI18n()
  const [isDiscardConfirmOpen, setIsDiscardConfirmOpen] = useState(false)

  return (
    <>
      {/* 存储读取异常警告条 */}
      {storageLoadError && (
        <div className="form-error-banner issue-detail-alert issue-detail-alert-danger" role="alert">
          <AlertTriangle size={16} aria-hidden="true" />
          <span>{t('projects.issue.storageLoadError', { error: storageLoadError })}</span>
        </div>
      )}

      {/* 损坏挂起记录警告条 */}
      {corruptActionError !== null && (
        <div className="form-error-banner issue-detail-alert issue-detail-alert-warning" role="alert">
          <div className="issue-detail-alert-heading">
            <AlertTriangle size={16} aria-hidden="true" />
            <strong>{t('projects.issue.corruptActionTitle')}</strong>
          </div>
          <div className="issue-detail-alert-detail">
            {t('projects.issue.corruptActionDetail', { error: corruptActionError })}
          </div>
          <div className="issue-detail-alert-actions">
            <Button danger onClick={onDiscardCorruptAction} disabled={isActionPending} size="compact">
              {t('projects.issue.discardCorruptAction')}
            </Button>
          </div>
        </div>
      )}

      {/* 未决写操作未知状态警告条 (I07) */}
      {pendingUnknownAction && (
        <div className="form-error-banner issue-detail-alert issue-detail-alert-warning" role="alert">
          <div className="issue-detail-alert-heading">
            <AlertTriangle size={16} aria-hidden="true" />
            <strong>{t('projects.issue.unknownPending')}</strong>
          </div>
          <div className="issue-detail-alert-detail">
            {t('projects.issue.pendingActionKind')} <code>{pendingUnknownAction.kind}</code> |
            {t('projects.issue.pendingRequestKey')} <code>{pendingUnknownAction.requestKey}</code> |
            {t('projects.issue.pendingExpectedVersion')} <code>{pendingUnknownAction.expectedVersion}</code>
          </div>
          <div className="issue-detail-alert-actions">
            <Button onClick={onRetryPendingAction} disabled={isActionPending} size="compact">
              {isActionPending ? t('projects.issue.retrying') : t('projects.issue.retryOriginalAction')}
            </Button>
            {!isDiscardConfirmOpen ? (
              <Button
                variant="ghost"
                danger
                onClick={() => setIsDiscardConfirmOpen(true)}
                disabled={isActionPending}
                size="compact"
              >
                {t('projects.issue.discardPending')}
              </Button>
            ) : (
              <div className="issue-detail-alert-actions">
                <span className="issue-detail-alert-discard">
                  {t('projects.issue.discardWarning')}
                </span>
                <Button
                  danger
                  onClick={() => {
                    onDiscardPendingAction()
                    setIsDiscardConfirmOpen(false)
                  }}
                  disabled={isActionPending}
                  size="compact"
                >
                  {t('projects.issue.confirmDiscard')}
                </Button>
                <Button variant="ghost" onClick={() => setIsDiscardConfirmOpen(false)} size="compact">
                  {t('projects.cancel')}
                </Button>
              </div>
            )}
          </div>
        </div>
      )}

      {/* 冲突与错误提示横幅 */}
      {conflictDetail && (
        <div className="form-error-banner issue-detail-alert issue-detail-alert-conflict" role="alert">
          <div className="issue-detail-alert-heading">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>{conflictDetail}</span>
          </div>
          <div className="issue-detail-alert-actions">
            {onConfirmOverwrite && (
              <Button
                onClick={onConfirmOverwrite}
                disabled={confirmOverwriteDisabled}
                size="compact"
                title={t('projects.issue.confirmOverwriteRiskTitle')}
              >
                {t('projects.issue.confirmOverwriteRisk')}
              </Button>
            )}
            <Button variant="ghost" onClick={onReloadFreshData} size="compact">
              <RefreshCw size={12} aria-hidden="true" />
              <span>{t('projects.issue.refreshAndKeepDraft')}</span>
            </Button>
          </div>
        </div>
      )}

      {displayError && (
        <div className="form-error-banner issue-detail-alert" role="alert">
          <AlertTriangle size={16} aria-hidden="true" />
          <span>{displayError}</span>
        </div>
      )}
    </>
  )
}
