import { useLayoutEffect, useRef, useState, type FormEventHandler } from 'react'
import { Button } from '@/shared/ui/controls/Button'
import { Tabs } from '@/shared/ui/controls/Tabs'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useUnsavedChangesConfirmation } from '@/shared/ui/overlays/useUnsavedChangesConfirmation'
import { AgentForm } from '@/features/catalog/AiAgentResourceForm'
import { ModelForm } from '@/features/catalog/AiModelResourceForm'
import { ModelGroupForm } from '@/features/catalog/AiModelGroupResourceForm'
import { ProviderForm } from '@/features/catalog/AiProviderResourceForm'
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

/**
 * 错误可能落在仍使用 details 的表单段里。
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

export function ResourceEditorModal(props: ResourceEditorModalProps) {
  const { modal } = props
  if (!modal) return null
  const key = JSON.stringify([modal.kind, modal.mode, modal.mode === 'edit' ? modal.name : null])
  return <OpenResourceEditorModal key={key} {...props} modal={modal} />
}

/** 新建模型的两个页签共用一次打开会话，两份草稿都可能有修改。 */
function draftSnapshot(
  modal: ResourceModal,
  drafts: Pick<ResourceEditorModalProps, 'providerDraft' | 'modelDraft' | 'modelGroupDraft' | 'agentDraft'>,
) {
  switch (modal.kind) {
    case 'provider': return JSON.stringify(drafts.providerDraft)
    case 'agent': return JSON.stringify(drafts.agentDraft)
    case 'model':
      return JSON.stringify(modal.mode === 'create'
        ? [drafts.modelDraft, drafts.modelGroupDraft]
        : modal.resourceKind === 'GROUP' ? drafts.modelGroupDraft : drafts.modelDraft)
  }
}

function OpenResourceEditorModal({
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
}: ResourceEditorModalProps & { modal: ResourceModal }) {
  const { t } = useI18n()
  const bodyRef = useRef<HTMLDivElement>(null)
  const bannerRef = useRef<HTMLDivElement>(null)
  const snapshot = draftSnapshot(modal, { providerDraft, modelDraft, modelGroupDraft, agentDraft })
  const [baseline] = useState(snapshot)
  const { requestClose, confirmation } = useUnsavedChangesConfirmation(
    snapshot !== baseline, pending, onClose,
  )


  /**
   * 校验失败后的错误呈现：错误只在本表单内定位，优先第一处内联错误，
   * 只有没有任何内联落点时才用 banner。layout effect 保证本轮错误文本已进入 DOM。
   */
  useLayoutEffect(() => {
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

  const title = resourceTitle(modal)
  const hasSections = modal.kind === 'agent' || (modal.kind === 'model' && modal.resourceKind !== 'GROUP')
  const body = (
    <div ref={bodyRef} className="modal-body">
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
        {modal.kind === 'model' && (modal.resourceKind === 'GROUP' ? (
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
        ))}
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
  )

  const editor = (
    <Dialog
      className={[
        'resource-modal-card',
        'modal-touch-target',
        hasSections ? 'catalog-editor-card' : '',
      ].filter(Boolean).join(' ')}
      title={title}
      pending={pending}
      onClose={requestClose}
    >
      <form className="modal-card-form" aria-label={title} onSubmit={onSubmit} noValidate>
        {modal.kind === 'model' && modal.mode === 'create' ? (
          /* 资源类型就是当前表单的页签：选中项决定下面渲染哪一个表单。 */
          <Tabs
            className="model-kind-tabs"
            panelClassName="catalog-editor-tab-panel"
            tabs={[
              {
                id: 'MODEL',
                label: t('ai.catalog.resourceType.model'),
                disabled: pending,
              },
              {
                id: 'GROUP',
                label: t('ai.catalog.resourceType.modelGroup'),
                disabled: pending,
              },
            ]}
            activeId={modal.resourceKind}
            onChange={(id) => onModelCreateKindChange(id === 'GROUP' ? 'GROUP' : 'MODEL')}
            ariaLabel={t('ai.catalog.form.modelKind')}
          >
            {body}
          </Tabs>
        ) : (
          body
        )}
        <div className="modal-footer">
          <Button variant="secondary" disabled={pending} onClick={requestClose}>
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

  return <>{editor}{confirmation}</>
}

type ResourceEditorModalProps = {
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
}
