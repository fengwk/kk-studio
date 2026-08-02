import { normalizeAgentDraftDefaultVariant, normalizeModelDraftDefaultVariant } from '@/features/ai/catalog/ai-draft-normalizers'
import {
  emptyAgentDraft,
  emptyModelDraft,
  emptyProviderDraft,
  toAgentDraft,
  toModelDraft,
  toProviderDraft,
} from '@/features/ai/catalog/ai-resource-draft-codecs'
import type { ResourceEditorPlan } from '@/features/ai/catalog/ai-resource-editor-plan-types'
import type { AgentModelView } from '@/features/ai/catalog/AgentModelView'
import type {
  AgentDefinitionDTO,
  AgentProviderDTO,
} from '@/shared/api/contracts/ai-catalog'

export function createProviderEditorPlan(): Extract<ResourceEditorPlan, { kind: 'provider' }> {
  return {
    kind: 'provider',
    modal: { kind: 'provider', mode: 'create' },
    providerDraft: emptyProviderDraft(),
  }
}

export function editProviderEditorPlan(
  providers: AgentProviderDTO[],
  providerName: string,
): Extract<ResourceEditorPlan, { kind: 'provider' }> | null {
  const provider = providers.find((item) => item.name === providerName)
  if (!provider) {
    return null
  }
  return {
    kind: 'provider',
    modal: { kind: 'provider', mode: 'edit', name: provider.name, expectedVersion: provider.version },
    providerDraft: toProviderDraft(provider),
  }
}

export function createModelEditorPlan(
  providers: AgentProviderDTO[],
): Extract<ResourceEditorPlan, { kind: 'model' }> {
  return {
    kind: 'model',
    modal: { kind: 'model', mode: 'create' },
    modelDraft: normalizeModelDraftDefaultVariant(emptyModelDraft(providers[0])),
  }
}

export function editModelEditorPlan(
  models: AgentModelView[],
  providerName: string,
  modelName: string,
): Extract<ResourceEditorPlan, { kind: 'model' }> | null {
  const model = models.find(
    (item) => item.providerName === providerName && item.name === modelName,
  )
  if (!model) {
    return null
  }
  return {
    kind: 'model',
    modal: {
      kind: 'model',
      mode: 'edit',
      providerName: model.providerName,
      name: model.name,
      expectedVersion: model.version,
    },
    modelDraft: normalizeModelDraftDefaultVariant(toModelDraft(model)),
  }
}

export function createAgentEditorPlan(
  models: AgentModelView[],
): Extract<ResourceEditorPlan, { kind: 'agent' }> {
  return {
    kind: 'agent',
    modal: { kind: 'agent', mode: 'create' },
    agentDraft: normalizeAgentDraftDefaultVariant(emptyAgentDraft(models[0]), models),
  }
}

export function editAgentEditorPlan(
  agents: AgentDefinitionDTO[],
  models: AgentModelView[],
  agentName: string,
): Extract<ResourceEditorPlan, { kind: 'agent' }> | null {
  const agent = agents.find((item) => item.name === agentName)
  if (!agent) {
    return null
  }
  return {
    kind: 'agent',
    modal: { kind: 'agent', mode: 'edit', name: agent.name, expectedVersion: agent.version },
    agentDraft: normalizeAgentDraftDefaultVariant(toAgentDraft(agent), models),
  }
}