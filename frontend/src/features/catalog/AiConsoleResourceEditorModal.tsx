import { useLayoutEffect, useRef, type FormEventHandler } from 'react'
import { Button } from '@/shared/ui/controls/Button'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { AgentForm } from '@/features/catalog/AiAgentResourceForm'
import { ModelForm } from '@/features/catalog/AiModelResourceForm'
import { ModelGroupForm } from '@/features/catalog/AiModelGroupResourceForm'
import { ProviderForm } from '@/features/catalog/AiProviderResourceForm'
import type { CatalogSectionName } from '@/features/catalog/CatalogEditorSection'
import type { CapabilityDirectory } from '@/features/catalog/CapabilitySelectionPanels'
import type {
  AgentDraft,
  ModelDraft,
  ModelGroupDraft,
  ProviderDraft,
  ResourceModal,
} from '@/features/catalog/ai-console-types'
import type { ResourceFieldKey } from '@/features/catalog/ai-resource-form-validation'
import { resourceTitle } from '@/features/catalog/catalog-utils'
import type { ModelDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import type {
  AgentDTO,
  ProviderDTO,
  SkillPackageSummaryDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/generated/contracts'
import './ai-console-resource-editor.css'

/** 单模型与 Agent 的区块顺序；Provider 和模型组保持原有的单列表单，不分块。 */
const MODEL_SECTIONS: readonly CatalogSectionName[] = ['basics', 'limits', 'presets', 'pricing']
const AGENT_SECTIONS: readonly CatalogSectionName[] = [
  'basics',
  'execution',
  'prompt',
  'capabilities',
]

function editorSections(modal: ResourceModal): readonly CatalogSectionName[] {
  if (modal.kind === 'provider') {
    return []
  }
  if (modal.kind === 'agent') {
    return AGENT_SECTIONS
  }
  return modal.resourceKind === 'GROUP' ? [] : MODEL_SECTIONS
}

/**
 * 错误可能落在收起的分组里（Agent 能力区把内容放进 details）。
 * 必须在测量与滚动之前同步展开，否则拿到的是收起后的位置。
 */
function openContainingDetails(target: HTMLElement): void {
  for (let node = target.parentElement; node !== null; node = node.parentElement) {
    if (node instanceof HTMLDetailsElement && !node.open) {
      node.open = true
    }
  }
}

/** 把视图带到第一处错误：先展开折叠容器，再聚焦（可被读屏播报）并滚动。 */
function revealError(target: HTMLElement | null): void {
  if (!target) {
    return
  }
  openContainingDetails(target)
  if (!target.hasAttribute('tabindex')) {
    target.tabIndex = -1
  }
  target.focus({ preventScroll: true })
  target.scrollIntoView({ block: 'center', behavior: 'smooth' })
}

export function ResourceEditorModal({
  modal,
  providers,
  models,
  agents = [],
  toolCatalog = [],
  skills = [],
  capabilityDirectories,
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
  providers: ProviderDTO[]
  models: ModelDTO[]
  agents?: AgentDTO[]
  toolCatalog?: ToolCatalogEntryDTO[]
  skills?: SkillPackageSummaryDTO[]
  capabilityDirectories?: Partial<Record<'tools' | 'skills' | 'subagents', CapabilityDirectory>>
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
  onModelCreateKindChange: (type: 'MODEL' | 'GROUP') => void
  onAgentDraftChange: (draft: AgentDraft) => void
  onSubmit: FormEventHandler<HTMLFormElement>
}) {
  const { t } = useI18n()
  const bodyRef = useRef<HTMLDivElement>(null)
  const bannerRef = useRef<HTMLDivElement>(null)

  /**
   * 校验失败后的错误呈现：错误只在本表单内定位，优先第一处内联错误，
   * 只有没有任何内联落点时才用 banner。layout effect 保证本轮错误文本已进入 DOM。
   */
  useLayoutEffect(() => {
    if (!modal) {
      return
    }
    if (formError === '' && Object.keys(fieldErrors).length === 0) {
      return
    }
    const inlineErrors = bodyRef.current?.querySelectorAll<HTMLElement>('.field-error')
    const banner = bannerRef.current
    if (banner && inlineErrors) {
      banner.hidden = Array.from(inlineErrors).some((error) => error.textContent === formError)
    }
    revealError(inlineErrors?.[0] ?? banner)
  }, [fieldErrors, formError, modal])

  if (!modal) {
    return null
  }

  const title = resourceTitle(modal)
  const sections = editorSections(modal)

  function jumpToSection(name: CatalogSectionName) {
    const target = bodyRef.current?.querySelector<HTMLElement>(
      `#catalog-section-${name}`,
    )
    if (!target) {
      return
    }
    target.focus({ preventScroll: true })
    target.scrollIntoView({ block: 'start', behavior: 'smooth' })
  }

  return (
    <Dialog
      className={[
        'resource-modal-card',
        'modal-touch-target',
        sections.length > 0 ? 'catalog-editor-card' : '',
      ].filter(Boolean).join(' ')}
      title={title}
      pending={pending}
      onClose={onClose}
    >
      <form className="modal-card-form" aria-label={title} onSubmit={onSubmit} noValidate>
        <div
          ref={bodyRef}
          className={['modal-body', 'catalog-editor-body', sections.length > 0 ? 'is-split' : '']
            .filter(Boolean)
            .join(' ')}
        >
          {sections.length > 0 ? (
            <nav className="catalog-editor-nav" aria-label={t('ai.catalog.sectionNavigation')}>
              {sections.map((name) => (
                <button
                  key={name}
                  type="button"
                  className="catalog-editor-nav-link"
                  onClick={() => jumpToSection(name)}
                >
                  {t(`ai.catalog.section.${name}`)}
                </button>
              ))}
            </nav>
          ) : null}
          <div className="catalog-editor-content">
            {formError ? (
              <div ref={bannerRef} className="form-error-banner" role="alert">
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
                models={models}
                agents={agents}
                toolCatalog={toolCatalog}
                skills={skills}
                capabilityDirectories={capabilityDirectories}
                fieldErrors={fieldErrors}
                onChange={onAgentDraftChange}
              />
            )}
          </div>
        </div>
        <div className="modal-footer">
          <Button variant="inline" disabled={pending} onClick={onClose}>
            {t('shared.cancel')}
          </Button>
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
