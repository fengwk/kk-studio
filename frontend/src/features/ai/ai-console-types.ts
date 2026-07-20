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

/** Variant = thinking/runtime profile (pi-style), not a full capacity definition. */
export interface VariantDraft {
  id: string
  name: string
  thinkingLevel: string
  temperature: string
  maxOutputTokens: string
}

export interface ModelPricingDraft {
  currency: string
  pricingTier: string
  serviceTier: string
  serviceTierMultiplier: string
  version: string
  inputPerMillionTokens: string
  outputPerMillionTokens: string
  cacheReadPerMillionTokens: string
  cacheWritePerMillionTokens: string
  cacheWriteLongPerMillionTokens: string
  reasoningPerMillionTokens: string
}

export interface ModelDraft {
  providerId: string
  name: string
  description: string
  contextWindow: string
  maxOutputTokens: string
  inputModalities: string[]
  capabilities: string[]
  reasoning: boolean
  defaultVariant: string
  variants: VariantDraft[]
  pricing: ModelPricingDraft
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
