import { useEffect } from 'react'
import { RESEARCH_CONTENT } from '@/features/canvas/data'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import type { ResearchTab } from '@/features/canvas/types'
import { useI18n } from '@/shared/i18n'

export function CanvasOverlays() {
  const {
    state,
    setResearchOpen,
    setResearchTab,
    setHelpOpen,
    helpDialogRef,
    helpCloseRef,
    researchCloseRef,
  } = useCanvasRuntime()
  const { t } = useI18n()

  const content = RESEARCH_CONTENT[state.researchTab]

  useEffect(() => {
    const dialog = helpDialogRef.current
    if (!dialog) {
      return
    }
    const onCancel = (event: Event) => {
      event.preventDefault()
      setHelpOpen(false)
    }
    dialog.addEventListener('cancel', onCancel)
    return () => dialog.removeEventListener('cancel', onCancel)
  }, [helpDialogRef, setHelpOpen])

  return (
    <>
      <aside
        className={`side-panel ${state.researchOpen ? '' : 'hidden'}`}
        id="researchPanel"
        role="dialog"
        aria-label={t('canvas.research.ariaLabel')}
        aria-hidden={!state.researchOpen}
        inert={!state.researchOpen}
      >
        <div className="panel-header">
          <div>
            <span className="eyebrow">{t('canvas.research.eyebrow')}</span>
            <h2>{t('canvas.research.title')}</h2>
          </div>
          <button
            className="icon-button"
            type="button"
            aria-label={t('canvas.research.close')}
            ref={researchCloseRef}
            onClick={() => setResearchOpen(false)}
          >
            ×
          </button>
        </div>
        <div className="panel-tabs" role="group" aria-label={t('canvas.research.tabs')}>
          {(
            [
              ['research', 'canvas.research.tab.research'],
              ['architecture', 'canvas.research.tab.architecture'],
              ['roadmap', 'canvas.research.tab.roadmap'],
            ] as const
          ).map(([tab, labelKey]) => (
            <button
              key={tab}
              type="button"
              className={state.researchTab === tab ? 'active' : undefined}
              aria-pressed={state.researchTab === tab}
              onClick={() => setResearchTab(tab as ResearchTab)}
            >
              {t(labelKey)}
            </button>
          ))}
        </div>
        <div className="panel-content" id="panelContent">
          {state.researchTab === 'architecture' ? (
            <>
              <p className="panel-section-title">{t('canvas.research.section.positioning')}</p>
              {content.slice(0, 1).map((item) => (
                <article key={item.title} className="insight-card">
                  <h3>{item.title}</h3>
                  <p>{item.body}</p>
                </article>
              ))}
              <p className="panel-section-title">{t('canvas.research.section.objectModel')}</p>
              <div className="model-flow">
                CanvasDocument
                <span>→</span>
                Node / Link
              </div>
              {content.slice(1).map((item) => (
                <article key={item.title} className="insight-card">
                  <h3>{item.title}</h3>
                  <p>{item.body}</p>
                </article>
              ))}
            </>
          ) : state.researchTab === 'roadmap' ? (
            <>
              <p className="panel-section-title">{t('canvas.research.section.roadmap')}</p>
              {content.map((item) => (
                <article key={item.title} className="insight-card phase">
                  <span className="phase-index">{item.phase}</span>
                  <div>
                    <h3>{item.title}</h3>
                    <p>{item.body}</p>
                  </div>
                </article>
              ))}
            </>
          ) : (
            <>
              <p className="panel-section-title">{t('canvas.research.section.insights')}</p>
              {content.map((item) => (
                <article key={item.title} className="insight-card">
                  <h3>{item.title}</h3>
                  <p>{item.body}</p>
                </article>
              ))}
            </>
          )}
        </div>
      </aside>

      <dialog
        className="help-dialog"
        id="helpDialog"
        ref={helpDialogRef}
        aria-labelledby="helpDialogTitle"
      >
        <div className="dialog-header">
          <div>
            <span className="eyebrow">{t('canvas.help.eyebrow')}</span>
            <h2 id="helpDialogTitle">{t('canvas.help.title')}</h2>
          </div>
          <button
            className="icon-button"
            type="button"
            aria-label={t('canvas.help.close')}
            ref={helpCloseRef}
            onClick={() => setHelpOpen(false)}
          >
            ×
          </button>
        </div>
        <div className="shortcut-grid">
          <div><kbd>Esc</kbd><span>{t('canvas.help.shortcut.dismiss')}</span></div>
          <div><kbd>Delete</kbd><span>{t('canvas.help.shortcut.delete')}</span></div>
          <div><kbd>V / H / T</kbd><span>{t('canvas.help.shortcut.tools')}</span></div>
          <div><kbd>0 / 1 / F</kbd><span>{t('canvas.help.shortcut.zoom')}</span></div>
          <div><kbd>⌘ / Ctrl K</kbd><span>{t('canvas.help.shortcut.focusDock')}</span></div>
          <div><kbd>Enter / Shift Enter</kbd><span>{t('canvas.help.shortcut.send')}</span></div>
        </div>
      </dialog>

      <div className={`toast ${state.toast ? 'visible' : ''}`} id="toast" role="status" aria-live="polite">
        {state.toast}
      </div>
    </>
  )
}
