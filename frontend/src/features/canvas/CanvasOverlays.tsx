import type { CanvasNodeDraft } from '@/features/canvas/canvas-drafts'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'

/** 草稿冲突的用户文案只由结构化冲突种类决定，节点名等参数在这里插值。 */
function draftConflictMessage(
  conflict: NonNullable<CanvasNodeDraft['conflict']>,
  nodeName: string | undefined,
  t: (key: TranslationKey, values?: Record<string, string | number>) => string,
): string {
  const isRemoteDeleted = conflict.type === 'remote_deleted' || conflict.kind === 'TARGET_MISSING'
  if (isRemoteDeleted) {
    return nodeName
      ? t('canvas.conflict.remoteDeletedNamed', { name: nodeName })
      : t('canvas.conflict.remoteDeleted')
  }
  if (conflict.kind === 'NODE_REFERENCED') {
    return t('canvas.conflict.nodeReferenced')
  }
  if (conflict.kind === 'STALE_NODE') {
    return t('canvas.conflict.staleNode')
  }
  return t('canvas.conflict.contentConflict')
}

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

  const conflictedEntries = Object.entries(state.drafts).flatMap(([nodeId, draft]) => (
    draft.conflict ? [{ nodeId, draft, conflict: draft.conflict }] : []
  ))

  const canRetryRecovery = Boolean(state.conflictMessage) && state.conflictKind === 'recoverable'

  return (
    <>
      <div className="canvas-notices">
        <div className={`toast ${state.toast ? 'visible' : ''}`} id="toast" role="status" aria-live="polite">
          {state.toast}
        </div>
        {state.storageError ? (
          <div className="canvas-storage-error-banner" role="alert">
            <span>{state.storageError}</span>
            <Button size="compact" onClick={() => void retryDraftPersist()}>
              {t('shared.retry')}
            </Button>
          </div>
        ) : null}
        {conflictedEntries.map(({ nodeId, draft, conflict }) => {
          const isRemoteDeleted = conflict.type === 'remote_deleted' || conflict.kind === 'TARGET_MISSING'
          const detail = conflict.conflicts?.find((entry) => 'nodeId' in entry && entry.nodeId === nodeId
            && entry.type === conflict.kind)
          const diagnostic = detail?.type === 'STALE_NODE'
            ? t('canvas.conflict.groupDiagnostic', { group: detail.group })
            : detail?.type === 'NODE_REFERENCED'
              ? t('canvas.conflict.referencesDiagnostic', { ids: detail.referencingNodeIds.join(', ') })
              : undefined
          return (
            <div key={nodeId} className="canvas-conflict-banner" role="alert" data-node-id={nodeId}>
              <span className="canvas-conflict-message" title={diagnostic}>
                {draftConflictMessage(conflict, draft.text?.name, t)}
              </span>
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
              {canRetryRecovery ? (
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
      </div>
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
