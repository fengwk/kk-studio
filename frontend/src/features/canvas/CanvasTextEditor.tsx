import { useMemo, useRef } from 'react'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { projectCanvasSnapshot } from '@/features/canvas/domain'
import { projectNodes } from '@/features/canvas/projection'
import { useI18n } from '@/shared/i18n'

const EDITOR_WIDTH = 400
const EDITOR_GAP = 10
const STAGE_MARGIN = 12

/**
 * 非模态文本编辑面板：编辑模式锚定在被编辑节点的正下方（世界坐标按当前
 * viewport 映射到 stage 屏幕坐标，打开面板不改变节点 transform）；创建模式
 * 使用稳定的舞台位置。Escape/取消关闭，保存复用 controller 的 textEditor 路径。
 */
export function CanvasTextEditor() {
  const {
    state,
    snapshot: snapshotDTO,
    models,
    nodeCallbacks,
    stageMetrics,
    closeTextEditor,
    setTextEditorDraft,
    saveTextEditor,
  } = useCanvasRuntime()
  const { t } = useI18n()
  const panelRef = useRef<HTMLDivElement>(null)
  const editor = state.textEditor

  const anchor = useMemo(() => {
    if (!editor || editor.mode !== 'edit' || !snapshotDTO) {
      return null
    }
    const snapshot = projectCanvasSnapshot(snapshotDTO)
    const nodes = projectNodes(
      snapshot,
      state.selectedIds,
      models,
      nodeCallbacks,
      state.positionDrafts,
    )
    const flowNode = nodes.find((node) => node.id === editor.nodeId)
    if (!flowNode) {
      return null
    }
    const nodeHeight = Number(flowNode.measured?.height ?? flowNode.style?.height ?? 260)
    const zoom = state.viewport.zoom
    const left = flowNode.position.x * zoom + state.viewport.x
    const top = (flowNode.position.y + nodeHeight) * zoom + state.viewport.y + EDITOR_GAP
    return {
      left: Math.max(STAGE_MARGIN, Math.min(left, stageMetrics.width - EDITOR_WIDTH - STAGE_MARGIN)),
      top: Math.max(STAGE_MARGIN, top),
    }
  }, [
    editor,
    models,
    nodeCallbacks,
    snapshotDTO,
    stageMetrics.width,
    state.positionDrafts,
    state.selectedIds,
    state.viewport,
  ])

  if (!editor) {
    return null
  }

  const createMode = editor.mode === 'create'
  return (
    <div
      ref={panelRef}
      className={`canvas-text-editor nodrag nowheel${createMode ? ' create' : ''}`}
      aria-label={createMode
        ? t('canvas.textEditor.createAria')
        : t('canvas.textEditor.editAria', { name: editor.name })}
      style={anchor ? { left: anchor.left, top: anchor.top } : undefined}
      onPointerDown={(event) => event.stopPropagation()}
    >
      <div className="canvas-text-editor-head">
        <span className="canvas-text-editor-kicker">
          {createMode ? t('canvas.textEditor.createKicker') : t('canvas.textEditor.editKicker')}
        </span>
        <button
          type="button"
          className="canvas-text-editor-close"
          aria-label={t('canvas.textEditor.close')}
          onClick={closeTextEditor}
        >
          ✕
        </button>
      </div>
      <div className="canvas-text-editor-body">
        <label>
          <span>{t('canvas.textEditor.name')}</span>
          <input
            aria-label={t('canvas.textEditor.name')}
            autoFocus
            value={editor.name}
            onChange={(event) => setTextEditorDraft({ name: event.target.value })}
          />
        </label>
        <label>
          <span>{t('canvas.textEditor.markdown')}</span>
          <textarea
            aria-label={t('canvas.textEditor.markdown')}
            value={editor.markdown}
            onChange={(event) => setTextEditorDraft({ markdown: event.target.value })}
          />
        </label>
        <span className="canvas-text-editor-count" aria-live="polite">
          {t('canvas.textEditor.characters', { count: editor.markdown.length })}
        </span>
      </div>
      <footer className="canvas-text-editor-footer">
        <button type="button" onClick={closeTextEditor}>
          {t('canvas.textEditor.cancel')}
        </button>
        <button
          type="button"
          className="primary"
          disabled={!editor.name.trim() || !editor.markdown.trim()}
          onClick={saveTextEditor}
        >
          {t('canvas.textEditor.save')}
        </button>
      </footer>
    </div>
  )
}
