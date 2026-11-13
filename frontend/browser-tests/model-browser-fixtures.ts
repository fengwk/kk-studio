import type {
  ModelDTO,
  ProviderDTO,
  ModelCatalogPageDTO,
  ModelCatalogCandidateDTO,
} from '@/shared/api/generated/contracts'

/**
 * 离线浏览器验收的固定目录数据。所有价格均为 wire 十进制字符串，
 * 候选价格完整且包含 TTL/推理/覆盖/倍率，用来验证一键填入的全部可写字段。
 */
export const FIXTURE_PROVIDER: ProviderDTO = {
  name: 'acme',
  description: 'Offline fixture provider',
  providerType: 'openai',
  promptCacheRetentions: ['NONE', 'SHORT', 'LONG'],
  baseUrl: 'https://fixture.example/v1',
  credential: { configured: true },
  modelCallTimeoutMillis: '1800000',
  modelCallIdleTimeoutMillis: '120000',
  modelHttpRetryStatusCodes: [429, 503],
  version: '1',
  createdAt: 1792454400,
  updatedAt: 1792454400,
}

export const FIXTURE_MAIN_MODEL: ModelDTO = {
  name: 'main',
  type: 'MODEL',
  description: 'Primary fixture model',
  providerName: 'acme',
  modelId: 'acme-main-v1',
  config: {
    limit: { context: 128000, output: 4096 },
    abilities: { tools: true, reasoning: false, midConversationSystemMessages: false, inputModalities: ['TEXT'] },
    pricing: {
      base: {
        input: '1',
        output: '2',
        cacheRead: '0.1',
        cacheWrite: '1.5',
        cacheWriteByTtl: {},
        reasoning: null,
      },
      overrides: [],
      multipliers: [],
    },
    defaultVariant: 'standard',
    variants: [{ id: 'standard', reasoningEffort: 'HIGH', promptCacheRetention: 'SHORT' }, { id: 'fast', promptCacheRetention: 'SHORT' }],
  },
  version: '1',
  createdAt: 1792454400,
  updatedAt: 1792454400,
}

export const FIXTURE_BACKUP_MODEL: ModelDTO = {
  name: 'backup',
  type: 'MODEL',
  description: 'Backup fixture model',
  providerName: 'acme',
  modelId: 'acme-backup-v1',
  config: {
    limit: { context: 64000, output: 2048 },
    abilities: { tools: true, reasoning: false, midConversationSystemMessages: false, inputModalities: ['TEXT'] },
    pricing: {
      base: {
        input: '0',
        output: '0',
        cacheRead: '0',
        cacheWrite: '0',
        cacheWriteByTtl: {},
        reasoning: null,
      },
      overrides: [],
      multipliers: [],
    },
    defaultVariant: 'default',
    variants: [{ id: 'default', promptCacheRetention: 'SHORT' }],
  },
  version: '1',
  createdAt: 1792454400,
  updatedAt: 1792454400,
}

export const FIXTURE_GROUP: ModelDTO = {
  name: 'coding',
  type: 'GROUP',
  description: 'Ordered coding fallback',
  config: {
    limit: { context: 128000, output: 4096 },
    abilities: { tools: true, reasoning: false, midConversationSystemMessages: false, inputModalities: ['TEXT'] },
    defaultVariant: 'standard',
    variants: [{ id: 'standard', promptCacheRetention: 'SHORT' }],
  },
  group: {
    members: [
      { modelName: 'main', variant: 'standard' },
      { modelName: 'backup', variant: 'default' },
    ],
    fallbackStatusCodes: [429, 503],
    cooldownSeconds: 42,
  },
  version: '3',
  createdAt: 1792454400,
  updatedAt: 1792454400,
}

export const FIXTURE_MODELS: ModelDTO[] = [
  FIXTURE_MAIN_MODEL,
  FIXTURE_BACKUP_MODEL,
  FIXTURE_GROUP,
]

export const FIXTURE_CANDIDATES: ModelCatalogCandidateDTO[] = [
  {
    id: 'models_dev_v2/acme/catalog-a',
    provider: 'acme',
    modelId: 'acme-catalog-a',
    name: 'catalog-a',
    limit: { context: 200000, output: 100000 },
    abilities: { tools: true, reasoning: true, inputModalities: ['TEXT', 'IMAGE'] },
    pricing: {
      base: {
        input: '3',
        output: '15',
        cacheRead: '0.3',
        cacheWrite: '3.75',
        cacheWriteByTtl: { '3600': '7.5' },
        reasoning: '9',
      },
      overrides: [
        {
          id: 'ov-ctx',
          when: {
            context: { gt: 200000 },
            time: { start: '00:00', end: '24:00', timezone: 'UTC' },
          },
          rates: { input: '6', output: '30', cacheWriteByTtl: {} },
        },
      ],
      multipliers: [
        { id: 'mx-tier', when: { serviceTier: ['priority'] }, factors: { input: '1.5', output: '1.2' } },
      ],
    },
    variants: [{ id: 'turbo', promptCacheRetention: 'NONE' }, { id: 'thinking', reasoningEffort: 'HIGH', promptCacheRetention: 'NONE' }],
    defaultVariant: 'turbo',
    supportIssues: [],
    pricingSupported: true,
  },
  {
    id: 'models_dev_v2/acme/catalog-b',
    provider: 'acme',
    modelId: 'acme-catalog-b',
    name: 'catalog-b',
    limit: { context: 32768, output: 8192 },
    abilities: { tools: false, reasoning: false, inputModalities: ['TEXT'] },
    pricing: null,
    variants: [],
    defaultVariant: null,
    supportIssues: ['Pricing is not fully supported'],
    pricingSupported: false,
  },
]

export const FIXTURE_DEFAULTS_PAGE: ModelCatalogPageDTO = {
  generation: 'fixture-generation-1',
  fetchedAt: 1792454400,
  loading: false,
  error: null,
  items: FIXTURE_CANDIDATES,
  nextCursor: null,
}
