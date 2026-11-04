import { useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, CheckCircle2, Pencil, RefreshCw } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { ApiError, isConflictError } from '@/shared/api/client'
import { environmentService } from '@/features/environment/environment-service'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { HelpHint } from '@/shared/ui/controls/HelpHint'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Tabs } from '@/shared/ui/controls/Tabs'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { isNetworkUnknownError } from '../pending-action-sidecar'
import { useCatalogAgentNames } from '../useCatalogAgentNames'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import {
  buildWorkflowDTO,
  createDraftState,
  hasActiveRun,
  toDraftStates,
  validateWorkflowDraft,
  workflowErrorKey,
  workflowErrorParams,
  type WorkflowDraftState,
} from '../workflow-draft'
import { WorkflowStageEditor } from './workflow-editor/WorkflowStageEditor'
import type { ProjectDTO, ProjectSnapshotDTO } from '@/shared/api/generated/contracts'

export interface EditProjectModalProps {
  isOpen: boolean
  project: ProjectDTO | null
  initialTab?: TabKey
  /** 看板快照；列表入口在工作流打开时复用既有 snapshot 查询。 */
  snapshot?: ProjectSnapshotDTO
  snapshotError?: unknown
  onClose: () => void
  onSuccess: (updated: ProjectDTO) => void
  api?: ProjectsApi
}

type TabKey = 'basic' | 'workflow'
type SaveScope = 'basic' | 'yolo' | 'workflow'
type SubmissionScope = SaveScope | 'reload'
type ActiveSubmission = SubmissionScope | null
type ScopeMessages = { error: string | null; success: string | null }

const SAVE_SUCCESS_KEY: Record<SaveScope, TranslationKey> = {
  basic: 'projects.basicSaved',
  yolo: 'projects.yoloSaved',
  workflow: 'projects.workflowSaved',
}

const SAVE_FAILURE_KEY: Record<SaveScope, TranslationKey> = {
  basic: 'projects.edit.updateBasicFailed',
  yolo: 'projects.edit.updateYoloFailed',
  workflow: 'projects.edit.updateWorkflowFailed',
}

const SAVE_LABEL_KEY: Record<SaveScope, TranslationKey> = {
  basic: 'projects.edit.labelBasic',
  yolo: 'projects.edit.labelYolo',
  workflow: 'projects.edit.labelWorkflow',
}

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
  initialTab: TabKey
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
  const [activeTab, setActiveTab] = useState<TabKey>(initialTab)

  // 打开时冻结的权威快照：推送只更新背景数据，不改写这里的草稿与 CAS 基线。
  const [frozenVersion, setFrozenVersion] = useState(project.version)
  const [title, setTitle] = useState(project.title)
  const [description, setDescription] = useState(project.description)
  const [yoloEnabled, setYoloEnabled] = useState(project.yoloEnabled)
  const [draftStates, setDraftStates] = useState<WorkflowDraftState[]>(() => toDraftStates(project.workflow))
  const [selectedKey, setSelectedKey] = useState<string | null>(null)

  const [activeSubmission, setActiveSubmission] = useState<ActiveSubmission>(null)
  const activeSubmissionRef = useRef<ActiveSubmission>(null)

  const [messages, setMessages] = useState<Record<SaveScope, ScopeMessages>>({
    basic: { error: null, success: null },
    yolo: { error: null, success: null },
    workflow: { error: null, success: null },
  })
  // 仅 409 / 网络结果未知时给出「加载最新 + 保留/放弃草稿」的显式恢复入口。
  const [recoveryMessage, setRecoveryMessage] = useState<string | null>(null)

  const isMountedRef = useRef(true)
  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
    }
  }, [])

  const constraintsQuery = useQuery({
    queryKey: queryKeys.projects.snapshot(project.id),
    queryFn: () => api.getProjectSnapshot(project.id),
    enabled: !snapshot && activeTab === 'workflow',
  })
  const constraints = snapshot ?? constraintsQuery.data
  const constraintsError = snapshotError ?? constraintsQuery.error
  const constraintsReady = Boolean(constraints) && !constraintsError
  const referencedStates = useMemo(
    () => new Set(constraints?.referencedStateCodes ?? []),
    [constraints?.referencedStateCodes],
  )
  const activeRun = hasActiveRun(constraints?.issues)
  const workflowLockedReason = !constraintsReady
    ? constraintsError instanceof Error
      ? constraintsError.message
      : t('projects.edit.referencesLoading')
    : (constraints?.project ?? project).archivedAt
    ? t('projects.edit.lockedArchived')
    : activeRun
      ? t('projects.edit.lockedActiveRun')
      : null

  const preserveAgentNames = useMemo(
    () => draftStates.map((state) => state.agent).filter((name): name is string => Boolean(name)),
    [draftStates],
  )
  const { agentOptions, catalogNames, isLoading: agentsLoading } = useCatalogAgentNames({
    preserveNames: preserveAgentNames,
    enabled: true,
  })
  const missingCatalogAgents = agentOptions.filter((name) => !catalogNames.includes(name))

  const { data: environments = [] } = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
  })

  const setScopeMessage = (scope: SaveScope, patch: Partial<ScopeMessages>) => {
    setMessages((prev) => ({ ...prev, [scope]: { ...prev[scope], ...patch } }))
  }

  const beginSubmission = (scope: SubmissionScope): boolean => {
    if (activeSubmissionRef.current !== null) {
      return false
    }
    activeSubmissionRef.current = scope
    setActiveSubmission(scope)
    setRecoveryMessage(null)
    return true
  }

  const endSubmission = () => {
    if (isMountedRef.current) {
      activeSubmissionRef.current = null
      setActiveSubmission(null)
    }
  }

  /**
   * 三个子表单共用的保存路径：串行互斥、按 scope 校验、成功后推进冻结版本。
   * 只有 409 与传输结果未知（网络中断、5xx、超时/限流）才转入显式恢复；其它错误只提示并允许重试提交。
   */
  const saveProject = async (scope: SaveScope, event: React.FormEvent) => {
    event.preventDefault()
    if (!beginSubmission(scope)) {
      return
    }
    setScopeMessage(scope, { error: null, success: null })
    const validationError = validateScope(scope)
    if (validationError) {
      setScopeMessage(scope, { error: validationError })
      endSubmission()
      return
    }
    try {
      const updated = await submitSave(scope)
      if (!isMountedRef.current) return
      setFrozenVersion(updated.version)
      setScopeMessage(scope, { success: t(SAVE_SUCCESS_KEY[scope]) })
      onSuccess(updated)
    } catch (err) {
      if (!isMountedRef.current) return
      handleSubmissionFailure(err, t(SAVE_FAILURE_KEY[scope]), scope)
    } finally {
      endSubmission()
    }
  }

  const validateScope = (scope: SaveScope): string | null => {
    if (scope === 'basic') {
      return title.trim() ? null : t('projects.edit.nameRequired')
    }
    if (scope === 'yolo') {
      return null
    }
    if (workflowLockedReason) {
      return workflowLockedReason
    }
    const error = validateWorkflowDraft(draftStates, referencedStates)
    return error ? t(workflowErrorKey(error), workflowErrorParams(error)) : null
  }

  const submitSave = (scope: SaveScope): Promise<ProjectDTO> => {
    switch (scope) {
      case 'basic':
        return api.updateProject(project.id, {
          expectedVersion: frozenVersion,
          title: title.trim(),
          description: description.trim(),
        })
      case 'yolo':
        return api.updateYolo(project.id, {
          expectedVersion: frozenVersion,
          yoloEnabled,
        })
      case 'workflow':
        return api.updateWorkflow(project.id, {
          expectedVersion: frozenVersion,
          workflow: buildWorkflowDTO(draftStates),
        })
    }
  }

  const handleSubmissionFailure = (err: unknown, fallbackMessage: string, scope: SaveScope) => {
    if (isConflictError(err) || (err instanceof ApiError && isNetworkUnknownError(err))) {
      setScopeMessage(scope, { error: null })
      setRecoveryMessage(t('projects.edit.unconfirmed', { label: t(SAVE_LABEL_KEY[scope]) }))
      return
    }
    setScopeMessage(scope, { error: err instanceof Error ? err.message : fallbackMessage })
  }

  const handleRecover = async (discardDraft: boolean) => {
    if (activeSubmissionRef.current !== null) {
      return
    }
    activeSubmissionRef.current = 'reload'
    setActiveSubmission('reload')
    setRecoveryMessage(null)
    try {
      const fresh = await api.getProject(project.id)
      if (!isMountedRef.current) return
      setFrozenVersion(fresh.version)
      if (discardDraft) {
        setTitle(fresh.title)
        setDescription(fresh.description)
        setYoloEnabled(fresh.yoloEnabled)
        setDraftStates(toDraftStates(fresh.workflow))
        setSelectedKey(null)
      }
      setMessages((prev) => ({
        basic: { ...prev.basic, error: null },
        yolo: { ...prev.yolo, error: null },
        workflow: { ...prev.workflow, error: null },
      }))
    } catch (err) {
      if (!isMountedRef.current) return
      setScopeMessage('basic', {
        error: err instanceof Error ? err.message : t('projects.edit.reloadFailed'),
      })
    } finally {
      if (isMountedRef.current) {
        activeSubmissionRef.current = null
        setActiveSubmission(null)
      }
    }
  }

  const updateState = (key: string, patch: Partial<WorkflowDraftState>) => {
    setDraftStates((prev) =>
      prev.map((state) => (state.key === key ? { ...state, ...patch } : state)),
    )
    setScopeMessage('workflow', { success: null })
  }

  /** 改码是重命名：同步改写所有引用该编码的转移边，保持图自洽。 */
  const renameState = (key: string, rawCode: string) => {
    const nextCode = rawCode.toUpperCase().trim()
    setDraftStates((prev) => {
      const target = prev.find((state) => state.key === key)
      if (!constraintsReady || referencedStates.has(target?.state ?? '')) {
        return prev
      }
      const oldCode = target?.state ?? ''
      return prev.map((state) => {
        if (state.key === key) {
          return { ...state, state: nextCode }
        }
        if (oldCode && oldCode !== nextCode && state.next.includes(oldCode)) {
          return { ...state, next: state.next.map((code) => (code === oldCode ? nextCode : code)) }
        }
        return state
      })
    })
    setScopeMessage('workflow', { success: null })
  }

  const toggleNext = (key: string, target: string, checked: boolean) => {
    setDraftStates((prev) =>
      prev.map((state) => {
        if (state.key !== key) return state
        const next = checked
          ? [...state.next, target]
          : state.next.filter((code) => code !== target)
        return { ...state, next }
      }),
    )
    setScopeMessage('workflow', { success: null })
  }

  const moveState = (index: number, delta: number) => {
    setDraftStates((prev) => {
      const target = index + delta
      if (target < 0 || target >= prev.length) {
        return prev
      }
      const next = [...prev]
      const [moved] = next.splice(index, 1)
      next.splice(target, 0, moved)
      return next
    })
    setScopeMessage('workflow', { success: null })
  }

  const addState = () => {
    const created = createDraftState()
    setDraftStates((prev) => [...prev, created])
    setSelectedKey(created.key)
    setScopeMessage('workflow', { success: null })
  }

  const removeState = (key: string) => {
    if (!constraintsReady || referencedStates.has(draftStates.find((state) => state.key === key)?.state ?? '')) {
      return
    }
    setDraftStates((prev) => prev.filter((state) => state.key !== key))
    setSelectedKey((current) => (current === key ? null : current))
    setScopeMessage('workflow', { success: null })
  }

  const isPending = activeSubmission !== null
  const isReloading = activeSubmission === 'reload'

  const recoveryBanner = recoveryMessage ? (
    <div className="form-error-banner" role="alert">
      <div className="edit-project-recovery">
        <span>
          <AlertTriangle size={16} aria-hidden="true" />
          {recoveryMessage}
        </span>
        <div className="edit-project-recovery-actions">
          <Button variant="ghost" disabled={isPending} onClick={() => void handleRecover(false)}>
            {t('projects.edit.reloadKeepDraft')}
          </Button>
          <Button variant="ghost" disabled={isPending} onClick={() => void handleRecover(true)}>
            {t('projects.edit.reloadDiscardDraft')}
          </Button>
        </div>
      </div>
    </div>
  ) : null

  const basicPanel = (
    <div className="modal-body edit-project-panel">
      <form className="edit-project-section" onSubmit={(event) => void saveProject('basic', event)}>
        <h3 className="edit-project-section-title">{t('projects.edit.tabBasic')}</h3>

        {messages.basic.error && (
          <div className="form-error-banner" role="alert">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>{messages.basic.error}</span>
          </div>
        )}
        {messages.basic.success && (
          <div className="form-success-banner" role="status">
            <CheckCircle2 size={16} aria-hidden="true" />
            <span>{messages.basic.success}</span>
          </div>
        )}

        <label className="edit-project-field" htmlFor="edit-project-title">
          <FieldLabel required>{t('projects.edit.projectName')}</FieldLabel>
          <TextInput
            id="edit-project-title"
            value={title}
            invalid={!title.trim()}
            disabled={isPending}
            onChange={(event) => {
              setTitle(event.target.value)
              setScopeMessage('basic', { success: null })
            }}
          />
        </label>

        <label className="edit-project-field" htmlFor="edit-project-desc">
          <FieldLabel>{t('projects.edit.description')}</FieldLabel>
          <TextArea
            id="edit-project-desc"
            rows={3}
            value={description}
            disabled={isPending}
            onChange={(event) => {
              setDescription(event.target.value)
              setScopeMessage('basic', { success: null })
            }}
          />
        </label>

        <div className="edit-project-form-actions">
          <Button
            type="submit"
            loading={activeSubmission === 'basic'}
            disabled={isPending && activeSubmission !== 'basic'}
          >
            {activeSubmission === 'basic' ? t('projects.edit.saving') : t('projects.saveBasic')}
          </Button>
        </div>
      </form>

      <form className="edit-project-section" onSubmit={(event) => void saveProject('yolo', event)}>
        <h3 className="edit-project-section-title">{t('projects.edit.yoloSection')}</h3>

        {messages.yolo.error && (
          <div className="form-error-banner" role="alert">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>{messages.yolo.error}</span>
          </div>
        )}
        {messages.yolo.success && (
          <div className="form-success-banner" role="status">
            <CheckCircle2 size={16} aria-hidden="true" />
            <span>{messages.yolo.success}</span>
          </div>
        )}

        <div className="field-label-row">
          <Checkbox
            checked={yoloEnabled}
            disabled={isPending}
            label={t('projects.edit.yoloToggle')}
            onChange={(checked) => {
              setYoloEnabled(checked)
              setScopeMessage('yolo', { success: null })
            }}
          />
          <HelpHint
            label={t('shared.helpAbout', { topic: t('projects.edit.yoloToggle') })}
          >
            {t('projects.edit.yoloHelp')}
          </HelpHint>
        </div>

        <div className="edit-project-form-actions">
          <Button
            type="submit"
            loading={activeSubmission === 'yolo'}
            disabled={isPending && activeSubmission !== 'yolo'}
          >
            {activeSubmission === 'yolo' ? t('projects.edit.saving') : t('projects.saveYolo')}
          </Button>
        </div>
      </form>
    </div>
  )

  const workflowPanel = (
    <form className="modal-card-form" onSubmit={(event) => void saveProject('workflow', event)}>
      <div className="modal-body">
        {workflowLockedReason && (
          <div className="form-warning-banner" role="status">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>{workflowLockedReason}</span>
          </div>
        )}
        {constraintsError && (
          <Button
            variant="ghost"
            disabled={isPending || constraintsQuery.isFetching}
            onClick={() => void constraintsQuery.refetch()}
          >
            {t('projects.retry')}
          </Button>
        )}

        {messages.workflow.error && (
          <div className="form-error-banner" role="alert">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>{messages.workflow.error}</span>
          </div>
        )}
        {messages.workflow.success && (
          <div className="form-success-banner" role="status">
            <CheckCircle2 size={16} aria-hidden="true" />
            <span>{messages.workflow.success}</span>
          </div>
        )}

        {missingCatalogAgents.length > 0 && (
          <div className="form-warning-banner" role="status">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>
              {t('projects.edit.missingAgents', { names: missingCatalogAgents.join(', ') })}
            </span>
          </div>
        )}

        <WorkflowStageEditor
          draftStates={draftStates}
          selectedKey={selectedKey}
          referencedStates={referencedStates}
          constraintsReady={constraintsReady}
          isPending={isPending}
          agentOptions={agentOptions}
          agentsLoading={agentsLoading}
          environments={environments}
          onSelect={setSelectedKey}
          onRename={renameState}
          onUpdate={updateState}
          onToggleNext={toggleNext}
          onMove={moveState}
          onAdd={addState}
          onRemove={removeState}
        />

        <div className="edit-project-form-actions">
          <Button
            type="submit"
            loading={activeSubmission === 'workflow'}
            disabled={(isPending && activeSubmission !== 'workflow') || Boolean(workflowLockedReason)}
          >
            {activeSubmission === 'workflow' ? t('projects.edit.saving') : t('projects.workflowSave')}
          </Button>
        </div>
      </div>
    </form>
  )

  return (
    <Dialog
      className="resource-modal-card edit-project-modal-card modal-touch-target"
      title={t('projects.edit.title')}
      headerIcon={<Pencil size={18} aria-hidden="true" />}
      pending={isPending}
      onClose={onClose}
    >
      {recoveryBanner}

      {isReloading && (
        <div className="edit-project-reloading" role="status">
          <RefreshCw size={14} className="animate-spin" aria-hidden="true" />
          <span>{t('projects.edit.reloadRunning')}</span>
        </div>
      )}

      <Tabs
        className="edit-project-tabs"
        ariaLabel={t('projects.edit.tabsLabel')}
        activeId={activeTab}
        onChange={(id) => setActiveTab(id as TabKey)}
        tabs={[
          { id: 'basic', label: t('projects.edit.tabBasic') },
          { id: 'workflow', label: t('projects.edit.tabWorkflow') },
        ]}
      >
        {activeTab === 'basic' ? basicPanel : workflowPanel}
      </Tabs>

      <div className="modal-footer">
        <Button variant="ghost" onClick={onClose} disabled={isPending}>
          {t('projects.close')}
        </Button>
      </div>
    </Dialog>
  )
}
