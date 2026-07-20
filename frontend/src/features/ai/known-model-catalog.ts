/**
 * Known model defaults inspired by pi's provider model tables.
 * When users pick a known model name, the form can load these defaults.
 * Reset restores this catalog profile (not an empty form).
 */

export const THINKING_LEVELS = ['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'] as const
export type ThinkingLevel = (typeof THINKING_LEVELS)[number]

export interface KnownModelVariantDefault {
  name: string
  thinkingLevel: ThinkingLevel
  temperature?: number
  maxOutputTokens?: number
}

export interface KnownModelDefault {
  /** Match against model name (case-insensitive). */
  id: string
  displayName: string
  contextWindow: number
  maxOutputTokens: number
  reasoning: boolean
  inputModalities: Array<'TEXT' | 'IMAGE' | 'AUDIO' | 'VIDEO' | 'DOCUMENT'>
  capabilities: Array<'TEXT' | 'VISION' | 'AUDIO' | 'TOOLS' | 'THINKING'>
  pricing: {
    currency: string
    pricingTier: string
    serviceTier: string
    serviceTierMultiplier: number
    version: string
    inputPerMillionTokens: number
    outputPerMillionTokens: number
    cacheReadPerMillionTokens: number
    cacheWritePerMillionTokens: number
    cacheWriteLongPerMillionTokens: number
    reasoningPerMillionTokens: number
  }
  /** Thinking profiles exposed as variants. */
  variants: KnownModelVariantDefault[]
  defaultVariant: string
}

function reasoningProfiles(maxOutputTokens: number): KnownModelVariantDefault[] {
  return [
    { name: 'off', thinkingLevel: 'off', maxOutputTokens },
    { name: 'low', thinkingLevel: 'low', maxOutputTokens },
    { name: 'medium', thinkingLevel: 'medium', maxOutputTokens },
    { name: 'high', thinkingLevel: 'high', maxOutputTokens },
  ]
}

function nonReasoningDefault(maxOutputTokens: number): KnownModelVariantDefault[] {
  return [{ name: 'default', thinkingLevel: 'off', maxOutputTokens }]
}

function freePricing(partial?: Partial<KnownModelDefault['pricing']>): KnownModelDefault['pricing'] {
  return {
    currency: 'USD',
    pricingTier: 'default',
    serviceTier: 'default',
    serviceTierMultiplier: 1,
    version: 'v1',
    inputPerMillionTokens: 0,
    outputPerMillionTokens: 0,
    cacheReadPerMillionTokens: 0,
    cacheWritePerMillionTokens: 0,
    cacheWriteLongPerMillionTokens: 0,
    reasoningPerMillionTokens: 0,
    ...partial,
  }
}

/** Catalog entries adapted from pi packages/ai/src/providers/*.models.ts */
export const KNOWN_MODEL_DEFAULTS: KnownModelDefault[] = [
  {
    id: 'MiniMax-M2.7',
    displayName: 'MiniMax-M2.7',
    contextWindow: 204800,
    maxOutputTokens: 131072,
    reasoning: true,
    inputModalities: ['TEXT'],
    capabilities: ['TEXT', 'TOOLS'],
    pricing: freePricing({
      inputPerMillionTokens: 0.3,
      outputPerMillionTokens: 1.2,
      cacheReadPerMillionTokens: 0.06,
      cacheWritePerMillionTokens: 0.375,
    }),
    variants: reasoningProfiles(131072),
    defaultVariant: 'medium',
  },
  {
    id: 'MiniMax-M2.7-highspeed',
    displayName: 'MiniMax-M2.7-highspeed',
    contextWindow: 204800,
    maxOutputTokens: 131072,
    reasoning: true,
    inputModalities: ['TEXT'],
    capabilities: ['TEXT', 'TOOLS'],
    pricing: freePricing({
      inputPerMillionTokens: 0.6,
      outputPerMillionTokens: 2.4,
      cacheReadPerMillionTokens: 0.06,
      cacheWritePerMillionTokens: 0.375,
    }),
    variants: reasoningProfiles(131072),
    defaultVariant: 'medium',
  },
  {
    id: 'MiniMax-M3',
    displayName: 'MiniMax-M3',
    contextWindow: 1_000_000,
    maxOutputTokens: 128000,
    reasoning: true,
    inputModalities: ['TEXT', 'IMAGE'],
    capabilities: ['TEXT', 'TOOLS', 'VISION'],
    pricing: freePricing({
      inputPerMillionTokens: 0.3,
      outputPerMillionTokens: 1.2,
      cacheReadPerMillionTokens: 0.06,
    }),
    variants: reasoningProfiles(128000),
    defaultVariant: 'medium',
  },
  {
    id: 'gpt-4o',
    displayName: 'gpt-4o',
    contextWindow: 128000,
    maxOutputTokens: 16384,
    reasoning: false,
    inputModalities: ['TEXT', 'IMAGE'],
    capabilities: ['TEXT', 'TOOLS', 'VISION'],
    pricing: freePricing({
      inputPerMillionTokens: 2.5,
      outputPerMillionTokens: 10,
      cacheReadPerMillionTokens: 1.25,
    }),
    variants: nonReasoningDefault(16384),
    defaultVariant: 'default',
  },
  {
    id: 'claude-sonnet-4',
    displayName: 'claude-sonnet-4',
    contextWindow: 200000,
    maxOutputTokens: 64000,
    reasoning: true,
    inputModalities: ['TEXT', 'IMAGE'],
    capabilities: ['TEXT', 'TOOLS', 'VISION'],
    pricing: freePricing({
      inputPerMillionTokens: 3,
      outputPerMillionTokens: 15,
      cacheReadPerMillionTokens: 0.3,
      cacheWritePerMillionTokens: 3.75,
    }),
    variants: reasoningProfiles(64000),
    defaultVariant: 'medium',
  },
]

export function findKnownModelDefault(modelName: string | null | undefined): KnownModelDefault | null {
  const key = modelName?.trim().toLowerCase()
  if (!key) {
    return null
  }
  return KNOWN_MODEL_DEFAULTS.find((item) => item.id.toLowerCase() === key || item.displayName.toLowerCase() === key) ?? null
}

export function knownModelNames(): string[] {
  return KNOWN_MODEL_DEFAULTS.map((item) => item.id)
}

/** Generic fallback when model is unknown: one default profile. */
export function genericModelDefaults(modelName = 'custom-model'): KnownModelDefault {
  return {
    id: modelName,
    displayName: modelName,
    contextWindow: 128000,
    maxOutputTokens: 8192,
    reasoning: false,
    inputModalities: ['TEXT'],
    capabilities: ['TEXT', 'TOOLS'],
    pricing: freePricing(),
    variants: nonReasoningDefault(8192),
    defaultVariant: 'default',
  }
}
