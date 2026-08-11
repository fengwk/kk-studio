import { ArrowLeft, MessageSquare } from 'lucide-react'
import { Link, useNavigate } from 'react-router'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { CanvasStage } from '@/features/canvas/CanvasStage'
import { useI18n } from '@/shared/i18n'

export function CanvasEditor() {
  const { state, snapshot, snapshotQuery, openThread, collapseThread } = useCanvasRuntime()
  const { t } = useI18n()
  const navigate = useNavigate()
  const openLibrary = () => navigate('/canvas')

  if (snapshotQuery.isError) {
    const notFound = (snapshotQuery.error as { status?: number }).status === 404
    return (
      <section className="canvas-editor-state danger" role="alert">
        <h2>{notFound ? t('canvas.editor.notFound') : t('canvas.editor.loadFailed')}</h2>
        <p>{(snapshotQuery.error as Error).message}</p>
        <div>
          <button type="button" onClick={openLibrary}>{t('canvas.editor.backToLibrary')}</button>
          {!notFound ? <button type="button" onClick={() => void snapshotQuery.refetch()}>{t('canvas.editor.retry')}</button> : null}
        </div>
      </section>
    )
  }
  if (snapshotQuery.isLoading || !snapshot) {
    return (
      <section className="canvas-editor-state" role="status">
        <span className="canvas-spinner" />
        {t('canvas.editor.loading')}
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
            {state.commandPending ? t('canvas.editor.save.saving') : t('canvas.editor.save.saved')}
          </span>
          <span className="revision-pill" title={t('canvas.editor.revision')}>
            r{snapshot.document.graphRevision}
          </span>
        </div>
        <button
          type="button"
          className={`thread-toggle${state.threadOpen ? ' active' : ''}`}
          aria-label={t('canvas.editor.threadAria')}
          aria-expanded={state.threadOpen}
          aria-controls="agentPanel"
          onClick={() => {
            if (state.threadOpen) {
              collapseThread()
            } else {
              openThread()
            }
          }}
        >
          <MessageSquare aria-hidden="true" />
          <span>{t('canvas.editor.thread')}</span>
        </button>
      </header>
      <CanvasStage />
    </section>
  )
}
