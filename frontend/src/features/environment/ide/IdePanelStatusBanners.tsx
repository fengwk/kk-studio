import { Button } from '@/shared/ui/controls/Button'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import type { ApplicationEventConnectionState } from '@/shared/app-events/connection'
import type { IdeViewState } from '@/features/environment/ide/ide-view-state'
import type { IdeBrowserSessionStatus } from '@/features/environment/ide/ide-browser-session'

/** backoff 原因到文案的映射：结构化 reason 驱动，不解析消息文本。 */
function connectionBackoffMessageKey(
  cause: 'transport-lost' | 'handshake-timeout' | 'liveness-timeout' | 'server-restart',
): TranslationKey {
  switch (cause) {
    case 'handshake-timeout': {
      return 'ai.ide.backoffHandshake'
    }
    case 'liveness-timeout': {
      return 'ai.ide.backoffLiveness'
    }
    case 'server-restart': {
      return 'ai.ide.backoffServerRestart'
    }
    default: {
      return 'ai.ide.backoffReconnecting'
    }
  }
}

/** stopped 原因到文案的映射：protocol/policy 终止明确提示需要人工介入。 */
function connectionStopMessageKey(
  cause: 'manual' | 'protocol' | 'policy',
): TranslationKey {
  switch (cause) {
    case 'protocol': {
      return 'ai.ide.stoppedProtocol'
    }
    case 'policy': {
      return 'ai.ide.stoppedPolicy'
    }
    default: {
      return 'ai.ide.stoppedManual'
    }
  }
}

interface IdePanelStatusBannersProps {
  storageUnavailable: boolean
  sessionStatus: IdeBrowserSessionStatus
  connectionState: ApplicationEventConnectionState
  selectedView: IdeViewState | null
  selectedEnvironmentId: string | null
  viewBusy: boolean
  claimError: string | undefined
  closeError: string | undefined
  terminalError: string | undefined
  onOpenNewView: (environmentId: string) => void
  onRetryClaim: (environmentId: string) => void
  onDismissCloseError: (environmentId: string) => void
  commandMessage: (code: string | undefined, fallback: TranslationKey) => string
}

/**
 * 面板顶部的状态横幅栈。
 *
 * <p>浏览器事件连接状态与 IDE 会话状态是两个事实，分别呈现；terminal 停止不显示持续重连。
 * 横幅只描述状态，不发命令——除三处需要用户介入的恢复入口（新视图、重试、关闭错误）。
 */
export function IdePanelStatusBanners({
  storageUnavailable,
  sessionStatus,
  connectionState,
  selectedView,
  selectedEnvironmentId,
  viewBusy,
  claimError,
  closeError,
  terminalError,
  onOpenNewView,
  onRetryClaim,
  onDismissCloseError,
  commandMessage,
}: IdePanelStatusBannersProps) {
  const { t } = useI18n()

  return (
    <>
      {storageUnavailable ? (
        <div className="ide-panel-warning-banner" role="status" data-testid="ide-storage-unavailable">
          {t('ai.ide.storageUnavailable')}
        </div>
      ) : null}
      {sessionStatus !== 'ready' && sessionStatus !== 'cloned' && sessionStatus !== 'acquiring'
        && sessionStatus !== 'suspended' ? (
        <div className="ide-panel-warning-banner" role="alert" data-testid="ide-session-unavailable">
          {t(sessionStatus === 'unsupported'
            ? 'ai.ide.sessionUnsupported'
            : sessionStatus === 'conflict'
              ? 'ai.ide.sessionConflict'
              : 'ai.ide.sessionUnavailable')}
        </div>
      ) : null}
      {connectionState.reason?.kind === 'connecting' ? (
        <div className="ide-panel-warning-banner" role="status" data-testid="ide-connecting">
          {t('ai.ide.connecting')}
        </div>
      ) : connectionState.reason?.kind === 'backoff' ? (
        <div className="ide-panel-warning-banner" role="status" data-testid="ide-reconnecting">
          {t(connectionBackoffMessageKey(connectionState.reason.cause))}
        </div>
      ) : connectionState.reason?.kind === 'stopped' ? (
        <div className="ide-panel-error-banner" role="alert" data-testid="ide-connection-stopped">
          {t(connectionStopMessageKey(connectionState.reason.cause))}
        </div>
      ) : null}
      {selectedView?.status === 'waiting-ready' ? (
        <div className="ide-panel-warning-banner" role="status" data-testid="ide-waiting-ready">
          {t('ai.ide.waitingReady')}
        </div>
      ) : null}
      {selectedView?.status === 'reconciling' ? (
        <div className="ide-panel-warning-banner" role="status" data-testid="ide-reconciling">
          {t('ai.ide.reconciling')}
        </div>
      ) : null}
      {selectedView?.status === 'detached' ? (
        <div className="ide-panel-recovery-banner" role="status" data-testid="ide-view-detached">
          <span>{t('ai.ide.viewOwnedByOther')}</span>
          <Button
            variant="ghost"
            size="compact"
            onClick={() => {
              if (selectedEnvironmentId != null) {
                onOpenNewView(selectedEnvironmentId)
              }
            }}
            data-testid="ide-open-new-view-btn"
          >
            {t('ai.ide.openNewView')}
          </Button>
        </div>
      ) : null}
      {selectedView?.status === 'gone' ? (
        <div className="ide-panel-recovery-banner" role="alert" data-testid="ide-view-gone">
          <span>{t('ai.ide.viewGone')}</span>
          <Button
            variant="ghost"
            size="compact"
            onClick={() => {
              if (selectedEnvironmentId != null) {
                onOpenNewView(selectedEnvironmentId)
              }
            }}
            data-testid="ide-open-new-view-btn"
          >
            {t('ai.ide.openNewView')}
          </Button>
        </div>
      ) : null}
      {selectedView != null
      && selectedView.entryUrl != null
      && viewBusy ? (
        <div className="ide-panel-warning-banner" role="status" data-testid="ide-rebinding">
          {t('ai.ide.reconciling')}
        </div>
      ) : null}
      {selectedView?.status === 'error' ? (
        <div className="ide-panel-error-banner" role="alert" data-testid="ide-error-banner">
          <span>{claimError === 'IDE_CLOSING' ? t('ai.ide.cleanupPending') : claimError || t('ai.ide.error')}</span>
          <Button
            variant="ghost"
            size="compact"
            onClick={() => {
              if (selectedEnvironmentId != null) {
                onRetryClaim(selectedEnvironmentId)
              }
            }}
            data-testid="ide-retry-claim-btn"
          >
            {t('ai.ide.retryClaim')}
          </Button>
        </div>
      ) : null}
      {closeError ? (
        <div className="ide-panel-error-banner" role="alert" data-testid="ide-close-error">
          <span>{commandMessage(closeError, 'ai.ide.error')}</span>
          <Button
            variant="ghost"
            size="compact"
            onClick={() => {
              if (selectedEnvironmentId != null) {
                onDismissCloseError(selectedEnvironmentId)
              }
            }}
          >
            {t('ai.ide.dismiss')}
          </Button>
        </div>
      ) : null}
      {terminalError ? (
        <div className="ide-panel-terminal-error" role="alert" data-testid="ide-terminal-error">
          {commandMessage(terminalError, 'ai.ide.terminalFailed')}
        </div>
      ) : null}
    </>
  )
}
