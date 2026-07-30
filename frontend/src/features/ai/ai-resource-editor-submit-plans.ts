import type { AgentDraft, ModelDraft, ProviderDraft, ResourceModal } from '@/features/ai/ai-console-types'
import {
  toEditableAgent,
  toEditableAgentUpdate,
  toEditableModel,
  toEditableModelUpdate,
  toEditableProvider,
  toEditableProviderUpdate,
} from '@/features/ai/ai-resource-draft-codecs'
import type { ResourceSubmitPlan } from '@/features/ai/ai-resource-editor-plan-types'

export function buildResourceSubmitPlan(
  modal: ResourceModal,
  drafts: {
    providerDraft: ProviderDraft
    modelDraft: ModelDraft
    agentDraft: AgentDraft
  },
): ResourceSubmitPlan {
  if (modal.kind === 'provider') {
    if (modal.mode === 'edit') {
      return {
        kind: 'provider',
        mode: 'edit',
        id: modal.id,
        data: { ...toEditableProviderUpdate(drafts.providerDraft), expectedVersion: modal.expectedVersion },
      }
    }
    return {
      kind: 'provider',
      mode: 'create',
      data: toEditableProvider(drafts.providerDraft),
    }
  }

  if (modal.kind === 'model') {
    if (modal.mode === 'edit') {
      return {
        kind: 'model',
        mode: 'edit',
        id: modal.id,
        data: { ...toEditableModelUpdate(drafts.modelDraft), expectedVersion: modal.expectedVersion },
      }
    }
    return {
      kind: 'model',
      mode: 'create',
      data: toEditableModel(drafts.modelDraft),
    }
  }

  if (modal.mode === 'edit') {
    return {
      kind: 'agent',
      mode: 'edit',
      id: modal.id,
      data: { ...toEditableAgentUpdate(drafts.agentDraft), expectedVersion: modal.expectedVersion },
    }
  }
  return {
    kind: 'agent',
    mode: 'create',
    data: toEditableAgent(drafts.agentDraft),
  }
}
