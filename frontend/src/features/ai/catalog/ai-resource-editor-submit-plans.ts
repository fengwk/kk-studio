import type {
  AgentDraft,
  ModelDraft,
  ModelGroupDraft,
  ProviderDraft,
  ResourceModal,
} from '@/features/ai/catalog/ai-console-types'
import {
  toEditableAgent,
  toEditableAgentUpdate,
  toEditableModel,
  toEditableModelGroup,
  toEditableModelGroupUpdate,
  toEditableModelUpdate,
  toEditableProvider,
  toEditableProviderUpdate,
} from '@/features/ai/catalog/ai-resource-draft-codecs'
import type { ResourceSubmitPlan } from '@/features/ai/catalog/ai-resource-editor-plan-types'

export function buildResourceSubmitPlan(
  modal: ResourceModal,
  drafts: {
    providerDraft: ProviderDraft
    modelDraft: ModelDraft
    modelGroupDraft: ModelGroupDraft
    agentDraft: AgentDraft
  },
): ResourceSubmitPlan {
  if (modal.kind === 'provider') {
    if (modal.mode === 'edit') {
      return {
        kind: 'provider',
        mode: 'edit',
        name: modal.name,
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
        name: modal.name,
        data: {
          ...(modal.resourceKind === 'GROUP'
            ? toEditableModelGroupUpdate(drafts.modelGroupDraft)
            : toEditableModelUpdate(drafts.modelDraft)),
          expectedVersion: modal.expectedVersion,
        },
      }
    }
    return {
      kind: 'model',
      mode: 'create',
      data: modal.resourceKind === 'GROUP'
        ? toEditableModelGroup(drafts.modelGroupDraft)
        : toEditableModel(drafts.modelDraft),
    }
  }

  if (modal.mode === 'edit') {
    return {
      kind: 'agent',
      mode: 'edit',
      name: modal.name,
      data: {
        // BUILTIN 允许未配置模型；请求体不携带系统持有的 type。
        ...toEditableAgentUpdate(drafts.agentDraft, modal.type === 'BUILTIN'),
        expectedVersion: modal.expectedVersion,
      },
    }
  }
  return {
    kind: 'agent',
    mode: 'create',
    data: toEditableAgent(drafts.agentDraft),
  }
}
