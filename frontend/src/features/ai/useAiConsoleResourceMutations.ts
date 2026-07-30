import type {
  AgentDefinitionCreateDTO,
  AgentDefinitionUpdateDTO,
  AgentModelCreateDTO,
  AgentModelUpdateDTO,
  AgentProviderCreateDTO,
  AgentProviderUpdateDTO,
  AgentResourceId,
} from '@/shared/api/contracts'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useInvalidateMutation } from '@/features/ai/useInvalidateMutation'

export function useAiConsoleResourceMutations({
  onResourceSaved,
  onDeleteCompleted,
}: {
  onResourceSaved: () => void
  onDeleteCompleted: () => void
}) {
  const createProviderMutation = useInvalidateMutation({
    mutationFn: (provider: AgentProviderCreateDTO) => agentService.createProvider(provider),
    invalidateQueryKeys: [queryKeys.providers.list],
    onSuccess: onResourceSaved,
  })
  const updateProviderMutation = useInvalidateMutation({
    mutationFn: ({ id, data }: { id: AgentResourceId; data: AgentProviderUpdateDTO }) => agentService.updateProvider(id, data),
    invalidateQueryKeys: [queryKeys.providers.list, queryKeys.models.list, queryKeys.agents.list],
    onSuccess: onResourceSaved,
  })
  const deleteProviderMutation = useInvalidateMutation({
    mutationFn: ({ id, expectedVersion }: { id: AgentResourceId; expectedVersion: string }) =>
      agentService.deleteProvider(id, expectedVersion),
    invalidateQueryKeys: [queryKeys.providers.list, queryKeys.models.list, queryKeys.agents.list],
    onSuccess: onDeleteCompleted,
  })
  const createModelMutation = useInvalidateMutation({
    mutationFn: (model: AgentModelCreateDTO) => agentService.createModel(model),
    invalidateQueryKeys: [queryKeys.models.list],
    onSuccess: onResourceSaved,
  })
  const updateModelMutation = useInvalidateMutation({
    mutationFn: ({ id, data }: { id: AgentResourceId; data: AgentModelUpdateDTO }) => agentService.updateModel(id, data),
    invalidateQueryKeys: [queryKeys.models.list, queryKeys.agents.list],
    onSuccess: onResourceSaved,
  })
  const deleteModelMutation = useInvalidateMutation({
    mutationFn: ({ id, expectedVersion }: { id: AgentResourceId; expectedVersion: string }) =>
      agentService.deleteModel(id, expectedVersion),
    invalidateQueryKeys: [queryKeys.models.list, queryKeys.agents.list],
    onSuccess: onDeleteCompleted,
  })
  const createAgentMutation = useInvalidateMutation({
    mutationFn: (agent: AgentDefinitionCreateDTO) => agentService.createAgent(agent),
    invalidateQueryKeys: [queryKeys.agents.list],
    onSuccess: onResourceSaved,
  })
  const updateAgentMutation = useInvalidateMutation({
    mutationFn: ({ id, data }: { id: AgentResourceId; data: AgentDefinitionUpdateDTO }) => agentService.updateAgent(id, data),
    invalidateQueryKeys: [queryKeys.agents.list, queryKeys.sessions.all],
    onSuccess: onResourceSaved,
  })
  const deleteAgentMutation = useInvalidateMutation({
    mutationFn: ({ id, expectedVersion }: { id: AgentResourceId; expectedVersion: string }) =>
      agentService.deleteAgent(id, expectedVersion),
    invalidateQueryKeys: [queryKeys.agents.list, queryKeys.sessions.all],
    onSuccess: onDeleteCompleted,
  })

  return {
    resourceMutationError:
      createProviderMutation.error || updateProviderMutation.error || deleteProviderMutation.error ||
      createModelMutation.error || updateModelMutation.error || deleteModelMutation.error ||
      createAgentMutation.error || updateAgentMutation.error || deleteAgentMutation.error,
    providerDeletePending: deleteProviderMutation.isPending,
    modelDeletePending: deleteModelMutation.isPending,
    agentDeletePending: deleteAgentMutation.isPending,
    resourceEditorPending:
      createProviderMutation.isPending || updateProviderMutation.isPending ||
      createModelMutation.isPending || updateModelMutation.isPending ||
      createAgentMutation.isPending || updateAgentMutation.isPending,
    deleteConfirmPending: deleteProviderMutation.isPending || deleteModelMutation.isPending || deleteAgentMutation.isPending,
    createProvider: (data: AgentProviderCreateDTO) => createProviderMutation.mutate(data),
    updateProvider: (id: AgentResourceId, data: AgentProviderUpdateDTO) => updateProviderMutation.mutate({ id, data }),
    deleteProvider: (id: AgentResourceId, expectedVersion: string) =>
      deleteProviderMutation.mutate({ id, expectedVersion }),
    createModel: (data: AgentModelCreateDTO) => createModelMutation.mutate(data),
    updateModel: (id: AgentResourceId, data: AgentModelUpdateDTO) => updateModelMutation.mutate({ id, data }),
    deleteModel: (id: AgentResourceId, expectedVersion: string) =>
      deleteModelMutation.mutate({ id, expectedVersion }),
    createAgent: (data: AgentDefinitionCreateDTO) => createAgentMutation.mutate(data),
    updateAgent: (id: AgentResourceId, data: AgentDefinitionUpdateDTO) => updateAgentMutation.mutate({ id, data }),
    deleteAgent: (id: AgentResourceId, expectedVersion: string) =>
      deleteAgentMutation.mutate({ id, expectedVersion }),
  }
}
