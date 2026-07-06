import { useEffect, useMemo, useState, type FormEventHandler } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  buildResourceSubmitPlan,
  createAgentEditorPlan,
  createModelEditorPlan,
  createProviderEditorPlan,
  editAgentEditorPlan,
  editModelEditorPlan,
  editProviderEditorPlan,
  type ResourceEditorPlan,
} from '@/features/ai/ai-console-page-helpers'
import type { AgentDraft, ConfirmModalState, ModelDraft, ProviderDraft, ResourceModal } from '@/features/ai/ai-console-types'
import { normalizeAgentDraftSelection, normalizeModelDraftDefaultVariant, normalizeModelDraftProvider } from '@/features/ai/ai-draft-normalizers'
import { agentService } from '@/shared/api/agent-service'
import type {
  AgentDefinitionCreateDTO,
  AgentDefinitionUpdateDTO,
  AgentModelCreateDTO,
  AgentModelUpdateDTO,
  AgentProviderCreateDTO,
  AgentProviderUpdateDTO,
  AgentResourceId,
} from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'
import { useInvalidateMutation } from '@/features/ai/useInvalidateMutation'

export function useAiConsoleResourceController() {
  const [resourceModal, setResourceModal] = useState<ResourceModal | null>(null)
  const [deleteConfirm, setDeleteConfirm] = useState<ConfirmModalState | null>(null)
  const [providerDraft, setProviderDraft] = useState<ProviderDraft>(() => createProviderEditorPlan().providerDraft)
  const [modelDraft, setModelDraft] = useState<ModelDraft>(() => createModelEditorPlan([], []).modelDraft)
  const [agentDraft, setAgentDraft] = useState<AgentDraft>(() => createAgentEditorPlan([]).agentDraft)

  const providersQuery = useQuery({
    queryKey: queryKeys.providers.list,
    queryFn: () => agentService.listProviders(),
  })

  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })

  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
  })

  const providers = useMemo(() => providersQuery.data?.results ?? [], [providersQuery.data?.results])
  const models = useMemo(() => modelsQuery.data?.results ?? [], [modelsQuery.data?.results])
  const agents = useMemo(() => agentsQuery.data?.results ?? [], [agentsQuery.data?.results])

  const closeResourceModal = () => setResourceModal(null)

  const createProviderMutation = useInvalidateMutation({
    mutationFn: (provider: AgentProviderCreateDTO) => agentService.createProvider(provider),
    invalidateQueryKeys: [queryKeys.providers.list],
    onSuccess: closeResourceModal,
  })

  const updateProviderMutation = useInvalidateMutation({
    mutationFn: ({ id, data }: { id: AgentResourceId; data: AgentProviderUpdateDTO }) => agentService.updateProvider(id, data),
    invalidateQueryKeys: [queryKeys.providers.list, queryKeys.models.list, queryKeys.agents.list],
    onSuccess: closeResourceModal,
  })

  const deleteProviderMutation = useInvalidateMutation({
    mutationFn: (id: AgentResourceId) => agentService.deleteProvider(id),
    invalidateQueryKeys: [queryKeys.providers.list, queryKeys.models.list, queryKeys.agents.list],
    onSuccess: () => setDeleteConfirm(null),
  })

  const createModelMutation = useInvalidateMutation({
    mutationFn: (model: AgentModelCreateDTO) => agentService.createModel(model),
    invalidateQueryKeys: [queryKeys.models.list],
    onSuccess: closeResourceModal,
  })

  const updateModelMutation = useInvalidateMutation({
    mutationFn: ({ id, data }: { id: AgentResourceId; data: AgentModelUpdateDTO }) => agentService.updateModel(id, data),
    invalidateQueryKeys: [queryKeys.models.list, queryKeys.agents.list],
    onSuccess: closeResourceModal,
  })

  const deleteModelMutation = useInvalidateMutation({
    mutationFn: (id: AgentResourceId) => agentService.deleteModel(id),
    invalidateQueryKeys: [queryKeys.models.list, queryKeys.agents.list],
    onSuccess: () => setDeleteConfirm(null),
  })

  const createAgentMutation = useInvalidateMutation({
    mutationFn: (agent: AgentDefinitionCreateDTO) => agentService.createAgent(agent),
    invalidateQueryKeys: [queryKeys.agents.list],
    onSuccess: closeResourceModal,
  })

  const updateAgentMutation = useInvalidateMutation({
    mutationFn: ({ id, data }: { id: AgentResourceId; data: AgentDefinitionUpdateDTO }) => agentService.updateAgent(id, data),
    invalidateQueryKeys: [queryKeys.agents.list, queryKeys.sessions.list],
    onSuccess: closeResourceModal,
  })

  const deleteAgentMutation = useInvalidateMutation({
    mutationFn: (id: AgentResourceId) => agentService.deleteAgent(id),
    invalidateQueryKeys: [queryKeys.agents.list, queryKeys.sessions.list],
    onSuccess: () => setDeleteConfirm(null),
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
      onConfirm: () => deleteProviderMutation.mutate(providerId),
    })
  }

  function deleteModel(providerName: string, modelName: string, modelId: AgentResourceId) {
    setDeleteConfirm({
      title: '删除 Model',
      description: `将删除 Model ${providerName}/${modelName}。`,
      confirmLabel: '确认删除',
      tone: 'danger',
      onConfirm: () => deleteModelMutation.mutate(modelId),
    })
  }

  function deleteAgent(agentName: string, agentId: AgentResourceId) {
    setDeleteConfirm({
      title: '删除 Agent',
      description: `将删除 Agent ${agentName}。已有会话会保留，但不能再用该 Agent 新建运行。`,
      confirmLabel: '确认删除',
      tone: 'danger',
      onConfirm: () => deleteAgentMutation.mutate(agentId),
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
        updateProviderMutation.mutate({ id: plan.id, data: plan.data })
      } else {
        createProviderMutation.mutate(plan.data)
      }
      return
    }

    if (plan.kind === 'model') {
      if (plan.mode === 'edit') {
        updateModelMutation.mutate({ id: plan.id, data: plan.data })
      } else {
        createModelMutation.mutate(plan.data)
      }
      return
    }

    if (plan.mode === 'edit') {
      updateAgentMutation.mutate({ id: plan.id, data: plan.data })
    } else {
      createAgentMutation.mutate(plan.data)
    }
  }

  return {
    providersQuery,
    modelsQuery,
    agentsQuery,
    providers,
    models,
    agents,
    resourceMutationError:
      createProviderMutation.error ||
      updateProviderMutation.error ||
      deleteProviderMutation.error ||
      createModelMutation.error ||
      updateModelMutation.error ||
      deleteModelMutation.error ||
      createAgentMutation.error ||
      updateAgentMutation.error ||
      deleteAgentMutation.error,
    providerDeletePending: deleteProviderMutation.isPending,
    modelDeletePending: deleteModelMutation.isPending,
    agentDeletePending: deleteAgentMutation.isPending,
    resourceEditorModal: {
      modal: resourceModal,
      providers,
      models,
      providerDraft,
      modelDraft,
      agentDraft,
      pending:
        createProviderMutation.isPending ||
        updateProviderMutation.isPending ||
        createModelMutation.isPending ||
        updateModelMutation.isPending ||
        createAgentMutation.isPending ||
        updateAgentMutation.isPending,
      onClose: closeResourceModal,
      onProviderDraftChange: setProviderDraft,
      onModelDraftChange: setModelDraft,
      onAgentDraftChange: setAgentDraft,
      onSubmit: submitResource,
    },
    deleteConfirmModal: {
      modal: deleteConfirm,
      pending: deleteProviderMutation.isPending || deleteModelMutation.isPending || deleteAgentMutation.isPending,
      onClose: () => setDeleteConfirm(null),
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
