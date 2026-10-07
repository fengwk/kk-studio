import { ArrowLeft } from 'lucide-react'
import { Link, useNavigate } from 'react-router'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { CanvasStage } from '@/features/canvas/CanvasStage'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'

export function CanvasEditor() {
  const { state, snapshot, snapshotQuery } = useCanvasRuntime()
  const { t } = useI18n()
  const navigate = useNavigate()
  const openLibrary = () => navigate('/canvas')

  if (snapshotQuery.isError) {
    const notFound = (snapshotQuery.error as { status?: number }).status === 404
    return (
      <section className="canvas-editor-state" role="alert">
        <StateBlock
          tone="danger"
          title={notFound ? t('canvas.editor.notFound') : t('canvas.editor.loadFailed')}
        />
        <p>{(snapshotQuery.error as Error).message}</p>
        <div className="canvas-editor-state-actions">
          <Button variant="ghost" onClick={openLibrary}>{t('canvas.editor.backToLibrary')}</Button>
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
    <section className="view editor-view active" id="editorView" tabIndex={-1} aria-label={t('canvas.editor.ariaLabel')}>
      <header className="editor-header">
        <Link
          className="sidebar-icon-btn canvas-back-button"
          to="/canvas"
          title={t('canvas.editor.backToLibrary')}
          aria-label={t('canvas.editor.backToLibrary')}
        >
          <ArrowLeft aria-hidden="true" />
        </Link>
        <div className="document-title">
          <strong>{snapshot.document.title}</strong>
          <span className="save-state" id="saveState" aria-live="polite">
            {state.commandPending || state.draftPersistPending
              ? t('canvas.editor.save.saving')
              : state.storageError
                ? t('canvas.editor.save.failed')
                : t('canvas.editor.save.saved')}
          </span>
          <span className="version-pill" title={t('canvas.editor.version')}>
            v{snapshot.document.revision}
          </span>
        </div>
      </header>
      <CanvasStage />
    </section>
  )
}
