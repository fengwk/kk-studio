import type { FormEventHandler } from 'react'
import { Button } from '@/shared/ui/controls/Button'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { AgentForm, ModelForm, ModelGroupForm, ProviderForm } from '@/features/ai/catalog/AiResourceForms'
import type { AgentDraft, ModelDraft, ModelGroupDraft, ProviderDraft, ResourceModal } from '@/features/ai/catalog/ai-console-types'
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
  modelGroupDraft,
  agentDraft,
  pending,
  formError = '',
  fieldErrors = {},
  onClose,
  onProviderDraftChange,
  onModelDraftChange,
  onModelGroupDraftChange,
  onModelCreateKindChange,
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
  modelGroupDraft: ModelGroupDraft
  agentDraft: AgentDraft
  pending: boolean
  formError?: string
  fieldErrors?: Partial<Record<ResourceFieldKey, string>>
  onClose: () => void
  onProviderDraftChange: (draft: ProviderDraft) => void
  onModelDraftChange: (draft: ModelDraft) => void
  onModelGroupDraftChange: (draft: ModelGroupDraft) => void
  onModelCreateKindChange: (kind: 'MODEL' | 'GROUP') => void
  onAgentDraftChange: (draft: AgentDraft) => void
  onSubmit: FormEventHandler<HTMLFormElement>
}) {
  const { t } = useI18n()
  if (!modal) {
    return null
  }

  const title = resourceTitle(modal)
  return (
    <Dialog
      className="resource-modal-card"
      title={title}
      pending={pending}
      onClose={onClose}
    >
      <form className="modal-card-form" aria-label={title} onSubmit={onSubmit} noValidate>
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
            <>
              {modal.mode === 'create' ? (
                <div className="model-kind-tabs" role="tablist" aria-label={t('ai.catalog.form.modelKind')}>
                  <button
                    type="button"
                    role="tab"
                    aria-selected={modal.resourceKind === 'MODEL'}
                    className={modal.resourceKind === 'MODEL' ? 'is-selected' : undefined}
                    onClick={() => onModelCreateKindChange('MODEL')}
                  >
                    {t('ai.catalog.resourceType.model')}
                  </button>
                  <button
                    type="button"
                    role="tab"
                    aria-selected={modal.resourceKind === 'GROUP'}
                    className={modal.resourceKind === 'GROUP' ? 'is-selected' : undefined}
                    onClick={() => onModelCreateKindChange('GROUP')}
                  >
                    {t('ai.catalog.resourceType.modelGroup')}
                  </button>
                </div>
              ) : null}
              {modal.resourceKind === 'GROUP' ? (
                <ModelGroupForm
                  draft={modelGroupDraft}
                  models={models}
                  fieldErrors={fieldErrors}
                  onChange={onModelGroupDraftChange}
                />
              ) : (
                <ModelForm
                  draft={modelDraft}
                  mode={modal.mode}
                  providers={providers}
                  fieldErrors={fieldErrors}
                  onChange={onModelDraftChange}
                />
              )}
            </>
          )}
          {modal.kind === 'agent' && (
            <AgentForm
              draft={agentDraft}
              mode={modal.mode}
              agentType={modal.type}
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
          <Button type="submit" disabled={pending}>
            {modal.mode === 'create'
              ? t('ai.catalog.action.confirmCreate')
              : t('ai.catalog.action.saveChanges')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
