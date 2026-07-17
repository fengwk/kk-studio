import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { CanvasStage } from '@/features/canvas/CanvasStage'

export function CanvasEditor() {
  const { state, openLibrary, setHelpOpen, setToast } = useCanvasRuntime()

  return (
    <section className="view editor-view active" id="editorView" tabIndex={-1} aria-label="竞品研究与产品方案画布编辑器">
      <header className="editor-header">
        <button className="back-button" type="button" onClick={openLibrary}>
          ←
          {' '}
          <span>画布库</span>
        </button>
        <span className="header-divider" />
        <div className="document-title">
          <strong>竞品研究与产品方案</strong>
          <span id="saveState" aria-live="polite">
            {state.saveState === 'saving' ? '保存中…' : '已保存'}
          </span>
        </div>
        <div className="editor-actions">
          <button
            type="button"
            id="helpButton"
            aria-label="查看快捷操作"
            onClick={(event) => setHelpOpen(true, event.currentTarget)}
          >
            ?
          </button>
          <button type="button" id="shareButton" onClick={() => setToast('分享链接已复制（原型模拟）')}>分享</button>
          <button type="button" id="exportButton" onClick={() => setToast('导出 PNG / PDF / JSON 将在完整产品中提供（原型模拟）')}>导出</button>
          <button className="icon-button" type="button" id="editorMoreButton" aria-label="更多编辑器操作" onClick={() => setToast('更多编辑器操作将在完整产品中提供（原型模拟）')}>···</button>
        </div>
      </header>
      <CanvasStage />
    </section>
  )
}
