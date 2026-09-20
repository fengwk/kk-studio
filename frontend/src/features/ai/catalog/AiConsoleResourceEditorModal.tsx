import type { FormEventHandler } from 'react'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { AgentForm, ModelForm, ProviderForm } from '@/features/ai/catalog/AiResourceForms'
import type { AgentDraft, ModelDraft, ProviderDraft, ResourceModal } from '@/features/ai/catalog/ai-console-types'
import type { ResourceFieldKey } from '@/features/ai/catalog/ai-resource-form-validation'
import { resourceTitle } from '@/features/ai/catalog/catalog-utils'
import type { AgentModelView } from '@/features/ai/catalog/AgentModelView'
import type {
  AgentDefinitionDTO,
  AgentProviderDTO,
  SkillPackageDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/contracts/ai-catalog'
import { useI18n } from '@/shared/i18n'

export function ResourceEditorModal({
  modal,
  providers,
  models,
  agents = [],
  toolCatalog = [],
  skills = [],
  skillsLoading = false,
  skillsError = null,
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
  models: AgentModelView[]
  agents?: AgentDefinitionDTO[]
  toolCatalog?: ToolCatalogEntryDTO[]
  skills?: SkillPackageDTO[]
  skillsLoading?: boolean
  skillsError?: unknown
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
  const { t } = useI18n()
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
              mode={modal.mode}
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
              mode={modal.mode}
              models={models}
              agents={agents}
              toolCatalog={toolCatalog}
              skills={skills}
              skillsLoading={skillsLoading}
              skillsError={skillsError}
              fieldErrors={fieldErrors}
              onChange={onAgentDraftChange}
            />
          )}
        </div>
        <div className="modal-footer">
          <button type="submit" className="btn-primary" disabled={pending}>
            {modal.mode === 'create'
              ? t('ai.catalog.action.confirmCreate')
              : t('ai.catalog.action.saveChanges')}
          </button>
        </div>
      </form>
    </ModalBackdrop>
  )
}
