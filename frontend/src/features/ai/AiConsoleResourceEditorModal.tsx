import type { FormEventHandler } from 'react'
import { ModalBackdrop, ModalHeader } from '@/features/ai/AiConsoleModalLayout'
import { AgentForm, ModelForm, ProviderForm } from '@/features/ai/AiResourceForms'
import type { AgentDraft, ModelDraft, ProviderDraft, ResourceModal } from '@/features/ai/ai-console-types'
import type { ResourceFieldKey } from '@/features/ai/ai-resource-form-validation'
import { resourceTitle } from '@/features/ai/ai-console-utils'
import type {
  AgentDefinitionDTO,
  AgentModelDTO,
  AgentProviderDTO,
  LiveEnvironmentDTO,
} from '@/shared/api/contracts'

export function ResourceEditorModal({
  modal,
  providers,
  models,
  agents = [],
  environments = [],
  providerDraft,
  modelDraft,
  agentDraft,
  pending,
  formError = '',
  fieldErrors = {},
  onClose,
  onProviderDraftChange,
  onModelDraftChange,
  onAgentDraftChange,
  onSubmit,
}: {
  modal: ResourceModal | null
  providers: AgentProviderDTO[]
  models: AgentModelDTO[]
  agents?: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  providerDraft: ProviderDraft
  modelDraft: ModelDraft
  agentDraft: AgentDraft
  pending: boolean
  formError?: string
  fieldErrors?: Partial<Record<ResourceFieldKey, string>>
  onClose: () => void
  onProviderDraftChange: (draft: ProviderDraft) => void
  onModelDraftChange: (draft: ModelDraft) => void
  onAgentDraftChange: (draft: AgentDraft) => void
  onSubmit: FormEventHandler<HTMLFormElement>
}) {
  if (!modal) {
    return null
  }

  const title = resourceTitle(modal)
  return (
    <ModalBackdrop onClose={onClose}>
      <form
        className="modal-card resource-modal-card"
        aria-label={title}
        onSubmit={onSubmit}
        onMouseDown={(event) => event.stopPropagation()}
        noValidate
      >
        <ModalHeader title={title} onClose={onClose} />
        <div className="modal-body">
          {formError ? (
            <div className="form-error-banner" role="alert">
              {formError}
            </div>
          ) : null}
          {modal.kind === 'provider' && (
            <ProviderForm
              draft={providerDraft}
              fieldErrors={fieldErrors}
              onChange={onProviderDraftChange}
            />
          )}
          {modal.kind === 'model' && (
            <ModelForm
              draft={modelDraft}
              mode={modal.mode}
              providers={providers}
              fieldErrors={fieldErrors}
              onChange={onModelDraftChange}
            />
          )}
          {modal.kind === 'agent' && (
            <AgentForm
              draft={agentDraft}
              models={models}
              agents={agents}
              environments={environments}
              fieldErrors={fieldErrors}
              onChange={onAgentDraftChange}
            />
          )}
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
