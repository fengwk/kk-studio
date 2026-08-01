import type { AgentModelInputModality } from '@/shared/api/contracts/ai-catalog'
import type { AgentResourceId, CatalogVersion } from '@/shared/api/contracts/base'

export interface ProviderDraft {
  name: string
  description: string
  providerType: string
  baseUrl: string
  credential: string
  modelCallTimeoutMillis: string
  modelCallIdleTimeoutMillis: string
}

/**
 * One Agent model variant. Form inputs are string-typed so empty fields map cleanly to
 * {@code null}/{@code undefined} on the wire {@link AgentModelVariantDTO}.
 */
export interface VariantDraft {
  /** Client-only React key. */
  draftId: string
  id: string
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
  /**
   * Non-empty on submit. {@code TEXT} is always present for newly created models; toggling
   * adds/removes items via immutable arrays (no in-place mutation).
   */
  inputModalities: AgentModelInputModality[]
  defaultVariant: string
  variants: VariantDraft[]
  pricing: ModelPricingDraft
}

export interface AgentDraft {
  name: string
  description: string
  systemPrompt: string
  modelId: string
  variant: string
  tools: string[]
  skills: string[]
}

export type ResourceModal =
  | { kind: 'provider'; mode: 'create' }
  | { kind: 'provider'; mode: 'edit'; id: AgentResourceId; expectedVersion: CatalogVersion }
  | { kind: 'model'; mode: 'create' }
  | { kind: 'model'; mode: 'edit'; id: AgentResourceId; expectedVersion: CatalogVersion }
  | { kind: 'agent'; mode: 'create' }
  | { kind: 'agent'; mode: 'edit'; id: AgentResourceId; expectedVersion: CatalogVersion }

export const providerTypes = ['openai', 'openai_response', 'anthropic', 'google']
