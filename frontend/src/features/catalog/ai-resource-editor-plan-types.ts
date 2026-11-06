import type { AgentDraft, ModelDraft, ModelGroupDraft, ProviderDraft, ResourceModal } from '@/features/catalog/ai-console-types'
import type {
  AgentDefinitionCreateDTO,
  AgentDefinitionUpdateDTO,
  ModelCreateDTO,
  ModelUpdateDTO,
  ProviderCreateDTO,
  ProviderUpdateDTO,
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
      data: ProviderCreateDTO
    }
  | {
      kind: 'provider'
      mode: 'edit'
      name: string
      data: ProviderUpdateDTO
    }
  | {
      kind: 'model'
      mode: 'create'
      data: ModelCreateDTO
    }
  | {
      kind: 'model'
      mode: 'edit'
      name: string
      data: ModelUpdateDTO
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
