import { useEffect } from 'react'
import { RESEARCH_CONTENT } from '@/features/canvas/data'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import type { ResearchTab } from '@/features/canvas/types'

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
        aria-label="调研与架构说明"
        aria-hidden={!state.researchOpen}
        inert={!state.researchOpen}
      >
        <div className="panel-header">
          <div>
            <span className="eyebrow">产品研究</span>
            <h2>为什么这样设计？</h2>
          </div>
          <button
            className="icon-button"
            type="button"
            aria-label="关闭说明"
            ref={researchCloseRef}
            onClick={() => setResearchOpen(false)}
          >
            ×
          </button>
        </div>
        <div className="panel-tabs" role="group" aria-label="调研内容">
          {(
            [
              ['research', '竞品洞察'],
              ['architecture', '方案基座'],
              ['roadmap', '分期计划'],
            ] as const
          ).map(([tab, label]) => (
            <button
              key={tab}
              type="button"
              className={state.researchTab === tab ? 'active' : undefined}
              aria-pressed={state.researchTab === tab}
              onClick={() => setResearchTab(tab as ResearchTab)}
            >
              {label}
            </button>
          ))}
        </div>
        <div className="panel-content" id="panelContent">
          {state.researchTab === 'architecture' ? (
            <>
              <p className="panel-section-title">产品定位</p>
              {content.slice(0, 1).map((item) => (
                <article key={item.title} className="insight-card">
                  <h3>{item.title}</h3>
                  <p>{item.body}</p>
                </article>
              ))}
              <p className="panel-section-title">对象模型</p>
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
              <p className="panel-section-title">从验证到生态</p>
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
              <p className="panel-section-title">关键竞品结论</p>
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
            <span className="eyebrow">快捷操作</span>
            <h2 id="helpDialogTitle">在画布中保持专注</h2>
          </div>
          <button
            className="icon-button"
            type="button"
            aria-label="关闭快捷操作"
            ref={helpCloseRef}
            onClick={() => setHelpOpen(false)}
          >
            ×
          </button>
        </div>
        <div className="shortcut-grid">
          <div><kbd>Esc</kbd><span>关闭浮层或清除选择</span></div>
          <div><kbd>Delete</kbd><span>删除选中的对象</span></div>
          <div><kbd>V / H / T</kbd><span>选择、平移或创建文本</span></div>
          <div><kbd>0 / 1 / F</kbd><span>适应、100%、聚焦选区</span></div>
          <div><kbd>⌘ / Ctrl K</kbd><span>聚焦底部 Agent Dock</span></div>
          <div><kbd>Enter / Shift Enter</kbd><span>发送任务 / 换行</span></div>
        </div>
      </dialog>

      <div className={`toast ${state.toast ? 'visible' : ''}`} id="toast" role="status" aria-live="polite">
        {state.toast}
      </div>
    </>
  )
}
