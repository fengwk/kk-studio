import { normalizeAgentDraftDefaultVariant, normalizeModelDraftDefaultVariant } from '@/features/catalog/ai-draft-normalizers'
import {
  emptyAgentDraft,
  emptyModelDraft,
  emptyModelGroupDraft,
  emptyProviderDraft,
  toAgentDraft,
  toModelDraft,
  toModelGroupDraft,
  toProviderDraft,
} from '@/features/catalog/ai-resource-draft-codecs'
import type { ResourceEditorPlan } from '@/features/catalog/ai-resource-editor-plan-types'
import type { AgentDTO, ModelDTO, ProviderDTO } from '@/shared/api/generated/contracts'

export function createProviderEditorPlan(): Extract<ResourceEditorPlan, { kind: 'provider' }> {
  return {
    kind: 'provider',
    modal: { kind: 'provider', mode: 'create' },
    providerDraft: emptyProviderDraft(),
  }
}

export function editProviderEditorPlan(
  providers: ProviderDTO[],
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
  providers: ProviderDTO[],
): Extract<ResourceEditorPlan, { kind: 'model' }> {
  return {
    kind: 'model',
    // 创建时两个 Tab 各自持有独立草稿；默认停留单 Model。
    modal: { kind: 'model', mode: 'create', resourceKind: 'MODEL' },
    modelDraft: normalizeModelDraftDefaultVariant(emptyModelDraft(providers[0])),
    modelGroupDraft: emptyModelGroupDraft(),
  }
}

export function editModelEditorPlan(
  models: ModelDTO[],
  modelName: string,
): Extract<ResourceEditorPlan, { kind: 'model' }> | null {
  const model = models.find((item) => item.name === modelName)
  if (!model) {
    return null
  }
  return {
    kind: 'model',
    modal: {
      kind: 'model',
      mode: 'edit',
      resourceKind: model.type,
      name: model.name,
      expectedVersion: model.version,
    },
    modelDraft: normalizeModelDraftDefaultVariant(
      model.type === 'MODEL' ? toModelDraft(model) : emptyModelDraft(null),
    ),
    modelGroupDraft: model.type === 'GROUP' ? toModelGroupDraft(model) : emptyModelGroupDraft(),
  }
}

export function createAgentEditorPlan(
  models: ModelDTO[],
): Extract<ResourceEditorPlan, { kind: 'agent' }> {
  return {
    kind: 'agent',
    modal: { kind: 'agent', mode: 'create' },
    agentDraft: normalizeAgentDraftDefaultVariant(emptyAgentDraft(models[0]), models),
  }
}

export function editAgentEditorPlan(
  agents: AgentDTO[],
  models: ModelDTO[],
  agentName: string,
): Extract<ResourceEditorPlan, { kind: 'agent' }> | null {
  const agent = agents.find((item) => item.name === agentName)
  if (!agent) {
    return null
  }
  return {
    kind: 'agent',
    modal: {
      kind: 'agent',
      mode: 'edit',
      name: agent.name,
      expectedVersion: agent.version,
    },
    agentDraft: normalizeAgentDraftDefaultVariant(toAgentDraft(agent), models),
  }
}
