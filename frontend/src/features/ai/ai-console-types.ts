import type { AgentResourceId } from '@/shared/api/contracts'
import type { AgentModelInputModality } from '@/shared/api/contracts'

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

/**
 * One Agent model variant. Form inputs are string-typed so empty fields map cleanly to
 * {@code null}/{@code undefined} on the wire {@link AgentModelVariantDTO}.
 */
export interface VariantDraft {
  /** Client-only React key. */
  id: string
  /** Persisted as {@code variants[].id}. */
  name: string
  reasoningEffort: string
  maxOutputTokens: string
  temperature: string
  topP: string
  topK: string
  frequencyPenalty: string
  presencePenalty: string
  /** Comma-separated in the form; persisted as a string array. */
  stopSequences: string
}

/**
 * Per-million-token prices carried through the form. The shared {@code config.pricing} subshape
 * owns currency / tier metadata at the wire boundary; the form only edits the per-unit prices.
 */
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

/** Form draft for a model resource. Inputs stay string-typed for predictable editing UX. */
export interface ModelDraft {
  providerId: string
  name: string
  description: string
  contextWindow: string
  maxOutputTokens: string
  tools: boolean
  reasoning: boolean
  /** Required, non-empty on submit. The form treats it as a {@link Set} for toggling. */
  inputModalities: Set<AgentModelInputModality>
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
