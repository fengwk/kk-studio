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
  credential: string
  modelCallTimeoutMillis: string
  modelCallIdleTimeoutMillis: string
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

export interface AgentExecutionPolicyDraft {
  maxTurns: string
  maxDepth: string
  maxDirectSubagents: string
  maxTotalSubagents: string
}

export interface AgentDraft {
  name: string
  description: string
  systemPrompt: string
  modelId: string
  variant: string
  environmentName: string
  tools: string[]
  skills: string[]
  allowedSubagents: string[]
  executionPolicy: AgentExecutionPolicyDraft
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
