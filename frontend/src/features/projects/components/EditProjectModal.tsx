import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  CheckCircle2,
  Pencil,
  Plus,
  RefreshCw,
  Trash2,
} from 'lucide-react'
import { ApiError, isConflictError } from '@/shared/api/client'
import { environmentService } from '@/shared/api/environment-service'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { Select } from '@/shared/ui/controls/Select'
import { Tabs } from '@/shared/ui/controls/Tabs'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { isNetworkUnknownError } from '../pending-action-sidecar'
import { useCatalogAgentNames } from '../useCatalogAgentNames'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type { ProjectDTO, ProjectSnapshotDTO } from '../types'
import {
  buildWorkflowDTO,
  createDraftState,
  hasActiveRun,
  isReservedStateCode,
  MAX_RUNS,
  parseMaxRuns,
  toDraftStates,
  validateWorkflowDraft,
  workflowErrorKey,
  workflowErrorParams,
  type WorkflowDraftState,
} from '../workflow-draft'

export interface EditProjectModalProps {
  isOpen: boolean
  project: ProjectDTO | null
  /** 看板快照；列表入口在工作流打开时复用既有 snapshot 查询。 */
  snapshot?: ProjectSnapshotDTO
  snapshotError?: unknown
  onClose: () => void
  onSuccess: (updated: ProjectDTO) => void
  api?: ProjectsApi
}

type TabKey = 'basic' | 'workflow'
type ActiveSubmission = 'basic' | 'yolo' | 'workflow' | 'reload' | null

export function EditProjectModal({
  isOpen,
  project,
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
  snapshot?: ProjectSnapshotDTO
  snapshotError?: unknown
  onClose: () => void
  onSuccess: (updated: ProjectDTO) => void
  api: ProjectsApi
}

function EditProjectModalContent({
  project,
  snapshot,
  snapshotError,
  onClose,
  onSuccess,
  api,
}: EditProjectModalContentProps) {
  const { t } = useI18n()
  const [activeTab, setActiveTab] = useState<TabKey>('basic')

  // 打开时冻结的权威快照：推送只更新背景数据，不改写这里的草稿与 CAS 基线。
  const [frozenVersion, setFrozenVersion] = useState(project.version)
  const [title, setTitle] = useState(project.title)
  const [description, setDescription] = useState(project.description)
  const [yoloEnabled, setYoloEnabled] = useState(project.yoloEnabled)
  const [draftStates, setDraftStates] = useState<WorkflowDraftState[]>(() => toDraftStates(project.workflow))
  const [selectedKey, setSelectedKey] = useState<string | null>(null)

  const [activeSubmission, setActiveSubmission] = useState<ActiveSubmission>(null)
  const activeSubmissionRef = useRef<ActiveSubmission>(null)

  const [basicErrorMessage, setBasicErrorMessage] = useState<string | null>(null)
  const [basicSuccessMessage, setBasicSuccessMessage] = useState<string | null>(null)
  const [yoloErrorMessage, setYoloErrorMessage] = useState<string | null>(null)
  const [yoloSuccessMessage, setYoloSuccessMessage] = useState<string | null>(null)
  const [workflowErrorMessage, setWorkflowErrorMessage] = useState<string | null>(null)
  const [workflowSuccessMessage, setWorkflowSuccessMessage] = useState<string | null>(null)
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

  const selectedState =
    draftStates.find((state) => state.key === selectedKey) ?? draftStates[0] ?? null

  const beginSubmission = (scope: Exclude<ActiveSubmission, null>): boolean => {
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

  const handleSaveBasic = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!beginSubmission('basic')) {
      return
    }
    const trimmedTitle = title.trim()
    if (!trimmedTitle) {
      setBasicErrorMessage(t('projects.edit.nameRequired'))
      setBasicSuccessMessage(null)
      endSubmission()
      return
    }
    setBasicErrorMessage(null)
    setBasicSuccessMessage(null)
    try {
      const updated = await api.updateProject(project.id, {
        expectedVersion: frozenVersion,
        title: trimmedTitle,
        description: description.trim(),
      })
      if (!isMountedRef.current) return
      setFrozenVersion(updated.version)
      setBasicSuccessMessage(t('projects.basicSaved'))
      onSuccess(updated)
    } catch (err) {
      if (!isMountedRef.current) return
      handleSubmissionFailure(
        err,
        t('projects.edit.updateBasicFailed'),
        setBasicErrorMessage,
        t('projects.edit.labelBasic'),
      )
    } finally {
      endSubmission()
    }
  }

  const handleSaveYolo = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!beginSubmission('yolo')) {
      return
    }
    setYoloErrorMessage(null)
    setYoloSuccessMessage(null)
    try {
      const updated = await api.updateYolo(project.id, {
        expectedVersion: frozenVersion,
        yoloEnabled,
      })
      if (!isMountedRef.current) return
      setFrozenVersion(updated.version)
      setYoloSuccessMessage(t('projects.yoloSaved'))
      onSuccess(updated)
    } catch (err) {
      if (!isMountedRef.current) return
      handleSubmissionFailure(
        err,
        t('projects.edit.updateYoloFailed'),
        setYoloErrorMessage,
        t('projects.edit.labelYolo'),
      )
    } finally {
      endSubmission()
    }
  }

  const handleSaveWorkflow = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!beginSubmission('workflow')) {
      return
    }
    setWorkflowErrorMessage(null)
    setWorkflowSuccessMessage(null)
    if (workflowLockedReason) {
      setWorkflowErrorMessage(workflowLockedReason)
      endSubmission()
      return
    }
    const validationError = validateWorkflowDraft(draftStates, referencedStates)
    if (validationError) {
      setWorkflowErrorMessage(
        t(workflowErrorKey(validationError), workflowErrorParams(validationError)),
      )
      endSubmission()
      return
    }
    try {
      const updated = await api.updateWorkflow(project.id, {
        expectedVersion: frozenVersion,
        workflow: buildWorkflowDTO(draftStates),
      })
      if (!isMountedRef.current) return
      setFrozenVersion(updated.version)
      setWorkflowSuccessMessage(t('projects.workflowSaved'))
      onSuccess(updated)
    } catch (err) {
      if (!isMountedRef.current) return
      handleSubmissionFailure(
        err,
        t('projects.edit.updateWorkflowFailed'),
        setWorkflowErrorMessage,
        t('projects.edit.labelWorkflow'),
      )
    } finally {
      endSubmission()
    }
  }

  /**
   * 只有 409 与传输结果未知（网络中断、5xx、超时/限流）才需要显式恢复：此时草稿与
   * 版本基线保持不变，用户自行选择加载最新数据的方式。其它错误只提示并允许重试提交。
   */
  const handleSubmissionFailure = (
    err: unknown,
    fallbackMessage: string,
    setError: (message: string | null) => void,
    label: string,
  ) => {
    if (isConflictError(err) || (err instanceof ApiError && isNetworkUnknownError(err))) {
      setError(null)
      setRecoveryMessage(t('projects.edit.unconfirmed', { label }))
      return
    }
    setError(err instanceof Error ? err.message : fallbackMessage)
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
      setBasicErrorMessage(null)
      setYoloErrorMessage(null)
      setWorkflowErrorMessage(null)
    } catch (err) {
      if (!isMountedRef.current) return
      setBasicErrorMessage(err instanceof Error ? err.message : t('projects.edit.reloadFailed'))
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
    setWorkflowSuccessMessage(null)
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
    setWorkflowSuccessMessage(null)
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
    setWorkflowSuccessMessage(null)
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
    setWorkflowSuccessMessage(null)
  }

  const addState = () => {
    const created = createDraftState()
    setDraftStates((prev) => [...prev, created])
    setSelectedKey(created.key)
    setWorkflowSuccessMessage(null)
  }

  const removeState = (key: string) => {
    if (!constraintsReady || referencedStates.has(draftStates.find((state) => state.key === key)?.state ?? '')) {
      return
    }
    setDraftStates((prev) => prev.filter((state) => state.key !== key))
    setSelectedKey((current) => (current === key ? null : current))
    setWorkflowSuccessMessage(null)
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
    <div className="edit-project-panel">
      <form className="edit-project-section" onSubmit={handleSaveBasic}>
        <h3 className="edit-project-section-title">{t('projects.edit.tabBasic')}</h3>

        {basicErrorMessage && (
          <div className="form-error-banner" role="alert">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>{basicErrorMessage}</span>
          </div>
        )}
        {basicSuccessMessage && (
          <div className="form-success-banner" role="status">
            <CheckCircle2 size={16} aria-hidden="true" />
            <span>{basicSuccessMessage}</span>
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
              setBasicSuccessMessage(null)
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
              setBasicSuccessMessage(null)
            }}
          />
        </label>

        <div className="edit-project-form-actions">
          <Button type="submit" disabled={isPending}>
            {activeSubmission === 'basic' ? t('projects.edit.saving') : t('projects.saveBasic')}
          </Button>
        </div>
      </form>

      <form className="edit-project-section" onSubmit={handleSaveYolo}>
        <h3 className="edit-project-section-title">{t('projects.edit.yoloSection')}</h3>

        {yoloErrorMessage && (
          <div className="form-error-banner" role="alert">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>{yoloErrorMessage}</span>
          </div>
        )}
        {yoloSuccessMessage && (
          <div className="form-success-banner" role="status">
            <CheckCircle2 size={16} aria-hidden="true" />
            <span>{yoloSuccessMessage}</span>
          </div>
        )}

        <Checkbox
          checked={yoloEnabled}
          disabled={isPending}
          label={t('projects.edit.yoloToggle')}
          onChange={(checked) => {
            setYoloEnabled(checked)
            setYoloSuccessMessage(null)
          }}
        />

        <div className="edit-project-form-actions">
          <Button type="submit" disabled={isPending}>
            {activeSubmission === 'yolo' ? t('projects.edit.saving') : t('projects.saveYolo')}
          </Button>
        </div>
      </form>
    </div>
  )

  const workflowPanel = (
    <form className="edit-project-workflow" onSubmit={handleSaveWorkflow}>
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

      {workflowErrorMessage && (
        <div className="form-error-banner" role="alert">
          <AlertTriangle size={16} aria-hidden="true" />
          <span>{workflowErrorMessage}</span>
        </div>
      )}
      {workflowSuccessMessage && (
        <div className="form-success-banner" role="status">
          <CheckCircle2 size={16} aria-hidden="true" />
          <span>{workflowSuccessMessage}</span>
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

      <div className="workflow-editor">
        <div
          className="workflow-stage-list"
          role="list"
          aria-label={t('projects.edit.stageListLabel')}
        >
          {draftStates.map((state, index) => {
            const reserved = isReservedStateCode(state.state)
            const active = selectedState?.key === state.key
            const label = state.name.trim() || state.state || t('projects.edit.untitledStage')
            const referenced =
              referencedStates.has(state.state)
              || draftStates.some((other) => other.key !== state.key && other.next.includes(state.state))
            const deleteDisabled = isPending || !constraintsReady || reserved || referenced
            const deleteReason = reserved
              ? t('projects.edit.deleteReservedReason')
              : referenced
                ? t('projects.edit.deleteReferencedReason')
                : undefined
            return (
              <div key={state.key} className={`workflow-stage-row${active ? ' is-active' : ''}`} role="listitem">
                <button
                  type="button"
                  className="workflow-stage-select"
                  aria-pressed={active}
                  onClick={() => setSelectedKey(state.key)}
                >
                  <span className="workflow-stage-order">{index + 1}</span>
                  <span className="workflow-stage-label">{label}</span>
                  <span className="badge badge-state">{state.state || '—'}</span>
                  {reserved ? (
                    <span className="badge badge-archived">{t('projects.edit.badgeReserved')}</span>
                  ) : state.agent ? (
                    <span className="badge badge-agent">{t('projects.edit.badgeAgent')}</span>
                  ) : (
                    <span className="badge">{t('projects.edit.badgeManual')}</span>
                  )}
                  {!state.enabled && (
                    <span className="badge badge-paused">{t('projects.edit.badgeDisabled')}</span>
                  )}
                </button>
                <IconButton
                  label={t('projects.edit.moveUp', { label })}
                  size="compact"
                  disabled={isPending || index === 0}
                  onClick={() => moveState(index, -1)}
                >
                  <ArrowUp aria-hidden="true" />
                </IconButton>
                <IconButton
                  label={t('projects.edit.moveDown', { label })}
                  size="compact"
                  disabled={isPending || index === draftStates.length - 1}
                  onClick={() => moveState(index, 1)}
                >
                  <ArrowDown aria-hidden="true" />
                </IconButton>
                <IconButton
                  label={t('projects.edit.deleteStage', { label })}
                  size="compact"
                  danger
                  disabled={deleteDisabled}
                  title={deleteReason}
                  onClick={() => removeState(state.key)}
                >
                  <Trash2 aria-hidden="true" />
                </IconButton>
              </div>
            )
          })}

          <Button
            variant="ghost"
            size="compact"
            disabled={isPending}
            onClick={addState}
          >
            <Plus size={14} aria-hidden="true" />
            <span>{t('projects.edit.addStage')}</span>
          </Button>
        </div>

        {selectedState ? (
          <div className="workflow-stage-form">
            {(() => {
              const state = selectedState
              const reserved = isReservedStateCode(state.state)
              const targetOptions = draftStates
                .filter((other) => other.key !== state.key && other.state && other.state !== 'BLOCKED')
                .map((other) => ({
                  code: other.state,
                  label: t('projects.edit.stageLabel', {
                    name: other.name.trim() || other.state,
                    state: other.state,
                  }),
                }))
              return (
                <>
                  <label className="edit-project-field" htmlFor="workflow-state-code">
                    <FieldLabel required>{t('projects.edit.stateCode')}</FieldLabel>
                    <TextInput
                      id="workflow-state-code"
                      value={state.state}
                      disabled={isPending || !constraintsReady || reserved || referencedStates.has(state.state)}
                      invalid={!state.state}
                      placeholder={t('projects.edit.stateCodePlaceholder')}
                      onChange={(event) => renameState(state.key, event.target.value)}
                    />
                  </label>

                  <label className="edit-project-field" htmlFor="workflow-state-name">
                    <FieldLabel required>{t('projects.edit.displayName')}</FieldLabel>
                    <TextInput
                      id="workflow-state-name"
                      value={state.name}
                      disabled={isPending}
                      invalid={!state.name.trim()}
                      onChange={(event) => updateState(state.key, { name: event.target.value })}
                    />
                  </label>

                  {reserved ? (
                    <StateBlock title={t('projects.edit.reservedShape')} />
                  ) : (
                    <>
                      <div className="edit-project-field">
                        <FieldLabel>{t('projects.edit.mode')}</FieldLabel>
                        <Select
                          value={state.agent ? 'agent' : 'manual'}
                          disabled={isPending}
                          aria-label={t('projects.edit.mode')}
                          options={[
                            { value: 'manual', label: t('projects.edit.modeManual') },
                            { value: 'agent', label: t('projects.edit.modeAgent') },
                          ]}
                          onChange={(value) =>
                            updateState(
                              state.key,
                              value === 'agent'
                                ? { agent: state.agent ?? (agentOptions[0] ?? '') }
                                : { agent: null, environment: null, maxRuns: '' },
                            )
                          }
                        />
                      </div>

                      {state.agent ? (
                        <>
                          <div className="edit-project-field">
                            <FieldLabel required>{t('projects.edit.agent')}</FieldLabel>
                            <Select
                              value={state.agent}
                              disabled={isPending || agentsLoading}
                              aria-label={t('projects.edit.agent')}
                              options={agentOptions.map((name) => ({ value: name, label: name }))}
                              onChange={(value) => updateState(state.key, { agent: value })}
                            />
                          </div>

                          <div className="edit-project-field">
                            <FieldLabel>{t('projects.edit.environment')}</FieldLabel>
                            <Select
                              value={state.environment ?? ''}
                              disabled={isPending}
                              aria-label={t('projects.edit.environment')}
                              placeholder={t('projects.edit.environmentUnset')}
                              options={[
                                { value: '', label: t('projects.edit.environmentUnset') },
                                ...environmentOptions(environments, state.environment).map(
                                  (name) => ({ value: name, label: name }),
                                ),
                              ]}
                              onChange={(value) =>
                                updateState(state.key, { environment: value || null })
                              }
                            />
                          </div>

                          <div className="edit-project-field">
                            <FieldLabel required>{t('projects.edit.maxRuns')}</FieldLabel>
                            <NumberInput
                              value={state.maxRuns}
                              min={1}
                              max={MAX_RUNS}
                              aria-label={t('projects.edit.maxRuns')}
                              disabled={isPending}
                              invalid={parseMaxRuns(state.maxRuns) === null}
                              onChange={(value) => updateState(state.key, { maxRuns: value })}
                            />
                          </div>
                        </>
                      ) : null}

                      <label className="edit-project-field" htmlFor="workflow-state-instructions">
                        <FieldLabel>{t('projects.edit.instructions')}</FieldLabel>
                        <TextArea
                          id="workflow-state-instructions"
                          rows={3}
                          value={state.instructions}
                          disabled={isPending}
                          onChange={(event) =>
                            updateState(state.key, { instructions: event.target.value })
                          }
                        />
                      </label>

                      <Checkbox
                        checked={state.enabled}
                        disabled={isPending}
                        label={t('projects.edit.enableStage')}
                        onChange={(checked) => updateState(state.key, { enabled: checked })}
                      />
                    </>
                  )}

                  <div className="edit-project-field">
                    <FieldLabel>{t('projects.edit.nextStates')}</FieldLabel>
                    {reserved && state.state !== 'INIT' ? (
                      <p className="edit-project-hint">{t('projects.edit.reservedNoEdges')}</p>
                    ) : targetOptions.length === 0 ? (
                      <p className="edit-project-hint">{t('projects.edit.noNextOptions')}</p>
                    ) : (
                      <div className="workflow-next-options">
                        {targetOptions.map((option) => (
                          <Checkbox
                            key={option.code}
                            checked={state.next.includes(option.code)}
                            disabled={isPending}
                            label={option.label}
                            onChange={(checked) =>
                              toggleNext(state.key, option.code, checked)
                            }
                          />
                        ))}
                      </div>
                    )}
                  </div>
                </>
              )
            })()}
          </div>
        ) : (
          <StateBlock title={t('projects.edit.selectStage')} />
        )}
      </div>

      <div className="edit-project-form-actions">
        <Button type="submit" disabled={isPending || Boolean(workflowLockedReason)}>
          {activeSubmission === 'workflow' ? t('projects.edit.saving') : t('projects.workflowSave')}
        </Button>
      </div>
    </form>
  )

  return (
    <Dialog
      className="resource-modal-card edit-project-modal-card"
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

/** Environment 候选：现有环境名 ∪ 草稿中已配置但当前查询不到的旧名，避免静默丢弃。 */
function environmentOptions(
  environments: ReadonlyArray<{ name: string }>,
  configured: string | null,
): string[] {
  const names = new Set(environments.map((environment) => environment.name))
  if (configured) {
    names.add(configured)
  }
  return Array.from(names).sort((a, b) => a.localeCompare(b))
}
