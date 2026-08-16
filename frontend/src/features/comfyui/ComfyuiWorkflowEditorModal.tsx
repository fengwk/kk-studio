import type { FormEventHandler } from 'react'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { StateBlock } from '@/shared/ui/console/AiConsoleCommonCards'
import type { ComfyuiWorkflowDraft } from '@/features/comfyui/comfyui-types'
import type { ComfyuiWorkflowApiDTO } from '@/shared/api/contracts/comfyui'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { useI18n } from '@/shared/i18n'

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
  const { t } = useI18n()
  if (!modal) {
    return null
  }
  const title =
    modal.mode === 'create'
      ? t('comfyui.editor.createTitle')
      : t('comfyui.editor.editTitle')
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
        <ModalHeader
          title={title}
          onClose={pending ? () => undefined : onClose}
          closeDisabled={pending}
        />
        <div className="modal-body comfyui-modal-scroll">
          {error && <StateBlock title={error} tone="danger" />}
          <div className="metadata-grid metadata-grid-two">
            <label className="form-group">
              <FieldLabel required>{t('comfyui.editor.apiNameLabel')}</FieldLabel>
              <input
                aria-label={t('comfyui.editor.apiNameLabel')}
                value={draft.apiName}
                onChange={(event) => update('apiName', event.target.value)}
                placeholder={t('comfyui.editor.apiNamePlaceholder')}
                autoComplete="off"
              />
              <span className="inline-hint">{t('comfyui.editor.apiNameHint')}</span>
            </label>
            <label className="form-group">
              <FieldLabel required>{t('comfyui.editor.displayNameLabel')}</FieldLabel>
              <input
                aria-label={t('comfyui.editor.displayNameLabel')}
                value={draft.name}
                onChange={(event) => update('name', event.target.value)}
                placeholder={t('comfyui.editor.displayNamePlaceholder')}
              />
            </label>
          </div>
          <label className="form-group">
            <FieldLabel>{t('comfyui.editor.descriptionLabel')}</FieldLabel>
            <textarea
              value={draft.description}
              onChange={(event) => update('description', event.target.value)}
              placeholder={t('comfyui.editor.descriptionPlaceholder')}
            />
          </label>
          <label className="form-group">
            <FieldLabel required>{t('comfyui.editor.workflowJsonLabel')}</FieldLabel>
            <textarea
              aria-label={t('comfyui.editor.workflowJsonLabel')}
              className="code-textarea comfyui-workflow-json"
              value={draft.workflowJson}
              onChange={(event) => update('workflowJson', event.target.value)}
              spellCheck={false}
            />
          </label>
          <label className="form-group">
            <FieldLabel required>{t('comfyui.editor.inputBindingsLabel')}</FieldLabel>
            <textarea
              aria-label={t('comfyui.editor.inputBindingsLabel')}
              className="code-textarea comfyui-bindings-json"
              value={draft.inputBindingsJson}
              onChange={(event) => update('inputBindingsJson', event.target.value)}
              spellCheck={false}
            />
            <span className="inline-hint">{t('comfyui.editor.inputBindingsHint')}</span>
          </label>
          <label className="form-group">
            <FieldLabel>{t('comfyui.editor.selectorLabel')}</FieldLabel>
            <input
              value={draft.defaultSelector}
              onChange={(event) => update('defaultSelector', event.target.value)}
              placeholder={t('comfyui.editor.selectorPlaceholder')}
            />
          </label>
          <label className="checkbox-field">
            <input type="checkbox" checked={draft.enabled} onChange={(event) => update('enabled', event.target.checked)} />
            {t('comfyui.editor.enabledLabel')}
          </label>
        </div>
        <div className="modal-footer">
          <button type="submit" className="btn-primary" disabled={pending}>
            {modal.mode === 'create' ? t('comfyui.editor.createSubmit') : t('comfyui.editor.saveSubmit')}
          </button>
        </div>
      </form>
    </ModalBackdrop>
  )
}
