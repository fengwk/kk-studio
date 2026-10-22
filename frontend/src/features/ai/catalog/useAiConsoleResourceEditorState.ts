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
import type {
  AgentDraft,
  ModelDraft,
  ModelGroupDraft,
  ProviderDraft,
  ResourceModal,
} from '@/features/ai/catalog/ai-console-types'
import {
  normalizeAgentDraftDefaultVariant,
  normalizeCreateAgentDraftSelection,
  normalizeCreateModelDraftProvider,
  normalizeEditModelDraftProvider,
  normalizeModelDraftDefaultVariant,
} from '@/features/ai/catalog/ai-draft-normalizers'
import type { AgentModelView } from '@/features/ai/catalog/AgentModelView'
import type {
  AgentDefinitionDTO,
  AgentProviderDTO,
} from '@/shared/api/contracts/ai-catalog'

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
  const [modelGroupDraft, setModelGroupDraft] = useState<ModelGroupDraft>(
    () => createModelEditorPlan([]).modelGroupDraft,
  )
  const [agentDraft, setAgentDraft] = useState<AgentDraft>(() => createAgentEditorPlan([]).agentDraft)

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

  return {
    resourceModal,
    providerDraft,
    modelDraft,
    modelGroupDraft,
    agentDraft,
    onProviderDraftChange: setProviderDraft,
    onModelDraftChange: setModelDraft,
    onModelGroupDraftChange: setModelGroupDraft,
    onAgentDraftChange: setAgentDraft,
    closeResourceModal,
    openCreateProvider: () => applyResourceEditorPlan(createProviderEditorPlan()),
    openEditProvider: (providerName: string) =>
      applyResourceEditorPlan(editProviderEditorPlan(providers, providerName)),
    openCreateModel: () => applyResourceEditorPlan(createModelEditorPlan(providers)),
    openEditModel: (modelName: string) =>
      applyResourceEditorPlan(editModelEditorPlan(models, modelName)),
    /** 仅创建态切换 Tab；两份草稿都保留在 state 中。 */
    selectModelCreateKind: (resourceKind: 'MODEL' | 'GROUP') =>
      setResourceModal((current) =>
        current && current.kind === 'model' && current.mode === 'create'
          ? { ...current, resourceKind }
          : current,
      ),
    openCreateAgent: () => applyResourceEditorPlan(createAgentEditorPlan(models)),
    openEditAgent: (agentName: string) =>
      applyResourceEditorPlan(editAgentEditorPlan(agents, models, agentName)),
  }
}
