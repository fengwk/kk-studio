import { useEffect, useState } from 'react'
import {
  createAgentEditorPlan,
  createModelEditorPlan,
  createProviderEditorPlan,
  editAgentEditorPlan,
  editModelEditorPlan,
  editProviderEditorPlan,
} from '@/features/ai/catalog/ai-resource-editor-open-plans'
import type { ResourceEditorPlan } from '@/features/ai/catalog/ai-resource-editor-plan-types'
import type { AgentDraft, ModelDraft, ProviderDraft, ResourceModal } from '@/features/ai/catalog/ai-console-types'
import { normalizeAgentDraftSelection, normalizeModelDraftDefaultVariant, normalizeModelDraftProvider } from '@/features/ai/catalog/ai-draft-normalizers'
import type { AgentModelView } from '@/features/ai/catalog/AgentModelView'
import type {
  AgentDefinitionDTO,
  AgentProviderDTO,
  AgentResourceId,
} from '@/shared/api/contracts'

export function useAiConsoleResourceEditorState({
  providers,
  models,
  agents,
}: {
  providers: AgentProviderDTO[]
  models: AgentModelView[]
  agents: AgentDefinitionDTO[]
}) {
  const [resourceModal, setResourceModal] = useState<ResourceModal | null>(null)
  const [providerDraft, setProviderDraft] = useState<ProviderDraft>(() => createProviderEditorPlan().providerDraft)
  const [modelDraft, setModelDraft] = useState<ModelDraft>(() => createModelEditorPlan([]).modelDraft)
  const [agentDraft, setAgentDraft] = useState<AgentDraft>(() => createAgentEditorPlan([]).agentDraft)

  useEffect(() => {
    if (!resourceModal) {
      return
    }

    if (resourceModal.kind === 'model') {
      // Edit mode keeps the persisted providerId so the user never silently rewires a Model
      // between providers; create mode has no preference and falls back to providers[0].
      const preferredProviderId =
        resourceModal.mode === 'edit'
          ? models.find((model) => model.id === resourceModal.id)?.providerId
          : undefined
      setModelDraft((currentDraft) =>
        normalizeModelDraftDefaultVariant(
          normalizeModelDraftProvider(currentDraft, providers, preferredProviderId),
        ),
      )
      return
    }

    if (resourceModal.kind === 'agent') {
      const preferredModelId =
        resourceModal.mode === 'edit'
          ? agents.find((agent) => agent.id === resourceModal.id)?.modelId
          : undefined
      setAgentDraft((currentDraft) => normalizeAgentDraftSelection(currentDraft, models, preferredModelId))
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
    openCreateModel: () => applyResourceEditorPlan(createModelEditorPlan(providers)),
    openEditModel: (modelId: AgentResourceId) => applyResourceEditorPlan(editModelEditorPlan(models, modelId)),
    openCreateAgent: () => applyResourceEditorPlan(createAgentEditorPlan(models)),
    openEditAgent: (agentId: AgentResourceId) => applyResourceEditorPlan(editAgentEditorPlan(agents, models, agentId)),
  }
}
