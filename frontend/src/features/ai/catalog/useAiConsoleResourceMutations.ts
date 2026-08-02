import type {
  AgentDefinitionCreateDTO,
  AgentDefinitionUpdateDTO,
  AgentModelCreateDTO,
  AgentModelUpdateDTO,
  AgentProviderCreateDTO,
  AgentProviderUpdateDTO,
} from '@/shared/api/contracts/ai-catalog'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useInvalidateMutation } from '@/shared/lib/useInvalidateMutation'

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
    mutationFn: ({ name, data }: { name: string; data: AgentProviderUpdateDTO }) =>
      agentService.updateProvider(name, data),
    invalidateQueryKeys: [queryKeys.providers.list, queryKeys.models.list, queryKeys.agents.list],
    onSuccess: onResourceSaved,
  })
  const deleteProviderMutation = useInvalidateMutation({
    mutationFn: ({ name, expectedVersion }: { name: string; expectedVersion: string }) =>
      agentService.deleteProvider(name, expectedVersion),
    invalidateQueryKeys: [queryKeys.providers.list, queryKeys.models.list, queryKeys.agents.list],
    onSuccess: onDeleteCompleted,
  })
  const createModelMutation = useInvalidateMutation({
    mutationFn: (model: AgentModelCreateDTO) => agentService.createModel(model),
    invalidateQueryKeys: [queryKeys.models.list],
    onSuccess: onResourceSaved,
  })
  const updateModelMutation = useInvalidateMutation({
    mutationFn: ({
      providerName,
      name,
      data,
    }: {
      providerName: string
      name: string
      data: AgentModelUpdateDTO
    }) => agentService.updateModel(providerName, name, data),
    invalidateQueryKeys: [queryKeys.models.list, queryKeys.agents.list],
    onSuccess: onResourceSaved,
  })
  const deleteModelMutation = useInvalidateMutation({
    mutationFn: ({
      providerName,
      name,
      expectedVersion,
    }: {
      providerName: string
      name: string
      expectedVersion: string
    }) => agentService.deleteModel(providerName, name, expectedVersion),
    invalidateQueryKeys: [queryKeys.models.list, queryKeys.agents.list],
    onSuccess: onDeleteCompleted,
  })
  const createAgentMutation = useInvalidateMutation({
    mutationFn: (agent: AgentDefinitionCreateDTO) => agentService.createAgent(agent),
    invalidateQueryKeys: [queryKeys.agents.list],
    onSuccess: onResourceSaved,
  })
  const updateAgentMutation = useInvalidateMutation({
    mutationFn: ({ name, data }: { name: string; data: AgentDefinitionUpdateDTO }) =>
      agentService.updateAgent(name, data),
    invalidateQueryKeys: [queryKeys.agents.list, queryKeys.sessions.all],
    onSuccess: onResourceSaved,
  })
  const deleteAgentMutation = useInvalidateMutation({
    mutationFn: ({ name, expectedVersion }: { name: string; expectedVersion: string }) =>
      agentService.deleteAgent(name, expectedVersion),
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
    updateProvider: (name: string, data: AgentProviderUpdateDTO) =>
      updateProviderMutation.mutate({ name, data }),
    deleteProvider: (name: string, expectedVersion: string) =>
      deleteProviderMutation.mutate({ name, expectedVersion }),
    createModel: (data: AgentModelCreateDTO) => createModelMutation.mutate(data),
    updateModel: (providerName: string, name: string, data: AgentModelUpdateDTO) =>
      updateModelMutation.mutate({ providerName, name, data }),
    deleteModel: (providerName: string, name: string, expectedVersion: string) =>
      deleteModelMutation.mutate({ providerName, name, expectedVersion }),
    createAgent: (data: AgentDefinitionCreateDTO) => createAgentMutation.mutate(data),
    updateAgent: (name: string, data: AgentDefinitionUpdateDTO) =>
      updateAgentMutation.mutate({ name, data }),
    deleteAgent: (name: string, expectedVersion: string) =>
      deleteAgentMutation.mutate({ name, expectedVersion }),
  }
}
