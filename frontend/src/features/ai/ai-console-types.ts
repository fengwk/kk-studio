import type { AgentResourceId } from '@/shared/api/contracts'

export interface ComfyuiWorkflowDraft {
  apiName: string
  name: string
  description: string
  workflowJson: string
  inputBindingsJson: string
  defaultSelector: string
  enabled: boolean
}

export interface ProviderDraft {
  name: string
  description: string
  providerType: string
  baseUrl: string
  apiKey: string
  timeoutMillis: string
}

export interface KeyValueDraft {
  id: string
  key: string
  value: string
}

export interface VariantDraft {
  id: string
  name: string
  temperature: string
  maxOutputTokens: string
  extras: KeyValueDraft[]
}

export interface ModelDraft {
  provider: string
  name: string
  description: string
  defaultVariant: string
  variants: VariantDraft[]
}

export interface AgentDraft {
  name: string
  description: string
  systemPrompt: string
  defaultProvider: string
  defaultModel: string
  defaultVariant: string
  tools: string[]
}

export interface ConfirmModalState {
  title: string
  description: string
  confirmLabel?: string
  tone?: 'danger'
  onConfirm: () => void
}

export type ResourceModal =
  | { kind: 'provider'; mode: 'create' | 'edit'; id?: AgentResourceId }
  | { kind: 'model'; mode: 'create' | 'edit'; id?: AgentResourceId }
  | { kind: 'agent'; mode: 'create' | 'edit'; id?: AgentResourceId }

export const providerTypes = ['openai', 'openai_response', 'anthropic', 'google']
