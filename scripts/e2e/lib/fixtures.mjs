/** 可复用的合法请求体与配置矩阵行定义。 */

export function basePricing(overrides = {}) {
  return {
    currency: 'USD',
    pricingTier: 'default',
    serviceTier: 'default',
    serviceTierMultiplier: 1,
    version: 'v1',
    inputPerMillionTokens: 1,
    outputPerMillionTokens: 2,
    cacheReadPerMillionTokens: 0,
    cacheWritePerMillionTokens: 0,
    cacheWriteLongPerMillionTokens: 0,
    reasoningPerMillionTokens: 0,
    ...overrides,
  }
}

export function baseModelConfig(overrides = {}) {
  return {
    limit: { context: 4096, output: 512 },
    abilities: {
      tools: true,
      reasoning: false,
      inputModalities: ['TEXT'],
    },
    pricing: basePricing(),
    defaultVariant: 'default',
    variants: [{ id: 'default', temperature: 0.2 }],
    ...overrides,
  }
}

export function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

/**
 * Model config 校验矩阵。
 * ok=true 期望 2xx；ok=false 期望 4xx 且 message 匹配。
 */
export function modelConfigMatrix() {
  const good = () => baseModelConfig()
  return [
    {
      id: 'valid.minimal',
      ok: true,
      title: '最小合法 config',
      build: () => good(),
    },
    {
      id: 'valid.reasoning_variants',
      ok: true,
      title: 'reasoning + 多 variant',
      build: () =>
        baseModelConfig({
          abilities: { tools: true, reasoning: true, inputModalities: ['TEXT', 'IMAGE'] },
          defaultVariant: 'medium',
          variants: [
            { id: 'off' },
            { id: 'low', reasoningEffort: 'low' },
            { id: 'medium', reasoningEffort: 'medium', temperature: 0.1 },
            { id: 'high', reasoningEffort: 'high', maxOutputTokens: 256 },
          ],
        }),
    },
    {
      id: 'valid.sampling_fields',
      ok: true,
      title: '采样字段齐全',
      build: () =>
        baseModelConfig({
          variants: [
            {
              id: 'default',
              temperature: 0.7,
              topP: 0.9,
              topK: 40,
              frequencyPenalty: 0.1,
              presencePenalty: 0.2,
              maxOutputTokens: 128,
            },
          ],
        }),
    },
    {
      id: 'invalid.defaultVariant_mismatch',
      ok: false,
      expectStatus: 400,
      messageIncludes: /defaultVariant must match/i,
      title: 'defaultVariant 不在 variants',
      build: () => baseModelConfig({ defaultVariant: 'missing' }),
    },
    {
      id: 'invalid.empty_variants',
      ok: false,
      expectStatus: 400,
      messageIncludes: /variant/i,
      title: 'variants 为空',
      build: () => baseModelConfig({ variants: [] }),
    },
    {
      id: 'invalid.context_non_positive',
      ok: false,
      expectStatus: 400,
      messageIncludes: /context|limit/i,
      title: 'context <= 0',
      build: () => baseModelConfig({ limit: { context: 0, output: 1 } }),
    },
    {
      id: 'invalid.output_gt_context',
      ok: false,
      expectStatus: 400,
      messageIncludes: /output|context|limit/i,
      title: 'output > context',
      build: () => baseModelConfig({ limit: { context: 100, output: 200 } }),
    },
    {
      id: 'invalid.blank_currency',
      ok: false,
      expectStatus: 400,
      messageIncludes: /currency|pricing/i,
      title: 'pricing.currency 空白',
      build: () => baseModelConfig({ pricing: basePricing({ currency: ' ' }) }),
    },
    {
      id: 'invalid.empty_modalities',
      ok: false,
      expectStatus: 400,
      messageIncludes: /modalit|abilities/i,
      title: 'inputModalities 为空',
      build: () =>
        baseModelConfig({
          abilities: { tools: true, reasoning: false, inputModalities: [] },
        }),
    },
    {
      id: 'invalid.duplicate_variant_id',
      ok: false,
      expectStatus: 400,
      messageIncludes: /duplicate|variant/i,
      title: 'variant id 重复',
      build: () =>
        baseModelConfig({
          variants: [
            { id: 'default' },
            { id: 'default', temperature: 0.5 },
          ],
        }),
    },
    {
      id: 'invalid.missing_config',
      ok: false,
      expectStatus: 400,
      messageIncludes: /config/i,
      title: '缺少 config',
      build: () => null,
      rawBody: true,
    },
    {
      id: 'invalid.variant_blank_id',
      ok: false,
      expectStatus: 400,
      messageIncludes: /variant|id|blank/i,
      title: 'variant id 空白',
      build: () =>
        baseModelConfig({
          variants: [{ id: ' ' }],
        }),
    },
    {
      id: 'invalid.negative_temperature',
      ok: false,
      expectStatus: 400,
      messageIncludes: /temperature|variant|range|invalid/i,
      title: 'temperature 越界',
      build: () =>
        baseModelConfig({
          variants: [{ id: 'default', temperature: -1 }],
        }),
    },
  ]
}

/**
 * Agent definition config 校验矩阵。
 */
export function agentConfigMatrix() {
  return [
    {
      id: 'valid.empty_lists_and_policy',
      ok: true,
      title: '空 tools/skills/subagents + 空 policy',
      build: () => ({
        tools: [],
        skills: [],
        allowedSubagents: [],
        executionPolicy: {},
      }),
    },
    {
      id: 'valid.positive_limits',
      ok: true,
      title: 'executionPolicy 正整数上限',
      build: () => ({
        // tools 必须是当前 live registry/platform 可解析名称；此处不依赖具体 tool
        tools: [],
        skills: [],
        allowedSubagents: [],
        executionPolicy: {
          maxTurns: 8,
          maxDepth: 2,
          maxDirectSubagents: 1,
          maxTotalSubagents: 2,
        },
      }),
    },
    {
      id: 'valid.missing_executionPolicy_defaults',
      ok: true,
      title: '缺 executionPolicy 时默认 {}',
      build: () => ({
        tools: [],
        skills: [],
        allowedSubagents: [],
      }),
      assertCreated: (agent) => {
        if (!agent?.config?.executionPolicy || typeof agent.config.executionPolicy !== 'object') {
          throw new Error(`expected default executionPolicy object, got ${JSON.stringify(agent?.config)}`)
        }
      },
    },
    {
      id: 'invalid.unknown_tool',
      ok: false,
      expectStatus: 400,
      messageIncludes: /unknown agent tool/i,
      title: '未知 tool 名',
      build: () => ({
        tools: ['definitely-not-a-real-tool'],
        skills: [],
        allowedSubagents: [],
        executionPolicy: {},
      }),
    },
    {
      id: 'invalid.duplicate_skill',
      ok: false,
      expectStatus: 400,
      messageIncludes: /duplicate|skill/i,
      title: 'skills 重复',
      build: () => ({
        tools: [],
        skills: ['a', 'a'],
        allowedSubagents: [],
        executionPolicy: {},
      }),
    },
    {
      id: 'invalid.non_positive_maxTurns',
      ok: false,
      expectStatus: 400,
      messageIncludes: /maxTurns|positive/i,
      title: 'maxTurns <= 0',
      build: () => ({
        tools: [],
        skills: [],
        allowedSubagents: [],
        executionPolicy: { maxTurns: 0 },
      }),
    },
  ]
}

export function providerCreateBody(suffix) {
  return {
    name: `e2e-provider-${suffix}`,
    description: 'e2e provider',
    providerType: 'openai',
    baseUrl: 'https://example.com/v1',
    credential: 'sk-e2e-test',
    modelCallTimeoutMillis: 1_800_000,
    modelCallIdleTimeoutMillis: 120_000,
  }
}

export function providerUpdateBody(name) {
  return {
    name,
    description: 'e2e provider updated',
    providerType: 'openai',
    baseUrl: 'https://example.com/v1',
    credential: '', // 保留密钥
    modelCallTimeoutMillis: 1_800_000,
    modelCallIdleTimeoutMillis: 120_000,
  }
}
