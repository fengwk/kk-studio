import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'

export function CanvasOverlays() {
  const {
    state,
    dismissDraft,
    retryDraft,
    retryDraftPersist,
    retryRecovery,
    dismissConflictMessage,
    saveDraftAsNewNode,
  } = useCanvasRuntime()
  const { t } = useI18n()
  const uploads = Object.entries(state.uploadProgress)

  const conflictedEntries = Object.entries(state.drafts).filter(
    ([, draft]) => Boolean(draft.conflict),
  )

  const isRecoverableConflict = Boolean(
    state.conflictMessage && (
      state.conflictMessage.includes('未确认操作')
      || state.conflictMessage.includes('恢复')
      || state.conflictMessage.includes('阻塞')
      || state.conflictMessage.includes('受阻')
      || state.conflictMessage.includes('重试')
    ),
  )

  return (
    <>
      <div className={`toast ${state.toast ? 'visible' : ''}`} id="toast" role="status" aria-live="polite">
        {state.toast}
      </div>
      {state.storageError ? (
        <div className="canvas-storage-error-banner" role="alert">
          <span>{t('canvas.draft.saveFailed', { message: state.storageError })}</span>
          <Button size="compact" onClick={() => void retryDraftPersist()}>
            {t('shared.retry')}
          </Button>
        </div>
      ) : null}
      {conflictedEntries.map(([nodeId, draft]) => {
        const isRemoteDeleted = draft.conflict?.type === 'remote_deleted' || draft.conflict?.kind === 'TARGET_MISSING'
        const message = draft.conflict?.message
          || (isRemoteDeleted
            ? t('canvas.conflict.remoteDeleted')
            : t('canvas.conflict.contentConflict'))
        return (
          <div key={nodeId} className="canvas-conflict-banner" role="alert" data-node-id={nodeId}>
            <span className="canvas-conflict-message">{message}</span>
            <div className="canvas-conflict-actions">
              {isRemoteDeleted ? (
                <Button variant="ghost" size="compact" onClick={() => void saveDraftAsNewNode(nodeId)}>
                  {t('canvas.conflict.saveAsNew')}
                </Button>
              ) : (
                <>
                  <Button variant="ghost" size="compact" onClick={() => void retryDraft(nodeId)}>
                    {t('canvas.conflict.retryLatest')}
                  </Button>
                  <Button variant="ghost" size="compact" onClick={() => void saveDraftAsNewNode(nodeId)}>
                    {t('canvas.conflict.saveAsNew')}
                  </Button>
                </>
              )}
              <Button variant="ghost" size="compact" danger onClick={() => dismissDraft(nodeId)}>
                {t('canvas.conflict.discard')}
              </Button>
            </div>
          </div>
        )
      })}
      {state.conflictMessage && conflictedEntries.length === 0 ? (
        <div className="canvas-conflict-banner" role="alert">
          <span className="canvas-conflict-message">{state.conflictMessage}</span>
          <div className="canvas-conflict-actions">
            {isRecoverableConflict ? (
              <Button
                variant="ghost"
                size="compact"
                onClick={() => void retryRecovery().catch(() => undefined)}
              >
                {t('shared.retry')}
              </Button>
            ) : null}
            <Button variant="ghost" size="compact" onClick={dismissConflictMessage}>
              {t('shared.close')}
            </Button>
          </div>
        </div>
      ) : null}
      {uploads.length > 0 ? (
        <div className="canvas-upload-progress" role="status" aria-live="polite">
          {uploads.map(([name, progress]) => (
            <span key={name}>
              {name.split(':')[0]}
              {' '}
              {Math.round(progress * 100)}
              %
            </span>
          ))}
        </div>
      ) : null}
    </>
  )
}
