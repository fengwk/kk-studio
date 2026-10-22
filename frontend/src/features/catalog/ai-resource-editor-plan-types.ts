import type { AgentDraft, ModelDraft, ModelGroupDraft, ProviderDraft, ResourceModal } from '@/features/catalog/ai-console-types'
import type {
  AgentDefinitionCreateDTO,
  AgentDefinitionUpdateDTO,
  AgentModelCreateDTO,
  AgentModelUpdateDTO,
  AgentProviderCreateDTO,
  AgentProviderUpdateDTO,
} from '@/shared/api/generated/contracts'

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
      modelGroupDraft: ModelGroupDraft
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
      name: string
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
      name: string
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
      name: string
      data: AgentDefinitionUpdateDTO
    }
