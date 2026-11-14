import { AlertTriangle, Pencil, RefreshCw } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { Tabs } from '@/shared/ui/controls/Tabs'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { toDraftStates } from '../workflow-draft'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import { ProjectBasicPanel } from './edit-project/ProjectBasicPanel'
import { ProjectWorkflowPanel } from './edit-project/ProjectWorkflowPanel'
import type { EditProjectTab } from './edit-project/useProjectDraft'
import { useProjectDraft, useWorkflowDraftEditing } from './edit-project/useProjectDraft'
import { useProjectSave } from './edit-project/useProjectSave'
import type { ProjectDTO, ProjectSnapshotDTO } from '@/shared/api/generated/contracts'

export interface EditProjectModalProps {
  isOpen: boolean
  project: ProjectDTO | null
  initialTab?: EditProjectTab
  /** 看板快照；列表入口在工作流打开时复用既有 snapshot 查询。 */
  snapshot?: ProjectSnapshotDTO
  snapshotError?: unknown
  onClose: () => void
  onSuccess: (updated: ProjectDTO) => void
  api?: ProjectsApi
}

/**
 * 项目编辑：基础信息与工作流两个 tab，各自独立保存。
 *
 * <p>外层只在打开且已知项目时挂载内容组件，并按项目 id 重建，保证草稿从当前权威值开始。
 */
export function EditProjectModal({
  isOpen,
  project,
  initialTab = 'basic',
  snapshot,
  snapshotError,
  onClose,
  onSuccess,
  api = projectsApi,
}: EditProjectModalProps) {
  if (!isOpen || !project) {
    return null
  }

  return (
    <EditProjectModalContent
      key={project.id}
      project={project}
      initialTab={initialTab}
      snapshot={snapshot}
      snapshotError={snapshotError}
      onClose={onClose}
      onSuccess={onSuccess}
      api={api}
    />
  )
}

interface EditProjectModalContentProps {
  project: ProjectDTO
  initialTab: EditProjectTab
  snapshot?: ProjectSnapshotDTO
  snapshotError?: unknown
  onClose: () => void
  onSuccess: (updated: ProjectDTO) => void
  api: ProjectsApi
}

function EditProjectModalContent({
  project,
  initialTab,
  snapshot,
  snapshotError,
  onClose,
  onSuccess,
  api,
}: EditProjectModalContentProps) {
  const { t } = useI18n()
  const draft = useProjectDraft({ project, initialTab, snapshot, snapshotError, api })

  const save = useProjectSave({
    projectId: project.id,
    api,
    frozenVersion: draft.frozenVersion,
    setFrozenVersion: draft.setFrozenVersion,
    title: draft.title,
    description: draft.description,
    yoloEnabled: draft.yoloEnabled,
    draftStates: draft.draftStates,
    referencedStates: draft.referencedStates,
    workflowLockedReason: draft.workflowLockedReason,
    onSuccess,
    onDiscardDraft: (fresh) => {
      draft.setTitle(fresh.title)
      draft.setDescription(fresh.description)
      draft.setYoloEnabled(fresh.yoloEnabled)
      draft.setDraftStates(toDraftStates(fresh.workflow))
      draft.setSelectedKey(null)
    },
  })

  const editing = useWorkflowDraftEditing({
    draftStates: draft.draftStates,
    setDraftStates: draft.setDraftStates,
    setSelectedKey: draft.setSelectedKey,
    constraintsReady: draft.constraintsReady,
    referencedStates: draft.referencedStates,
    onEdited: () => save.setScopeMessage('workflow', { success: null }),
  })

  const { isPending, isReloading, activeSubmission, messages, recoveryMessage } = save

  return (
    <Dialog
      className="resource-modal-card edit-project-modal-card modal-touch-target"
      title={t('projects.edit.title')}
      headerIcon={<Pencil size={18} aria-hidden="true" />}
      pending={isPending}
      onClose={onClose}
    >
      {recoveryMessage ? (
        <div className="form-error-banner" role="alert">
          <div className="edit-project-recovery">
            <span>
              <AlertTriangle size={16} aria-hidden="true" />
              {recoveryMessage}
            </span>
            <div className="edit-project-recovery-actions">
              <Button variant="ghost" disabled={isPending} onClick={() => void save.recover(false)}>
                {t('projects.edit.reloadKeepDraft')}
              </Button>
              <Button variant="ghost" disabled={isPending} onClick={() => void save.recover(true)}>
                {t('projects.edit.reloadDiscardDraft')}
              </Button>
            </div>
          </div>
        </div>
      ) : null}

      {isReloading && (
        <div className="edit-project-reloading" role="status">
          <RefreshCw size={14} className="animate-spin" aria-hidden="true" />
          <span>{t('projects.edit.reloadRunning')}</span>
        </div>
      )}

      <Tabs
        className="edit-project-tabs"
        ariaLabel={t('projects.edit.tabsLabel')}
        activeId={draft.activeTab}
        onChange={(id) => draft.setActiveTab(id as EditProjectTab)}
        tabs={[
          { id: 'basic', label: t('projects.edit.tabBasic') },
          { id: 'workflow', label: t('projects.edit.tabWorkflow') },
        ]}
      >
        {draft.activeTab === 'basic' ? (
          <ProjectBasicPanel
            title={draft.title}
            description={draft.description}
            yoloEnabled={draft.yoloEnabled}
            isPending={isPending}
            activeSubmission={activeSubmission}
            messages={messages}
            onTitleChange={draft.setTitle}
            onDescriptionChange={draft.setDescription}
            onYoloChange={draft.setYoloEnabled}
            onSaveBasic={(event) => void save.save('basic', event)}
            onSaveYolo={(event) => void save.save('yolo', event)}
            onClearSuccess={(scope) => save.setScopeMessage(scope, { success: null })}
          />
        ) : (
          <ProjectWorkflowPanel
            draftStates={draft.draftStates}
            selectedKey={draft.selectedKey}
            referencedStates={draft.referencedStates}
            constraintsReady={draft.constraintsReady}
            constraintsError={
              draft.constraintsError == null
                ? null
                : draft.constraintsError instanceof Error
                  ? draft.constraintsError.message
                  : String(draft.constraintsError)
            }
            constraintsFetching={draft.constraintsQuery.isFetching}
            onRefetchConstraints={() => void draft.constraintsQuery.refetch()}
            workflowLockedReason={draft.workflowLockedReason}
            messages={messages.workflow}
            missingCatalogAgents={draft.missingCatalogAgents}
            agentOptions={draft.agentOptions}
            agentsLoading={draft.agentsLoading}
            environments={draft.environments}
            isPending={isPending}
            activeSubmission={activeSubmission}
            onSelect={draft.setSelectedKey}
            onRename={editing.renameState}
            onUpdate={editing.updateState}
            onToggleNext={editing.toggleNext}
            onMove={editing.moveState}
            onAdd={editing.addState}
            onRemove={editing.removeState}
            onSubmit={(event) => void save.save('workflow', event)}
          />
        )}
      </Tabs>

      <div className="modal-footer">
        <Button variant="ghost" onClick={onClose} disabled={isPending}>
          {t('projects.close')}
        </Button>
      </div>
    </Dialog>
  )
}
