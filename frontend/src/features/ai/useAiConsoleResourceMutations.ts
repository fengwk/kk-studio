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
  workspaceId,
  onResourceSaved,
  onDeleteCompleted,
}: {
  workspaceId: string
  onResourceSaved: () => void
  onDeleteCompleted: () => void
}) {
  const createProviderMutation = useInvalidateMutation({
    mutationFn: (provider: AgentProviderCreateDTO) => agentService.createProvider(workspaceId, provider),
    invalidateQueryKeys: [queryKeys.providers.list(workspaceId)],
    onSuccess: onResourceSaved,
  })
  const updateProviderMutation = useInvalidateMutation({
    mutationFn: ({ id, data }: { id: AgentResourceId; data: AgentProviderUpdateDTO }) => agentService.updateProvider(workspaceId, id, data),
    invalidateQueryKeys: [queryKeys.providers.list(workspaceId), queryKeys.models.list(workspaceId), queryKeys.agents.list(workspaceId)],
    onSuccess: onResourceSaved,
  })
  const deleteProviderMutation = useInvalidateMutation({
    mutationFn: (id: AgentResourceId) => agentService.deleteProvider(workspaceId, id),
    invalidateQueryKeys: [queryKeys.providers.list(workspaceId), queryKeys.models.list(workspaceId), queryKeys.agents.list(workspaceId)],
    onSuccess: onDeleteCompleted,
  })
  const createModelMutation = useInvalidateMutation({
    mutationFn: (model: AgentModelCreateDTO) => agentService.createModel(workspaceId, model),
    invalidateQueryKeys: [queryKeys.models.list(workspaceId)],
    onSuccess: onResourceSaved,
  })
  const updateModelMutation = useInvalidateMutation({
    mutationFn: ({ id, data }: { id: AgentResourceId; data: AgentModelUpdateDTO }) => agentService.updateModel(workspaceId, id, data),
    invalidateQueryKeys: [queryKeys.models.list(workspaceId), queryKeys.agents.list(workspaceId)],
    onSuccess: onResourceSaved,
  })
  const deleteModelMutation = useInvalidateMutation({
    mutationFn: (id: AgentResourceId) => agentService.deleteModel(workspaceId, id),
    invalidateQueryKeys: [queryKeys.models.list(workspaceId), queryKeys.agents.list(workspaceId)],
    onSuccess: onDeleteCompleted,
  })
  const createAgentMutation = useInvalidateMutation({
    mutationFn: (agent: AgentDefinitionCreateDTO) => agentService.createAgent(workspaceId, agent),
    invalidateQueryKeys: [queryKeys.agents.list(workspaceId)],
    onSuccess: onResourceSaved,
  })
  const updateAgentMutation = useInvalidateMutation({
    mutationFn: ({ id, data }: { id: AgentResourceId; data: AgentDefinitionUpdateDTO }) => agentService.updateAgent(workspaceId, id, data),
    invalidateQueryKeys: [queryKeys.agents.list(workspaceId), queryKeys.sessions.list(workspaceId)],
    onSuccess: onResourceSaved,
  })
  const deleteAgentMutation = useInvalidateMutation({
    mutationFn: (id: AgentResourceId) => agentService.deleteAgent(workspaceId, id),
    invalidateQueryKeys: [queryKeys.agents.list(workspaceId), queryKeys.sessions.list(workspaceId)],
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
    deleteProvider: (id: AgentResourceId) => deleteProviderMutation.mutate(id),
    createModel: (data: AgentModelCreateDTO) => createModelMutation.mutate(data),
    updateModel: (id: AgentResourceId, data: AgentModelUpdateDTO) => updateModelMutation.mutate({ id, data }),
    deleteModel: (id: AgentResourceId) => deleteModelMutation.mutate(id),
    createAgent: (data: AgentDefinitionCreateDTO) => createAgentMutation.mutate(data),
    updateAgent: (id: AgentResourceId, data: AgentDefinitionUpdateDTO) => updateAgentMutation.mutate({ id, data }),
    deleteAgent: (id: AgentResourceId) => deleteAgentMutation.mutate(id),
  }
}
