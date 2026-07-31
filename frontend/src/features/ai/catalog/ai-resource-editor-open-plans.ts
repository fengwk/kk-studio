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
import type { AgentResourceId } from '@/shared/api/contracts/base'

export function createProviderEditorPlan(): Extract<ResourceEditorPlan, { kind: 'provider' }> {
  return {
    kind: 'provider',
    modal: { kind: 'provider', mode: 'create' },
    providerDraft: emptyProviderDraft(),
  }
}

export function editProviderEditorPlan(
  providers: AgentProviderDTO[],
  providerId: AgentResourceId,
): Extract<ResourceEditorPlan, { kind: 'provider' }> | null {
  const provider = providers.find((item) => item.id === providerId)
  if (!provider) {
    return null
  }
  return {
    kind: 'provider',
    modal: { kind: 'provider', mode: 'edit', id: provider.id, expectedVersion: provider.version },
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
  modelId: AgentResourceId,
): Extract<ResourceEditorPlan, { kind: 'model' }> | null {
  const model = models.find((item) => item.id === modelId)
  if (!model) {
    return null
  }
  return {
    kind: 'model',
    modal: { kind: 'model', mode: 'edit', id: model.id, expectedVersion: model.version },
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
  agentId: AgentResourceId,
): Extract<ResourceEditorPlan, { kind: 'agent' }> | null {
  const agent = agents.find((item) => item.id === agentId)
  if (!agent) {
    return null
  }
  return {
    kind: 'agent',
    modal: { kind: 'agent', mode: 'edit', id: agent.id, expectedVersion: agent.version },
    agentDraft: normalizeAgentDraftDefaultVariant(toAgentDraft(agent), models),
  }
}