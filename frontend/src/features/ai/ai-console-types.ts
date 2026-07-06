import type { AgentResourceId } from '@/shared/api/contracts'

export type AiConsoleTab = 'chat' | 'agent' | 'model' | 'provider'

export interface ProviderDraft {
  name: string
  description: string
  providerType: string
  baseUrl: string
  apiKey: string
  timeoutMillis: string
  streamIdleTimeoutMillis: string
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
  capabilities: KeyValueDraft[]
  limits: KeyValueDraft[]
  pricing: KeyValueDraft[]
}

export interface AgentDraft {
  name: string
  description: string
  systemPrompt: string
  defaultProvider: string
  defaultModel: string
  defaultVariant: string
  tools: string[]
  subagents: string[]
  skills: string[]
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

export const tabRoutes: Record<AiConsoleTab, string> = {
  chat: '/agent/sessions',
  agent: '/agent/agents',
  model: '/agent/models',
  provider: '/agent/providers',
}

export const tabLabels: Record<AiConsoleTab, string> = {
  chat: 'Chat',
  agent: 'Agent',
  model: 'Model',
  provider: 'Provider',
}

export const providerTypes = ['openai', 'openai_response', 'anthropic', 'google']
