import { useEffect, useState } from 'react'
import {
  createAgentEditorPlan,
  createModelEditorPlan,
  createProviderEditorPlan,
  editAgentEditorPlan,
  editModelEditorPlan,
  editProviderEditorPlan,
  type ResourceEditorPlan,
} from '@/features/ai/ai-resource-editor-plans'
import type { AgentDraft, ModelDraft, ProviderDraft, ResourceModal } from '@/features/ai/ai-console-types'
import { normalizeAgentDraftSelection, normalizeModelDraftDefaultVariant, normalizeModelDraftProvider } from '@/features/ai/ai-draft-normalizers'
import type { AgentDefinitionDTO, AgentModelDTO, AgentProviderDTO, AgentResourceId } from '@/shared/api/contracts'

export function useAiConsoleResourceEditorState({
  providers,
  models,
  agents,
}: {
  providers: AgentProviderDTO[]
  models: AgentModelDTO[]
  agents: AgentDefinitionDTO[]
}) {
  const [resourceModal, setResourceModal] = useState<ResourceModal | null>(null)
  const [providerDraft, setProviderDraft] = useState<ProviderDraft>(() => createProviderEditorPlan().providerDraft)
  const [modelDraft, setModelDraft] = useState<ModelDraft>(() => createModelEditorPlan([], []).modelDraft)
  const [agentDraft, setAgentDraft] = useState<AgentDraft>(() => createAgentEditorPlan([]).agentDraft)

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
      return
    }
    setAgentDraft(plan.agentDraft)
  }

  return {
    resourceModal,
    providerDraft,
    modelDraft,
    agentDraft,
    onProviderDraftChange: setProviderDraft,
    onModelDraftChange: setModelDraft,
    onAgentDraftChange: setAgentDraft,
    closeResourceModal,
    openCreateProvider: () => applyResourceEditorPlan(createProviderEditorPlan()),
    openEditProvider: (providerId: AgentResourceId) => applyResourceEditorPlan(editProviderEditorPlan(providers, providerId)),
    openCreateModel: () => applyResourceEditorPlan(createModelEditorPlan(models, providers)),
    openEditModel: (modelId: AgentResourceId) => applyResourceEditorPlan(editModelEditorPlan(models, modelId)),
    openCreateAgent: () => applyResourceEditorPlan(createAgentEditorPlan(models)),
    openEditAgent: (agentId: AgentResourceId) => applyResourceEditorPlan(editAgentEditorPlan(agents, models, agentId)),
  }
}
