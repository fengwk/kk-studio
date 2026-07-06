import type { AiConsoleTab, AgentDraft, ModelDraft, ProviderDraft, ResourceModal } from '@/features/ai/ai-console-types'
import { normalizeAgentDraftDefaultVariant, normalizeModelDraftDefaultVariant } from '@/features/ai/ai-draft-normalizers'
import {
  emptyAgentDraft,
  emptyModelDraft,
  emptyProviderDraft,
  toAgentDraft,
  toEditableAgent,
  toEditableAgentUpdate,
  toEditableModel,
  toEditableModelUpdate,
  toEditableProvider,
  toEditableProviderUpdate,
  toModelDraft,
  toProviderDraft,
} from '@/features/ai/ai-console-utils'
import type {
  AgentDefinitionCreateDTO,
  AgentDefinitionDTO,
  AgentDefinitionUpdateDTO,
  AgentModelCreateDTO,
  AgentModelDTO,
  AgentModelUpdateDTO,
  AgentProviderCreateDTO,
  AgentProviderDTO,
  AgentProviderUpdateDTO,
  AgentResourceId,
} from '@/shared/api/contracts'

export const aiConsoleTabs: AiConsoleTab[] = ['chat', 'agent', 'model', 'provider']

export type ResourceEditorPlan =
  | {
      kind: 'provider'
      modal: Extract<ResourceModal, { kind: 'provider' }>
      providerDraft: ProviderDraft
    }
  | {
      kind: 'model'
      modal: Extract<ResourceModal, { kind: 'model' }>
      modelDraft: ModelDraft
    }
  | {
      kind: 'agent'
      modal: Extract<ResourceModal, { kind: 'agent' }>
      agentDraft: AgentDraft
    }

export type ResourceSubmitPlan =
  | {
      kind: 'provider'
      mode: 'create'
      data: AgentProviderCreateDTO
    }
  | {
      kind: 'provider'
      mode: 'edit'
      id: AgentResourceId
      data: AgentProviderUpdateDTO
    }
  | {
      kind: 'model'
      mode: 'create'
      data: AgentModelCreateDTO
    }
  | {
      kind: 'model'
      mode: 'edit'
      id: AgentResourceId
      data: AgentModelUpdateDTO
    }
  | {
      kind: 'agent'
      mode: 'create'
      data: AgentDefinitionCreateDTO
    }
  | {
      kind: 'agent'
      mode: 'edit'
      id: AgentResourceId
      data: AgentDefinitionUpdateDTO
    }

export function resolveSessionAgentName(agentName: string | undefined, selectedAgentName: string, agents: AgentDefinitionDTO[]): string {
  return agentName || selectedAgentName || agents[0]?.name || ''
}

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
    modal: { kind: 'provider', mode: 'edit', id: provider.id },
    providerDraft: toProviderDraft(provider),
  }
}

export function createModelEditorPlan(models: AgentModelDTO[], providers: AgentProviderDTO[]): Extract<ResourceEditorPlan, { kind: 'model' }> {
  return {
    kind: 'model',
    modal: { kind: 'model', mode: 'create' },
    modelDraft: normalizeModelDraftDefaultVariant(emptyModelDraft(models[0], providers[0])),
  }
}

export function editModelEditorPlan(models: AgentModelDTO[], modelId: AgentResourceId): Extract<ResourceEditorPlan, { kind: 'model' }> | null {
  const model = models.find((item) => item.id === modelId)
  if (!model) {
    return null
  }
  return {
    kind: 'model',
    modal: { kind: 'model', mode: 'edit', id: model.id },
    modelDraft: normalizeModelDraftDefaultVariant(toModelDraft(model)),
  }
}

export function createAgentEditorPlan(models: AgentModelDTO[]): Extract<ResourceEditorPlan, { kind: 'agent' }> {
  return {
    kind: 'agent',
    modal: { kind: 'agent', mode: 'create' },
    agentDraft: normalizeAgentDraftDefaultVariant(emptyAgentDraft(models[0]), models),
  }
}

export function editAgentEditorPlan(
  agents: AgentDefinitionDTO[],
  models: AgentModelDTO[],
  agentId: AgentResourceId,
): Extract<ResourceEditorPlan, { kind: 'agent' }> | null {
  const agent = agents.find((item) => item.id === agentId)
  if (!agent) {
    return null
  }
  return {
    kind: 'agent',
    modal: { kind: 'agent', mode: 'edit', id: agent.id },
    agentDraft: normalizeAgentDraftDefaultVariant(toAgentDraft(agent), models),
  }
}

export function buildResourceSubmitPlan(
  modal: ResourceModal,
  drafts: {
    providerDraft: ProviderDraft
    modelDraft: ModelDraft
    agentDraft: AgentDraft
  },
): ResourceSubmitPlan {
  if (modal.kind === 'provider') {
    if (modal.mode === 'edit' && modal.id !== undefined) {
      return {
        kind: 'provider',
        mode: 'edit',
        id: modal.id,
        data: toEditableProviderUpdate(drafts.providerDraft),
      }
    }
    return {
      kind: 'provider',
      mode: 'create',
      data: toEditableProvider(drafts.providerDraft),
    }
  }

  if (modal.kind === 'model') {
    if (modal.mode === 'edit' && modal.id !== undefined) {
      return {
        kind: 'model',
        mode: 'edit',
        id: modal.id,
        data: toEditableModelUpdate(drafts.modelDraft),
      }
    }
    return {
      kind: 'model',
      mode: 'create',
      data: toEditableModel(drafts.modelDraft),
    }
  }

  if (modal.mode === 'edit' && modal.id !== undefined) {
    return {
      kind: 'agent',
      mode: 'edit',
      id: modal.id,
      data: toEditableAgentUpdate(drafts.agentDraft),
    }
  }
  return {
    kind: 'agent',
    mode: 'create',
    data: toEditableAgent(drafts.agentDraft),
  }
}
