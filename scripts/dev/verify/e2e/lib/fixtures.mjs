/** 可复用的合法请求体与配置矩阵行定义。 */

import { assert, envelopeData, HttpError } from './http.mjs'

/**
 * 把 Model 逻辑名编码为 catalog 路径 tail：按 `/` 分段编码，保留逻辑名中的路径层级。
 *
 * <p>Model 名称是单一全局身份且允许包含斜杠；不能只对整串调用 `encodeURIComponent` 生成 `%2F`，
 * 也不能直接拼接未编码的空格或其他保留字符。
 */
export function encodePathTail(value) {
  return String(value).split('/').map(encodeURIComponent).join('/')
}

/**
 * 完整价格定义：基础费率 + 有序覆盖/倍率规则。
 *
 * <p>所有价格与倍率在 wire 上都是十进制字符串；普通缺价由服务端归一化为 0，reasoning=null 表示跟随
 * output。测试默认使用可直接读回的完整定义，而不是省略 base 或伪造 unpriced 状态。
 */
export function basePricing(overrides = {}) {
  return {
    base: {
      input: '1',
      output: '2',
      cacheRead: '0',
      cacheWrite: '0',
      cacheWriteByTtl: {},
      reasoning: null,
      ...(overrides.base ?? {}),
    },
    overrides: overrides.overrides ?? [],
    multipliers: overrides.multipliers ?? [],
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
 * 把种子目录（持久化形态：variant 允许省略 `protocolOptionsJson`）规范化为公开读取契约的 canonical 形态。
 *
 * <p>产品读取路径（`AgentModelRuntimeConfigParser`）对每个 variant 统一返回 `protocolOptionsJson` 字符串，其中 null / 空选项
 * 等价于 `{}`；既有选项文本原样保留，其它字段一概不改写、不丢弃。
 */
export function canonicalSeedCatalog(models) {
  return models.map((model) => ({
    ...model,
    config: {
      ...model.config,
      variants: model.config.variants.map((variant) => ({
        ...variant,
        protocolOptionsJson: variant.protocolOptionsJson ?? '{}',
      })),
    },
  }))
}

/**
 * Model config 校验矩阵。
 * ok=true 期望 2xx；ok=false 期望 4xx 且 message 匹配。
 */
export function modelConfigMatrix() {
  const good = () => baseModelConfig()
  return [
    // 严格 JSON 文本边界：拒绝格式错误/重复键，以及绕过字符串传输的对象值。
    ...[
      ['malformed', '{invalid}'],
      ['duplicate_keys', '{"a":1,"a":2}'],
      ['non_object', '[]'],
      ['trailing_token', '{} true'],
      ['non_string', { temperature: 0.5 }],
    ].map(([suffix, protocolOptionsJson]) => ({
      id: `invalid.protocol_options_${suffix}`,
      ok: false,
      expectStatus: 400,
      messageIncludes: /protocolOptionsJson|Failed to read request|Bad Request/i,
      title: `protocolOptionsJson 严格边界：${suffix}`,
      build: () => baseModelConfig({
        variants: [{ id: 'default', protocolOptionsJson }],
      }),
    })),
    {
      id: 'valid.minimal',
      ok: true,
      title: '最小合法 config',
      build: () => good(),
    },
    {
      id: 'valid.pricing_rules',
      ok: true,
      title: 'base/overrides/multipliers 十进制字符串',
      build: () =>
        baseModelConfig({
          pricing: basePricing({
            base: {
              input: '1.25',
              output: '2.5',
              cacheRead: '0.5',
              cacheWrite: '1',
              cacheWriteByTtl: { 3600: '1.5' },
              reasoning: null,
            },
            overrides: [
              {
                id: 'long-context',
                when: { context: { gt: 100000 } },
                rates: { input: '2', reasoning: null },
              },
            ],
            multipliers: [
              {
                id: 'batch-tier',
                when: { serviceTier: ['batch'] },
                factors: { input: '0.5', output: '0.5' },
              },
            ],
          }),
        }),
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
      id: 'invalid.pricing_numeric',
      ok: false,
      expectStatus: 400,
      messageIncludes: /price|decimal|pricing/i,
      title: '价格必须是十进制字符串而非 JSON 数值',
      build: () => baseModelConfig({ pricing: basePricing({ base: { input: 1 } }) }),
    },
    {
      id: 'invalid.pricing_negative',
      ok: false,
      expectStatus: 400,
      messageIncludes: /negative|pricing|input/i,
      title: '价格为负',
      build: () => baseModelConfig({ pricing: basePricing({ base: { output: '-1' } }) }),
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
      id: 'invalid.oversized_reasoning_effort',
      ok: false,
      expectStatus: 400,
      messageIncludes: /reasoningEffort|variant/i,
      title: 'reasoningEffort 超过长度限制',
      build: () =>
        baseModelConfig({
          variants: [{ id: 'default', reasoningEffort: 'x'.repeat(65) }],
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
      title: '空 tools/skills/subagents 且继承开关缺省为 true',
      build: () => ({
        tools: [],
        skills: [],
        subagents: [],
      }),
      assertCreated: (agent) => {
        if (agent.config?.inheritParentEnvironment !== true) {
          throw new Error(`inheritParentEnvironment must default to true: ${JSON.stringify(agent)}`)
        }
      },
    },
    {
      id: 'valid.disable_parent_environment_inheritance',
      ok: true,
      title: '显式关闭父 Environment 继承',
      build: () => ({
        tools: [],
        skills: [],
        subagents: [],
        inheritParentEnvironment: false,
      }),
      assertCreated: (agent) => {
        if (agent.config?.inheritParentEnvironment !== false) {
          throw new Error(`inheritParentEnvironment=false was not preserved: ${JSON.stringify(agent)}`)
        }
      },
    },
    {
      id: 'valid.goal_contributor_tools',
      ok: true,
      title: 'Goal contributor 工具已进入可选 ToolCatalog',
      // Goal 正文只由用户经 typed GOAL 命令维护：Goal contributor 只贡献 get_goal/update_goal，没有创建工具。
      build: () => ({
        tools: ['get_goal', 'update_goal'],
        skills: [],
        subagents: [],
        inheritParentEnvironment: true,
      }),
    },
    {
      id: 'invalid.missing_tools',
      ok: false,
      expectStatus: 400,
      messageIncludes: /tools.*required/i,
      title: 'tools 必填',
      build: () => ({
        skills: [],
        subagents: [],
        inheritParentEnvironment: true,
      }),
    },
    {
      id: 'invalid.missing_skills',
      ok: false,
      expectStatus: 400,
      messageIncludes: /skills.*required/i,
      title: 'skills 必填',
      build: () => ({
        tools: [],
        subagents: [],
        inheritParentEnvironment: true,
      }),
    },
    {
      id: 'invalid.missing_subagents',
      ok: false,
      expectStatus: 400,
      messageIncludes: /subagents.*required/i,
      title: 'subagents 必填',
      build: () => ({
        tools: [],
        skills: [],
        inheritParentEnvironment: true,
      }),
    },
    {
      id: 'invalid.null_inherit_parent_environment',
      ok: false,
      expectStatus: 400,
      messageIncludes: /inheritParentEnvironment|required/i,
      title: 'inheritParentEnvironment 显式 null 拒绝',
      build: () => ({
        tools: [],
        skills: [],
        subagents: [],
        inheritParentEnvironment: null,
      }),
    },
    {
      id: 'invalid.non_boolean_inherit_parent_environment',
      ok: false,
      expectStatus: 400,
      messageIncludes: /Failed to read request|inheritParentEnvironment|boolean/i,
      title: 'inheritParentEnvironment 非布尔值拒绝',
      build: () => ({
        tools: [],
        skills: [],
        subagents: [],
        inheritParentEnvironment: 'false',
      }),
    },
    {
      id: 'invalid.unknown_tool',
      ok: false,
      expectStatus: 400,
      messageIncludes: /unknown agent tool/i,
      title: '未知 tool name',
      build: () => ({
        tools: ['definitely_not_a_real_tool'],
        skills: [],
        subagents: [],
        inheritParentEnvironment: true,
      }),
    },
    {
      id: 'invalid.duplicate_skill_name',
      ok: false,
      expectStatus: 400,
      messageIncludes: /duplicate|skill/i,
      title: '全局 Skill 名称重复',
      // skills 的 wire shape 是 (packageName, name) 引用列表；两个相同引用必须命中 duplicate 校验。
      build: () => ({
        tools: [],
        skills: [
          { packageName: 'tools', name: 'dev' },
          { packageName: 'tools', name: 'dev' },
        ],
        subagents: [],
        inheritParentEnvironment: true,
      }),
    },
    {
      id: 'invalid.duplicate_subagent',
      ok: false,
      expectStatus: 400,
      messageIncludes: /duplicate|subagent/i,
      title: 'subagents 重复',
      build: () => ({
        tools: [],
        skills: [],
        subagents: ['missing-agent', 'missing-agent'],
        inheritParentEnvironment: true,
      }),
    },
    {
      id: 'invalid.unknown_subagent',
      ok: false,
      expectStatus: 404,
      messageIncludes: /agent_definition.*not found|not found/i,
      title: '未知 subagent 引用',
      build: () => ({
        tools: [],
        skills: [],
        subagents: ['definitely-not-a-real-agent'],
        inheritParentEnvironment: true,
      }),
    },
    {
      id: 'invalid.unknown_field_rejected',
      ok: false,
      expectStatus: 400,
      messageIncludes: /Failed to read request|Bad Request/i,
      title: '未知字段 rejected',
      build: () => ({
        tools: [],
        skills: [],
        subagents: [],
        inheritParentEnvironment: true,
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

/**
 * Provider PUT 可编辑字段（不含 `name`：资源路由身份在 path，请求体携带 `name` 会被严格反序列化拒绝）。
 * `overrides` 用于表达 `modelHttpRetryStatusCodes` 的省略（不传键）/ 显式 null / 数组三态。
 */
export function providerUpdateBody(overrides = {}) {
  return {
    description: 'e2e provider updated',
    providerType: 'openai',
    baseUrl: 'https://example.com/v1',
    credential: '', // 保留密钥
    modelCallTimeoutMillis: 1_800_000,
    modelCallIdleTimeoutMillis: 120_000,
    ...overrides,
  }
}

/**
 * Environment fixture：创建一次性 Environment Card 并返回权威 Card。
 *
 * <p>用例只依赖 Card 的 id 与 CAS version；一次性 registrationToken 不进入断言，也不写入 artifact。
 */
export async function createEnvironmentFixture(ctx, name) {
  const { json } = await ctx.call('POST', '/api/environments', { name })
  const card = envelopeData(json)
  assert(
    card && typeof card === 'object' && card.id && card.version != null,
    'created environment must expose id and version',
  )
  return card
}

/**
 * Environment fixture 清理：先读回权威版本，再用它做 CAS 删除。
 *
 * <p>环境已经不存在（404）视为清理完成；其它 canonical 错误原样抛出，既不用陈旧版本盲删，也不静默吞掉失败。
 */
export async function deleteEnvironmentFixture(ctx, environmentId) {
  const path = `/api/environments/${encodeURIComponent(environmentId)}`
  let card
  try {
    const { json } = await ctx.call('GET', path)
    card = envelopeData(json)
  } catch (error) {
    if (error instanceof HttpError && error.status === 404) {
      return
    }
    throw error
  }
  await ctx.call(
    'DELETE',
    `${path}?expectedVersion=${encodeURIComponent(String(card.version))}`,
  )
}
