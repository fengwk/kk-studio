import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { CanvasStage } from '@/features/canvas/CanvasStage'
import { useI18n } from '@/shared/i18n'

export function CanvasEditor() {
  const { state, openLibrary, setHelpOpen, setToast } = useCanvasRuntime()
  const { t } = useI18n()

  return (
    <section className="view editor-view active" id="editorView" tabIndex={-1} aria-label={t('canvas.editor.ariaLabel')}>
      <header className="editor-header">
        <button className="back-button" type="button" onClick={openLibrary}>
          ←
          {' '}
          <span>{t('canvas.editor.library')}</span>
        </button>
        <span className="header-divider" />
        <div className="document-title">
          <strong>竞品研究与产品方案</strong>
          <span id="saveState" aria-live="polite">
            {state.saveState === 'saving' ? t('canvas.editor.save.saving') : t('canvas.editor.save.saved')}
          </span>
        </div>
        <div className="editor-actions">
          <button
            type="button"
            id="helpButton"
            aria-label={t('canvas.editor.help')}
            onClick={(event) => setHelpOpen(true, event.currentTarget)}
          >
            ?
          </button>
          <button type="button" id="shareButton" onClick={() => setToast(t('canvas.toast.editor.share'))}>{t('canvas.editor.share')}</button>
          <button type="button" id="exportButton" onClick={() => setToast(t('canvas.toast.editor.export'))}>{t('canvas.editor.export')}</button>
          <button
            className="icon-button"
            type="button"
            id="editorMoreButton"
            aria-label={t('canvas.editor.more')}
            onClick={() => setToast(t('canvas.toast.editor.more'))}
          >
            ···
          </button>
        </div>
      </header>
      <CanvasStage />
    </section>
  )
}
