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
    variants: [{ id: 'default' }],
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
            { id: 'medium', reasoningEffort: 'medium' },
            { id: 'high', reasoningEffort: 'high' },
          ],
        }),
    },
    {
      id: 'valid.explicit_off_variant',
      ok: true,
      title: '显式 off variant',
      build: () =>
        baseModelConfig({
          abilities: { tools: true, reasoning: true, inputModalities: ['TEXT'] },
          defaultVariant: 'off',
          variants: [
            { id: 'off', reasoningEffort: 'off' },
            { id: 'high', reasoningEffort: 'high' },
          ],
        }),
    },
    {
      id: 'valid.null_effort_uses_protocol_default',
      ok: true,
      title: 'reasoning + 未声明 effort（协议默认）',
      build: () =>
        baseModelConfig({
          abilities: { tools: true, reasoning: true, inputModalities: ['TEXT'] },
          defaultVariant: 'default',
          variants: [{ id: 'default' }, { id: 'high', reasoningEffort: 'high' }],
        }),
    },
    {
      id: 'invalid.effort_without_reasoning_ability',
      ok: false,
      expectStatus: 400,
      messageIncludes: /reasoningEffort.*reasoning|reasoning.*false/i,
      title: 'reasoning=false 但 variant 仍声明 effort',
      build: () =>
        baseModelConfig({
          abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
          variants: [{ id: 'default', reasoningEffort: 'high' }],
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
            { id: 'default', reasoningEffort: 'high' },
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
      id: 'invalid.unknown_variant_field',
      ok: false,
      expectStatus: 400,
      messageIncludes: /Failed to read request|Bad Request|unknown/i,
      title: '已删除的 variant 字段被拒绝',
      build: () =>
        baseModelConfig({
          variants: [{ id: 'default', temperature: 0.5 }],
        }),
    },
    {
      id: 'invalid.unknown_reasoning_effort',
      ok: false,
      expectStatus: 400,
      messageIncludes: /reasoningEffort|variant/i,
      title: 'reasoningEffort 非法取值',
      build: () =>
        baseModelConfig({
          variants: [{ id: 'default', reasoningEffort: 'extreme' }],
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
      id: 'valid.empty_lists',
      ok: true,
      title: '空 toolIds/skills/subagents',
      build: () => ({
        toolIds: [],
        skills: [],
        subagents: [],
      }),
    },
    {
      id: 'valid.goal_contributor_tools',
      ok: true,
      title: 'Goal contributor 工具已进入可选 ToolCatalog',
      build: () => ({
        toolIds: ['base.goal.create', 'base.goal.get', 'base.goal.update'],
        skills: [],
        subagents: [],
      }),
    },
    {
      id: 'invalid.missing_toolIds',
      ok: false,
      expectStatus: 400,
      messageIncludes: /toolIds.*required/i,
      title: 'toolIds 必填',
      build: () => ({
        skills: [],
        subagents: [],
      }),
    },
    {
      id: 'invalid.missing_skills',
      ok: false,
      expectStatus: 400,
      messageIncludes: /skills.*required/i,
      title: 'skills 必填',
      build: () => ({
        toolIds: [],
        subagents: [],
      }),
    },
    {
      id: 'invalid.missing_subagents',
      ok: false,
      expectStatus: 400,
      messageIncludes: /subagents.*required/i,
      title: 'subagents 必填',
      build: () => ({
        toolIds: [],
        skills: [],
      }),
    },
    {
      id: 'invalid.unknown_tool',
      ok: false,
      expectStatus: 400,
      messageIncludes: /unknown agent tool/i,
      title: '未知 toolId',
      build: () => ({
        toolIds: ['definitely-not-a-real-tool'],
        skills: [],
        subagents: [],
      }),
    },
    {
      id: 'invalid.duplicate_skill_ref',
      ok: false,
      expectStatus: 400,
      messageIncludes: /duplicate|skill/i,
      title: '复合 skill ref 重复',
      build: () => ({
        toolIds: [],
        skills: [
          {
            sourceId: '11111111-1111-4111-8111-111111111111',
            name: 'a',
          },
          {
            sourceId: '11111111-1111-4111-8111-111111111111',
            name: 'a',
          },
        ],
        subagents: [],
      }),
    },
    {
      id: 'invalid.duplicate_subagent',
      ok: false,
      expectStatus: 400,
      messageIncludes: /duplicate|subagent/i,
      title: 'subagents 重复',
      build: () => ({
        toolIds: [],
        skills: [],
        subagents: ['missing-agent', 'missing-agent'],
      }),
    },
    {
      id: 'invalid.unknown_subagent',
      ok: false,
      expectStatus: 404,
      messageIncludes: /agent_definition.*not found|not found/i,
      title: '未知 subagent 引用',
      build: () => ({
        toolIds: [],
        skills: [],
        subagents: ['definitely-not-a-real-agent'],
      }),
    },
    {
      id: 'invalid.unknown_field_rejected',
      ok: false,
      expectStatus: 400,
      messageIncludes: /Failed to read request|Bad Request/i,
      title: '未知字段 rejected',
      build: () => ({
        toolIds: [],
        skills: [],
        subagents: [],
        unexpected: true,
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
