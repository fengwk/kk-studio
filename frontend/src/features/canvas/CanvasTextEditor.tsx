import { useEffect, useRef } from 'react'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'

export function CanvasTextEditor() {
  const { state, closeTextEditor, setTextEditorDraft, saveTextEditor } = useCanvasRuntime()
  const dialogRef = useRef<HTMLDialogElement>(null)
  const editor = state.textEditor

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) {
      return
    }
    if (editor && !dialog.open) {
      dialog.showModal()
    } else if (!editor && dialog.open) {
      dialog.close()
    }
  }, [editor])

  if (!editor) {
    return <dialog ref={dialogRef} className="canvas-text-dialog" />
  }

  return (
    <dialog
      ref={dialogRef}
      className="canvas-text-dialog"
      aria-label={editor.mode === 'create' ? '创建 Markdown 文本' : '编辑 Markdown 文本'}
      onCancel={(event) => {
        event.preventDefault()
        closeTextEditor()
      }}
    >
      <form
        method="dialog"
        onSubmit={(event) => {
          event.preventDefault()
          saveTextEditor()
        }}
      >
        <div className="canvas-text-dialog-fields">
          {editor.mode === 'create' ? (
            <label>
              <span>名称</span>
              <input
                autoFocus
                aria-label="文本节点名称"
                value={editor.name}
                onChange={(event) => setTextEditorDraft({ name: event.target.value })}
              />
            </label>
          ) : null}
          <label>
            <span>Markdown</span>
            <textarea
              autoFocus={editor.mode === 'edit'}
              aria-label="Markdown 内容"
              value={editor.markdown}
              onChange={(event) => setTextEditorDraft({ markdown: event.target.value })}
            />
          </label>
        </div>
        <div className="canvas-text-preview" aria-label="Markdown 预览">
          <MarkdownRenderer content={editor.markdown} />
        </div>
        <footer>
          <button type="button" onClick={closeTextEditor}>取消</button>
          <button
            type="submit"
            className="primary"
            disabled={!editor.name.trim() || !editor.markdown.trim()}
          >
            保存
          </button>
        </footer>
      </form>
    </dialog>
  )
}
