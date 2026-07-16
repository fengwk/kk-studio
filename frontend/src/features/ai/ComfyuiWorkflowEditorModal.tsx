import type { FormEventHandler } from 'react'
import { ModalBackdrop, ModalHeader } from '@/features/ai/AiConsoleModalLayout'
import { StateBlock } from '@/features/ai/AiConsoleCards'
import type { ComfyuiWorkflowDraft } from '@/features/ai/ai-console-types'
import type { ComfyuiWorkflowApiDTO } from '@/shared/api/contracts'

type EditorModal =
  | { mode: 'create'; workflow: null }
  | { mode: 'edit'; workflow: ComfyuiWorkflowApiDTO }

export function ComfyuiWorkflowEditorModal({
  modal,
  draft,
  pending,
  error,
  onClose,
  onDraftChange,
  onSubmit,
}: {
  modal: EditorModal | null
  draft: ComfyuiWorkflowDraft
  pending: boolean
  error: string | null
  onClose: () => void
  onDraftChange: (draft: ComfyuiWorkflowDraft) => void
  onSubmit: FormEventHandler<HTMLFormElement>
}) {
  if (!modal) {
    return null
  }
  const title = modal.mode === 'create' ? '新建 ComfyUI Workflow' : '编辑 ComfyUI Workflow'
  const update = <K extends keyof ComfyuiWorkflowDraft>(key: K, value: ComfyuiWorkflowDraft[K]) =>
    onDraftChange({ ...draft, [key]: value })

  return (
    <ModalBackdrop onClose={pending ? () => undefined : onClose}>
      <form
        className="modal-card resource-modal-card comfyui-editor-modal"
        aria-label={title}
        onSubmit={onSubmit}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <ModalHeader title={title} onClose={pending ? () => undefined : onClose} />
        <div className="modal-body comfyui-modal-scroll">
          {error && <StateBlock title={error} tone="danger" />}
          <div className="metadata-grid metadata-grid-two">
            <label className="form-group">
              API name
              <input
                aria-label="API name"
                value={draft.apiName}
                onChange={(event) => update('apiName', event.target.value)}
                placeholder="image-upscale"
                autoComplete="off"
              />
              <span className="inline-hint">小写字母开头，仅允许小写字母、数字和连字符。</span>
            </label>
            <label className="form-group">
              Display name
              <input aria-label="Display name" value={draft.name} onChange={(event) => update('name', event.target.value)} placeholder="Image Upscale" />
            </label>
          </div>
          <label className="form-group">
            Description
            <textarea value={draft.description} onChange={(event) => update('description', event.target.value)} placeholder="工作流用途说明" />
          </label>
          <label className="form-group">
            API-format workflow JSON
            <textarea
              aria-label="API-format workflow JSON"
              className="code-textarea comfyui-workflow-json"
              value={draft.workflowJson}
              onChange={(event) => update('workflowJson', event.target.value)}
              spellCheck={false}
            />
          </label>
          <label className="form-group">
            Input bindings JSON
            <textarea
              aria-label="Input bindings JSON"
              className="code-textarea comfyui-bindings-json"
              value={draft.inputBindingsJson}
              onChange={(event) => update('inputBindingsJson', event.target.value)}
              spellCheck={false}
            />
            <span className="inline-hint">必须是数组；每项声明 name、kind、nodeId 和 inputName。</span>
          </label>
          <label className="form-group">
            Default JSONPath selector
            <input
              value={draft.defaultSelector}
              onChange={(event) => update('defaultSelector', event.target.value)}
              placeholder="$.outputs"
            />
          </label>
          <label className="checkbox-field">
            <input type="checkbox" checked={draft.enabled} onChange={(event) => update('enabled', event.target.checked)} />
            Enabled
          </label>
        </div>
        <div className="modal-footer">
          <button type="submit" className="btn-primary" disabled={pending}>
            {modal.mode === 'create' ? '确认创建' : '保存修改'}
          </button>
        </div>
      </form>
    </ModalBackdrop>
  )
}
