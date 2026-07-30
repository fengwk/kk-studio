import type { AgentDraft, ModelDraft, ProviderDraft, ResourceModal } from '@/features/ai/catalog/ai-console-types'
import type {
  AgentDefinitionCreateDTO,
  AgentDefinitionUpdateDTO,
  AgentModelCreateDTO,
  AgentModelUpdateDTO,
  AgentProviderCreateDTO,
  AgentProviderUpdateDTO,
  AgentResourceId,
} from '@/shared/api/contracts'

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
