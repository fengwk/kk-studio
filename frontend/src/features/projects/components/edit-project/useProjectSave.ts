import { useEffect, useRef, useState } from 'react'
import { ApiError, isConflictError } from '@/shared/api/client'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import type { ProjectDTO } from '@/shared/api/generated/contracts'
import { isNetworkUnknownError } from '../../pending-action-sidecar'
import type { ProjectsApi } from '../../projects-api'
import {
  buildWorkflowDTO,
  validateWorkflowDraft,
  workflowErrorKey,
  workflowErrorParams,
  type WorkflowDraftState,
} from '../../workflow-draft'

export type SaveScope = 'basic' | 'yolo' | 'workflow'
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

interface UseProjectSaveInput {
  projectId: string
  api: ProjectsApi
  frozenVersion: string
  setFrozenVersion: (version: string) => void
  title: string
  description: string
  yoloEnabled: boolean
  draftStates: WorkflowDraftState[]
  referencedStates: ReadonlySet<string>
  workflowLockedReason: string | null
  onSuccess: (updated: ProjectDTO) => void
  /** 用权威版本覆盖全部输入与工作流草稿；只在用户明确放弃草稿时调用。 */
  onDiscardDraft: (fresh: ProjectDTO) => void
}

/**
 * 项目保存：三个子表单串行互斥、按 scope 校验，成功后推进冻结版本。
 *
 * <p>只有 409 与传输结果未知（网络中断、5xx、超时/限流）才转入显式恢复；其它错误只提示并允许重试提交。
 * 恢复时始终取回权威版本，丢弃草稿与否由用户选择。
 */
export function useProjectSave({
  projectId,
  api,
  frozenVersion,
  setFrozenVersion,
  title,
  description,
  yoloEnabled,
  draftStates,
  referencedStates,
  workflowLockedReason,
  onSuccess,
  onDiscardDraft,
}: UseProjectSaveInput) {
  const { t } = useI18n()
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
        return api.updateProject(projectId, {
          expectedVersion: frozenVersion,
          title: title.trim(),
          description: description.trim(),
        })
      case 'yolo':
        return api.updateYolo(projectId, {
          expectedVersion: frozenVersion,
          yoloEnabled,
        })
      case 'workflow':
        return api.updateWorkflow(projectId, {
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

  const save = async (scope: SaveScope, event: React.FormEvent) => {
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

  /** 取回权威版本；discardDraft 为真时同时用权威值覆盖输入与工作流草稿。 */
  const recover = async (discardDraft: boolean) => {
    if (activeSubmissionRef.current !== null) {
      return
    }
    activeSubmissionRef.current = 'reload'
    setActiveSubmission('reload')
    setRecoveryMessage(null)
    try {
      const fresh = await api.getProject(projectId)
      if (!isMountedRef.current) return
      setFrozenVersion(fresh.version)
      setMessages((prev) => ({
        basic: { ...prev.basic, error: null },
        yolo: { ...prev.yolo, error: null },
        workflow: { ...prev.workflow, error: null },
      }))
      if (discardDraft) {
        onDiscardDraft(fresh)
      }
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

  return {
    activeSubmission,
    isPending: activeSubmission !== null,
    isReloading: activeSubmission === 'reload',
    messages,
    setScopeMessage,
    recoveryMessage,
    save,
    recover,
  }
}
