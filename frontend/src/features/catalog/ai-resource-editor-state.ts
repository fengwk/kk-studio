import { useEffect, useState } from 'react'
import { useI18n } from '@/shared/i18n'
import type { ConfirmModalState } from '@/shared/ui/overlays/confirm-modal'
import type { ResourceFieldKey } from '@/features/catalog/ai-resource-form-validation'
import type {
  AgentDraft,
  ModelDraft,
  ModelGroupDraft,
  ProviderDraft,
  ResourceModal,
} from '@/features/catalog/ai-console-types'
import {
  createAgentEditorPlan,
  createModelEditorPlan,
  createProviderEditorPlan,
} from '@/features/catalog/ai-resource-editor-open-plans'
import type { ResourceEditorPlan } from '@/features/catalog/ai-resource-editor-plan-types'

/**
 * 编辑器草稿与校验状态：草稿按资源类型各存一份，打开计划时整体替换；
 * 编辑器开关与表单校验状态同生命周期（一起清空）。
 */
export function useResourceDrafts() {
  const [providerDraft, setProviderDraft] = useState<ProviderDraft>(
    () => createProviderEditorPlan().providerDraft,
  )
  const [modelDraft, setModelDraft] = useState<ModelDraft>(
    () => createModelEditorPlan([]).modelDraft,
  )
  const [modelGroupDraft, setModelGroupDraft] = useState<ModelGroupDraft>(
    () => createModelEditorPlan([]).modelGroupDraft,
  )
  const [agentDraft, setAgentDraft] = useState<AgentDraft>(
    () => createAgentEditorPlan([]).agentDraft,
  )

  /** 当前编辑器看到的四份草稿快照，用于校验与提交。 */
  function drafts() {
    return { providerDraft, modelDraft, modelGroupDraft, agentDraft }
  }

  function applyPlan(plan: ResourceEditorPlan) {
    if (plan.kind === 'provider') {
      setProviderDraft(plan.providerDraft)
      return
    }
    if (plan.kind === 'model') {
      setModelDraft(plan.modelDraft)
      setModelGroupDraft(plan.modelGroupDraft)
      return
    }
    setAgentDraft(plan.agentDraft)
  }

  return {
    providerDraft,
    modelDraft,
    modelGroupDraft,
    agentDraft,
    drafts,
    applyPlan,
    setProviderDraft,
    setModelDraft,
    setModelGroupDraft,
    setAgentDraft,
  }
}

/** 编辑器内的表单错误：banner 文案与逐字段错误总是一起设置、一起清空。 */
export function useResourceFormErrors(modal: ResourceModal | null) {
  const [formError, setFormError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<ResourceFieldKey, string>>>({})

  function clear() {
    setFormError('')
    setFieldErrors({})
  }

  /** 打开/关闭编辑器时校验状态随之清空；错误只在一次打开会话内有意义。 */
  useEffect(clear, [modal])

  /** 保存失败只影响 banner；已有字段级错误时不再追加 general，避免重复提示。 */
  function showFailure(message: string) {
    setFormError(message)
    setFieldErrors((current) => (Object.keys(current).length > 0 ? current : { general: message }))
  }

  return { formError, fieldErrors, setFormError, setFieldErrors, clear, showFailure }
}

/** 删除确认弹窗的文案与待确认动作。 */
export function useResourceDeleteConfirm() {
  const { t } = useI18n()
  const [deleteConfirm, setDeleteConfirm] = useState<ConfirmModalState | null>(null)

  function requestDelete(options: {
    titleKey: 'ai.catalog.deleteProviderTitle' | 'ai.catalog.deleteModelTitle' | 'ai.catalog.deleteAgentTitle'
    descriptionKey:
      | 'ai.catalog.deleteProviderDescription'
      | 'ai.catalog.deleteModelDescription'
      | 'ai.catalog.deleteAgentDescription'
    name: string
    onConfirm: () => void
  }) {
    setDeleteConfirm({
      title: t(options.titleKey),
      description: t(options.descriptionKey, { name: options.name }),
      confirmLabel: t('ai.catalog.action.confirmDelete'),
      tone: 'danger',
      onConfirm: options.onConfirm,
    })
  }

  return {
    deleteConfirm,
    requestDelete,
    closeDeleteConfirm: () => setDeleteConfirm(null),
  }
}

/** 打开计划与草稿替换是同一步：计划为空表示目标已不存在，不改动当前编辑器。 */
export function applyEditorPlan(
  plan: ResourceEditorPlan | null,
  openModal: (modal: ResourceModal) => void,
  drafts: ReturnType<typeof useResourceDrafts>,
) {
  if (!plan) {
    return
  }
  openModal(plan.modal)
  drafts.applyPlan(plan)
}
