import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { CanvasStage } from '@/features/canvas/CanvasStage'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import './canvas-editor.css'

export function CanvasEditor() {
  const { state, snapshot, snapshotQuery } = useCanvasRuntime()
  const { t } = useI18n()

  if (snapshotQuery.isError) {
    const notFound = (snapshotQuery.error as { status?: number }).status === 404
    return (
      <section className="canvas-editor-state">
        <StateBlock
          tone="danger"
          title={notFound ? t('canvas.editor.notFound') : t('canvas.editor.loadFailed')}
        />
        <p>{(snapshotQuery.error as Error).message}</p>
        <div className="canvas-editor-state-actions">
          {!notFound ? (
            <Button onClick={() => void snapshotQuery.refetch()}>{t('canvas.editor.retry')}</Button>
          ) : null}
        </div>
      </section>
    )
  }
  if (snapshotQuery.isLoading || !snapshot) {
    return (
      <section className="canvas-editor-state" role="status">
        <span className="canvas-spinner" />
        <StateBlock title={t('canvas.editor.loading')} />
      </section>
    )
  }

  return (
    <section className="view editor-view active" tabIndex={-1} aria-label={t('canvas.editor.ariaLabel')}>
      <span className="sr-only" aria-live="polite">
        {state.commandPending || state.draftPersistPending
          ? t('canvas.editor.save.saving')
          : state.storageError ? t('canvas.editor.save.failed') : t('canvas.editor.save.saved')}
      </span>
      <CanvasStage />
    </section>
  )
}
