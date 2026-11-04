import { useEffect, useState, type FormEventHandler } from 'react'
import {
  createAgentEditorPlan,
  createModelEditorPlan,
  createProviderEditorPlan,
  editAgentEditorPlan,
  editModelEditorPlan,
  editProviderEditorPlan,
} from '@/features/catalog/ai-resource-editor-open-plans'
import type { ResourceEditorPlan } from '@/features/catalog/ai-resource-editor-plan-types'
import type {
  AgentDraft,
  ModelDraft,
  ModelGroupDraft,
  ProviderDraft,
  ResourceModal,
} from '@/features/catalog/ai-console-types'
import {
  normalizeAgentDraftDefaultVariant,
  normalizeCreateAgentDraftSelection,
  normalizeCreateModelDraftProvider,
  normalizeEditModelDraftProvider,
  normalizeModelDraftDefaultVariant,
} from '@/features/catalog/ai-draft-normalizers'
import { buildResourceSubmitPlan } from '@/features/catalog/ai-resource-editor-submit-plans'
import {
  validateResourceDraft,
  type ResourceFieldKey,
} from '@/features/catalog/ai-resource-form-validation'
import { useAiConsoleResourceMutations } from '@/features/catalog/useAiConsoleResourceMutations'
import {
  useAiConsoleResourceQueries,
  type AiConsoleResourceQueryEnabled,
} from '@/features/catalog/useAiConsoleResourceQueries'
import type { ConfirmModalState } from '@/shared/ui/overlays/confirm-modal'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import { useI18n } from '@/shared/i18n'

/**
 * Catalog 资源编辑器控制器：资源查询、编辑器草稿与打开计划、保存校验、
 * 删除确认和 Dialog props 都收敛在这里；页面控制器只负责搜索与面板装配。
 */
export function useAiConsoleResourceController(
  enabled: AiConsoleResourceQueryEnabled,
) {
  const { t, locale } = useI18n()
  const { queries, resources } = useAiConsoleResourceQueries(enabled)
  const { providers, models, agents, toolCatalog, skills } = resources
  const [resourceModal, setResourceModal] = useState<ResourceModal | null>(null)
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
  const [deleteConfirm, setDeleteConfirm] = useState<ConfirmModalState | null>(null)
  const [formError, setFormError] = useState<string>('')
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<ResourceFieldKey, string>>>({})

  useEffect(() => {
    if (!resourceModal) {
      return
    }

    if (resourceModal.kind === 'model') {
      setModelDraft((currentDraft) => {
        const normalizedProvider =
          resourceModal.mode === 'edit'
            ? normalizeEditModelDraftProvider(currentDraft, currentDraft.providerName)
            : normalizeCreateModelDraftProvider(currentDraft, providers)
        return normalizeModelDraftDefaultVariant(normalizedProvider)
      })
      return
    }

    if (resourceModal.kind === 'agent') {
      setAgentDraft((currentDraft) =>
        resourceModal.mode === 'edit'
          ? normalizeAgentDraftDefaultVariant(currentDraft, models)
          : normalizeCreateAgentDraftSelection(currentDraft, models),
      )
    }
  }, [models, providers, resourceModal])

  function closeResourceModal() {
    setResourceModal(null)
  }

  function applyResourceEditorPlan(plan: ResourceEditorPlan | null) {
    if (!plan) {
      return
    }
    setResourceModal(plan.modal)
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

  function closeDeleteConfirm() {
    setDeleteConfirm(null)
  }

  const mutations = useAiConsoleResourceMutations({
    onResourceSaved: closeResourceModal,
    onDeleteCompleted: closeDeleteConfirm,
  })

  function deleteProvider(providerName: string, expectedVersion: string) {
    setDeleteConfirm({
      title: t('ai.catalog.deleteProviderTitle'),
      description: t('ai.catalog.deleteProviderDescription', { name: providerName }),
      confirmLabel: t('ai.catalog.action.confirmDelete'),
      tone: 'danger',
      onConfirm: () => mutations.deleteProvider(providerName, expectedVersion),
    })
  }

  function deleteModel(modelName: string, expectedVersion: string) {
    setDeleteConfirm({
      title: t('ai.catalog.deleteModelTitle'),
      description: t('ai.catalog.deleteModelDescription', { name: modelName }),
      confirmLabel: t('ai.catalog.action.confirmDelete'),
      tone: 'danger',
      onConfirm: () => mutations.deleteModel(modelName, expectedVersion),
    })
  }

  function deleteAgent(agentName: string, expectedVersion: string) {
    setDeleteConfirm({
      title: t('ai.catalog.deleteAgentTitle'),
      description: t('ai.catalog.deleteAgentDescription', { name: agentName }),
      confirmLabel: t('ai.catalog.action.confirmDelete'),
      tone: 'danger',
      onConfirm: () => mutations.deleteAgent(agentName, expectedVersion),
    })
  }

  // 打开/关闭编辑器时清空校验状态
  useEffect(() => {
    setFormError('')
    setFieldErrors({})
  }, [resourceModal])

  // API 错误：转成用户可读文案，只显示在模态内
  useEffect(() => {
    if (!resourceModal || !mutations.resourceMutationError) {
      return
    }
    const message = toUserFacingErrorMessage(mutations.resourceMutationError)
    setFormError(message)
    setFieldErrors((current) =>
      Object.keys(current).length > 0 ? current : { general: message },
    )
  }, [resourceModal, mutations.resourceMutationError, locale])

  const submitResource: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (!resourceModal) {
      return
    }

    const drafts = {
      providerDraft,
      modelDraft,
      modelGroupDraft,
      agentDraft,
    }
    const validation = validateResourceDraft(resourceModal, drafts)
    if (!validation.ok) {
      setFormError(validation.message)
      setFieldErrors(validation.fields)
      const form = event.currentTarget as HTMLFormElement
      const body = form.querySelector('.modal-body')
      body?.scrollTo({ top: 0, behavior: 'smooth' })
      // 优先滚到首个标红字段，便于看见「思考强度」等具体项
      window.requestAnimationFrame(() => {
        const firstError =
          form.querySelector('.form-group.is-error') ||
          form.querySelector('.capability-picker.is-error') ||
          form.querySelector('.field-error')
        firstError?.scrollIntoView({ block: 'center', behavior: 'smooth' })
      })
      return
    }
    setFormError('')
    setFieldErrors({})

    const plan = buildResourceSubmitPlan(resourceModal, drafts)

    if (plan.kind === 'provider') {
      if (plan.mode === 'edit') {
        mutations.updateProvider(plan.name, plan.data)
      } else {
        mutations.createProvider(plan.data)
      }
      return
    }

    if (plan.kind === 'model') {
      if (plan.mode === 'edit') {
        mutations.updateModel(plan.name, plan.data)
      } else {
        mutations.createModel(plan.data)
      }
      return
    }

    if (plan.mode === 'edit') {
      mutations.updateAgent(plan.name, plan.data)
    } else {
      mutations.createAgent(plan.data)
    }
  }

  return {
    queries,
    resources,
    resourceMutationError: mutations.resourceMutationError,
    deletePending: {
      provider: mutations.providerDeletePending,
      model: mutations.modelDeletePending,
      agent: mutations.agentDeletePending,
    },
    resourceEditorModal: {
      modal: resourceModal,
      providers,
      models,
      agents,
      toolCatalog,
      skills,
      skillsLoading: queries.skills.isLoading,
      skillsError: queries.skills.error,
      providerDraft,
      modelDraft,
      modelGroupDraft,
      agentDraft,
      pending: mutations.resourceEditorPending,
      formError,
      fieldErrors,
      onClose: closeResourceModal,
      onProviderDraftChange: (draft: ProviderDraft) => {
        setFormError('')
        setFieldErrors({})
        setProviderDraft(draft)
      },
      onModelDraftChange: (draft: ModelDraft) => {
        setFormError('')
        setFieldErrors({})
        setModelDraft(draft)
      },
      onModelGroupDraftChange: (draft: ModelGroupDraft) => {
        setFormError('')
        setFieldErrors({})
        setModelGroupDraft(draft)
      },
      /** 仅创建态切换 Tab；两份草稿都保留在 state 中。 */
      onModelCreateKindChange: (resourceKind: 'MODEL' | 'GROUP') =>
        setResourceModal((current) =>
          current && current.kind === 'model' && current.mode === 'create'
            ? { ...current, resourceKind }
            : current,
        ),
      onAgentDraftChange: (draft: AgentDraft) => {
        setFormError('')
        setFieldErrors({})
        setAgentDraft(draft)
      },
      onSubmit: submitResource,
    },
    deleteConfirmModal: {
      modal: deleteConfirm,
      pending: mutations.deleteConfirmPending,
      onClose: closeDeleteConfirm,
    },
    editorActions: {
      createProvider: () => applyResourceEditorPlan(createProviderEditorPlan()),
      editProvider: (providerName: string) =>
        applyResourceEditorPlan(editProviderEditorPlan(providers, providerName)),
      createModel: () => applyResourceEditorPlan(createModelEditorPlan(providers)),
      editModel: (modelName: string) =>
        applyResourceEditorPlan(editModelEditorPlan(models, modelName)),
      createAgent: () => applyResourceEditorPlan(createAgentEditorPlan(models)),
      editAgent: (agentName: string) =>
        applyResourceEditorPlan(editAgentEditorPlan(agents, models, agentName)),
    },
    deleteResource: {
      provider: deleteProvider,
      model: deleteModel,
      agent: deleteAgent,
    },
  }
}
