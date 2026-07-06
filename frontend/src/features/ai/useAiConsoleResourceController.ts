import { useEffect, useState, type FormEventHandler } from 'react'
import {
  buildResourceSubmitPlan,
  createAgentEditorPlan,
  createModelEditorPlan,
  createProviderEditorPlan,
  editAgentEditorPlan,
  editModelEditorPlan,
  editProviderEditorPlan,
  type ResourceEditorPlan,
} from '@/features/ai/ai-resource-editor-plans'
import type { AgentDraft, ConfirmModalState, ModelDraft, ProviderDraft, ResourceModal } from '@/features/ai/ai-console-types'
import { normalizeAgentDraftSelection, normalizeModelDraftDefaultVariant, normalizeModelDraftProvider } from '@/features/ai/ai-draft-normalizers'
import type { AgentResourceId } from '@/shared/api/contracts'
import { useAiConsoleResourceMutations } from '@/features/ai/useAiConsoleResourceMutations'
import { useAiConsoleResourceQueries } from '@/features/ai/useAiConsoleResourceQueries'

export function useAiConsoleResourceController() {
  const [resourceModal, setResourceModal] = useState<ResourceModal | null>(null)
  const [deleteConfirm, setDeleteConfirm] = useState<ConfirmModalState | null>(null)
  const [providerDraft, setProviderDraft] = useState<ProviderDraft>(() => createProviderEditorPlan().providerDraft)
  const [modelDraft, setModelDraft] = useState<ModelDraft>(() => createModelEditorPlan([], []).modelDraft)
  const [agentDraft, setAgentDraft] = useState<AgentDraft>(() => createAgentEditorPlan([]).agentDraft)

  const { providersQuery, modelsQuery, agentsQuery, providers, models, agents } = useAiConsoleResourceQueries()

  const closeResourceModal = () => setResourceModal(null)
  const closeDeleteConfirm = () => setDeleteConfirm(null)
  const mutations = useAiConsoleResourceMutations({
    onResourceSaved: closeResourceModal,
    onDeleteCompleted: closeDeleteConfirm,
  })

  useEffect(() => {
    if (!resourceModal) {
      return
    }

    if (resourceModal.kind === 'model') {
      const preferredProviderName = resourceModal.mode === 'edit' ? models.find((model) => model.id === resourceModal.id)?.providerName : undefined
      setModelDraft((currentDraft) => normalizeModelDraftDefaultVariant(normalizeModelDraftProvider(currentDraft, providers, preferredProviderName)))
      return
    }

    if (resourceModal.kind === 'agent') {
      const preferredSelection =
        resourceModal.mode === 'edit'
          ? agents.find((agent) => agent.id === resourceModal.id)
          : undefined
      setAgentDraft((currentDraft) =>
        normalizeAgentDraftSelection(currentDraft, models, preferredSelection && {
          defaultProvider: preferredSelection.defaultProviderName,
          defaultModel: preferredSelection.defaultModelName,
          defaultVariant: preferredSelection.defaultVariant,
        }),
      )
    }
  }, [agents, models, providers, resourceModal])

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
      return
    }
    setAgentDraft(plan.agentDraft)
  }

  function openCreateProvider() {
    applyResourceEditorPlan(createProviderEditorPlan())
  }

  function openEditProvider(providerId: AgentResourceId) {
    applyResourceEditorPlan(editProviderEditorPlan(providers, providerId))
  }

  function openCreateModel() {
    applyResourceEditorPlan(createModelEditorPlan(models, providers))
  }

  function openEditModel(modelId: AgentResourceId) {
    applyResourceEditorPlan(editModelEditorPlan(models, modelId))
  }

  function openCreateAgent() {
    applyResourceEditorPlan(createAgentEditorPlan(models))
  }

  function openEditAgent(agentId: AgentResourceId) {
    applyResourceEditorPlan(editAgentEditorPlan(agents, models, agentId))
  }

  function deleteProvider(providerName: string, providerId: AgentResourceId) {
    setDeleteConfirm({
      title: '删除 Provider',
      description: `将删除 Provider ${providerName}。`,
      confirmLabel: '确认删除',
      tone: 'danger',
      onConfirm: () => mutations.deleteProvider(providerId),
    })
  }

  function deleteModel(providerName: string, modelName: string, modelId: AgentResourceId) {
    setDeleteConfirm({
      title: '删除 Model',
      description: `将删除 Model ${providerName}/${modelName}。`,
      confirmLabel: '确认删除',
      tone: 'danger',
      onConfirm: () => mutations.deleteModel(modelId),
    })
  }

  function deleteAgent(agentName: string, agentId: AgentResourceId) {
    setDeleteConfirm({
      title: '删除 Agent',
      description: `将删除 Agent ${agentName}。已有会话会保留，但不能再用该 Agent 新建运行。`,
      confirmLabel: '确认删除',
      tone: 'danger',
      onConfirm: () => mutations.deleteAgent(agentId),
    })
  }

  const submitResource: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (!resourceModal) {
      return
    }

    const plan = buildResourceSubmitPlan(resourceModal, { providerDraft, modelDraft, agentDraft })

    if (plan.kind === 'provider') {
      if (plan.mode === 'edit') {
        mutations.updateProvider(plan.id, plan.data)
      } else {
        mutations.createProvider(plan.data)
      }
      return
    }

    if (plan.kind === 'model') {
      if (plan.mode === 'edit') {
        mutations.updateModel(plan.id, plan.data)
      } else {
        mutations.createModel(plan.data)
      }
      return
    }

    if (plan.mode === 'edit') {
      mutations.updateAgent(plan.id, plan.data)
    } else {
      mutations.createAgent(plan.data)
    }
  }

  return {
    providersQuery,
    modelsQuery,
    agentsQuery,
    providers,
    models,
    agents,
    resourceMutationError: mutations.resourceMutationError,
    providerDeletePending: mutations.providerDeletePending,
    modelDeletePending: mutations.modelDeletePending,
    agentDeletePending: mutations.agentDeletePending,
    resourceEditorModal: {
      modal: resourceModal,
      providers,
      models,
      providerDraft,
      modelDraft,
      agentDraft,
      pending: mutations.resourceEditorPending,
      onClose: closeResourceModal,
      onProviderDraftChange: setProviderDraft,
      onModelDraftChange: setModelDraft,
      onAgentDraftChange: setAgentDraft,
      onSubmit: submitResource,
    },
    deleteConfirmModal: {
      modal: deleteConfirm,
      pending: mutations.deleteConfirmPending,
      onClose: closeDeleteConfirm,
    },
    openCreateProvider,
    openEditProvider,
    deleteProvider,
    openCreateModel,
    openEditModel,
    deleteModel,
    openCreateAgent,
    openEditAgent,
    deleteAgent,
  }
}
