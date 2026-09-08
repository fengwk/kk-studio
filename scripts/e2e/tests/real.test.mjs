import assert from 'node:assert/strict'
import test from 'node:test'

import { ALL_CASES } from '../lib/registry.mjs'
import {
  REAL_MODEL_DEFINITIONS,
  MINIMAX_ANTHROPIC_M3,
  MIN_CACHE_PREFIX_BYTES,
  buildCachePrefix,
  buildTextCacheSystemPrompt,
  assertAssistantUsage,
  assertProviderUsageAlgebra,
  sanitizeArtifact,
  safeDiagnosticJson,
  resolveRealModel,
  requireRealMiniMaxM3,
} from '../cases/real.mjs'

test('十二个指定 real case 正确注册到 case registry 且旧 text_turn 移除', () => {
  // 测试意图：确保四个声明式模型文本+缓存 case、四个多推理级别烟雾 case 和四个真实工具 case 正确注册，旧单模型 real.text_turn 已被彻底移除，注册表保持干净且可单独 --only 运行。
  const caseIds = ALL_CASES.map((c) => c.id)

  const expectedTextCacheIds = [
    'real.text_cache.google_gemini',
    'real.text_cache.openai_responses',
    'real.text_cache.minimax_anthropic',
    'real.text_cache.deepseek_chat',
  ]
  for (const id of expectedTextCacheIds) {
    assert.equal(caseIds.includes(id), true, `missing text cache case: ${id}`)
  }

  const expectedReasoningLevelsIds = [
    'real.reasoning_levels.google_gemini',
    'real.reasoning_levels.openai_responses',
    'real.reasoning_levels.minimax_anthropic',
    'real.reasoning_levels.deepseek_chat',
  ]
  for (const id of expectedReasoningLevelsIds) {
    assert.equal(caseIds.includes(id), true, `missing reasoning levels case: ${id}`)
  }

  const expectedToolIds = [
    'real.tool.google_gemini',
    'real.tool.openai_responses',
    'real.tool.minimax_anthropic',
    'real.tool.deepseek_chat',
  ]
  for (const id of expectedToolIds) {
    assert.equal(caseIds.includes(id), true, `missing tool case: ${id}`)
  }

  assert.equal(caseIds.includes('real.text_turn'), false, 'legacy real.text_turn must be removed')
})

test('真实用例矩阵 capability 声明严格对齐规范（text_cache/reasoning_levels 仅 real，tool 与 delegation 需 real+tools）', () => {
  // 测试意图：验证用例依赖的前置条件声明准确无误，避免无工具环境误跑工具测试或真实工具 case 遗漏 tools capability 标记。
  for (const def of REAL_MODEL_DEFINITIONS) {
    const textCacheCase = ALL_CASES.find((c) => c.id === `real.text_cache.${def.idSuffix}`)
    assert.ok(textCacheCase, `text cache case not found for ${def.idSuffix}`)
    assert.equal(textCacheCase.level, 'L2')
    assert.deepEqual([...textCacheCase.requires].sort(), ['real'])

    const reasoningCase = ALL_CASES.find((c) => c.id === `real.reasoning_levels.${def.idSuffix}`)
    assert.ok(reasoningCase, `reasoning levels case not found for ${def.idSuffix}`)
    assert.equal(reasoningCase.level, 'L2')
    assert.deepEqual([...reasoningCase.requires].sort(), ['real'])

    const toolCase = ALL_CASES.find((c) => c.id === `real.tool.${def.idSuffix}`)
    assert.ok(toolCase, `tool case not found for ${def.idSuffix}`)
    assert.equal(toolCase.level, 'L4')
    assert.deepEqual([...toolCase.requires].sort(), ['real', 'tools'])
  }

  const delegationCase = ALL_CASES.find((c) => c.id === 'real.task_delegation')
  assert.ok(delegationCase, 'real.task_delegation missing')
  assert.deepEqual([...delegationCase.requires].sort(), ['real', 'tools'])

  const stopCase = ALL_CASES.find((c) => c.id === 'real.stop_partial_continue')
  assert.ok(stopCase, 'real.stop_partial_continue missing')
  assert.deepEqual([...stopCase.requires].sort(), ['real'])

  const readTurnCase = ALL_CASES.find((c) => c.id === 'tool.read_turn')
  assert.ok(readTurnCase, 'tool.read_turn missing')
  assert.deepEqual([...readTurnCase.requires].sort(), ['canvas-storage', 'real', 'tools'])
})

test('四指定模型声明式定义与 seed/credential 公共契约完全一致', () => {
  // 测试意图：验证模型定义与并行切片所实现的 seed 及供应商配置契约完全一致，确保 providerName、modelName、variant 及 providerType 无拼写偏离。
  assert.equal(REAL_MODEL_DEFINITIONS.length, 4)

  const google = REAL_MODEL_DEFINITIONS.find((d) => d.idSuffix === 'google_gemini')
  assert.deepEqual(google, {
    idSuffix: 'google_gemini',
    title: 'Google Gemini',
    providerName: 'google',
    modelName: 'gemini-3.8-flash',
    variant: 'minimal',
    variants: ['minimal', 'low', 'medium', 'high'],
    providerType: 'google',
  })

  const openai = REAL_MODEL_DEFINITIONS.find((d) => d.idSuffix === 'openai_responses')
  assert.deepEqual(openai, {
    idSuffix: 'openai_responses',
    title: 'OpenAI Responses',
    providerName: 'openai',
    modelName: 'gpt-5.6-luna',
    variant: 'off',
    variants: ['off', 'low', 'medium', 'high', 'xhigh', 'max'],
    providerType: 'openai_response',
  })

  const minimax = REAL_MODEL_DEFINITIONS.find((d) => d.idSuffix === 'minimax_anthropic')
  assert.deepEqual(minimax, {
    idSuffix: 'minimax_anthropic',
    title: 'MiniMax Anthropic',
    providerName: 'minimax-anthropic',
    modelName: 'MiniMax-M3',
    variant: 'off',
    variants: ['off', 'minimal', 'low', 'medium', 'high'],
    providerType: 'anthropic',
  })

  const deepseek = REAL_MODEL_DEFINITIONS.find((d) => d.idSuffix === 'deepseek_chat')
  assert.deepEqual(deepseek, {
    idSuffix: 'deepseek_chat',
    title: 'DeepSeek Chat',
    providerName: 'deepseek',
    modelName: 'deepseek-v4-flash',
    variant: 'off',
    variants: ['off', 'low', 'high', 'max'],
    providerType: 'openai',
  })

  assert.equal(MINIMAX_ANTHROPIC_M3, minimax)
})

test('prompt cache 确定性前缀与 system prompt 尺寸满足 >=16KiB 边界', () => {
  // 测试意图：验证 system prompt 前缀大小满足各类大模型 Prompt Caching 的最低 token/byte 门槛（>= 16KiB = 16384 bytes），并具备严格 marker 回复约束。
  assert.equal(MIN_CACHE_PREFIX_BYTES, 16384)

  const prefix1 = buildCachePrefix()
  const prefix2 = buildCachePrefix()
  assert.equal(prefix1, prefix2, 'prefix must be deterministic across calls')
  assert.ok(
    Buffer.byteLength(prefix1, 'utf8') >= 16384,
    `prefix size in bytes must be >= 16384, got: ${Buffer.byteLength(prefix1, 'utf8')}`,
  )

  const prompt = buildTextCacheSystemPrompt()
  assert.ok(
    Buffer.byteLength(prompt, 'utf8') >= 16384,
    `system prompt size in bytes must be >= 16384, got: ${Buffer.byteLength(prompt, 'utf8')}`,
  )
  assert.ok(prompt.includes('Strict Instruction:'), 'system prompt must include strict instruction')
  assert.ok(prompt.includes('exact marker text'), 'system prompt must enforce exact marker emission')
})

test('assertAssistantUsage 严格校验用量七字段非负性与事实有效性', () => {
  // 测试意图：验证模型用量元数据断言函数能正确拦截缺失字段、负数值、非安全整数以及零 IO，确保真实 Provider 返回了实质性 token 用量。
  const validUsage = {
    inputTokens: 100,
    outputTokens: 20,
    cacheReadTokens: 50,
    cacheWriteTokens: 0,
    cacheWriteLongTokens: 0,
    reasoningTokens: 0,
    providerTotalTokens: 170,
  }

  assert.doesNotThrow(() => assertAssistantUsage(validUsage))
  assert.doesNotThrow(() => assertAssistantUsage(validUsage, { requirePositiveIO: true }))

  // 缺少字段
  const missingField = { ...validUsage }
  delete missingField.cacheReadTokens
  assert.throws(() => assertAssistantUsage(missingField), /non-negative safe integer/)

  // 负数
  assert.throws(
    () => assertAssistantUsage({ ...validUsage, inputTokens: -1 }),
    /non-negative safe integer/,
  )

  // 浮点数
  assert.throws(
    () => assertAssistantUsage({ ...validUsage, outputTokens: 10.5 }),
    /non-negative safe integer/,
  )

  // requirePositiveIO 检查零 IO
  assert.throws(
    () => assertAssistantUsage({ ...validUsage, inputTokens: 0 }, { requirePositiveIO: true }),
    /inputTokens must be > 0/,
  )
  assert.throws(
    () => assertAssistantUsage({ ...validUsage, outputTokens: 0 }, { requirePositiveIO: true }),
    /outputTokens must be > 0/,
  )
  assert.throws(
    () => assertAssistantUsage({ ...validUsage, providerTotalTokens: 0 }, { requirePositiveIO: true }),
    /providerTotalTokens must be > 0/,
  )

  // requirePositiveIO: false 时允许零 IO
  assert.doesNotThrow(() =>
    assertAssistantUsage(
      {
        inputTokens: 0,
        outputTokens: 0,
        cacheReadTokens: 0,
        cacheWriteTokens: 0,
        cacheWriteLongTokens: 0,
        reasoningTokens: 0,
        providerTotalTokens: 0,
      },
      { requirePositiveIO: false },
    ),
  )
})

test('sanitizeArtifact 递归脱敏，彻底杜绝敏感凭据与 base URL 泄漏到 artifact', () => {
  // 测试意图：验证 artifact 脱敏工具无论遇到何种嵌套层级的对象或字符串，都能可靠替换掉 apiKey、secret、password、baseUrl、Bearer 与完整 HTTP URL，确保测试产物安全合规。
  const dirtyData = {
    threadId: '00000000-0000-0000-0000-000000000001',
    status: 'IDLE',
    provider: {
      name: 'minimax-anthropic',
      baseUrl: 'https://api.minimaxi.chat/v1',
      apiKey: 'sk-abcdef1234567890',
      nestedAuth: {
        password: 'super-secret-password',
        rawToken: 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9',
      },
    },
    message: 'Check download at https://example.com/blob/123 for detail',
    endpointUrl: 'http://localhost:8080/api',
    diagnostic: 'TEST_OPENAI_API_KEY=short-secret Bearer bearer-value',
    usage: {
      inputTokens: 120,
    },
  }

  const cleaned = sanitizeArtifact(dirtyData)
  assert.equal(cleaned.threadId, '00000000-0000-0000-0000-000000000001')
  assert.equal(cleaned.status, 'IDLE')
  assert.equal(cleaned.provider.name, 'minimax-anthropic')
  assert.equal(cleaned.provider.baseUrl, '[REDACTED]')
  assert.equal(cleaned.provider.apiKey, '[REDACTED]')
  assert.equal(cleaned.provider.nestedAuth.password, '[REDACTED]')
  assert.equal(cleaned.provider.nestedAuth.rawToken, '[REDACTED]')
  assert.equal(cleaned.endpointUrl, '[REDACTED_URL]')
  assert.equal(
    cleaned.diagnostic,
    'TEST_OPENAI_API_KEY=[REDACTED] Bearer [REDACTED]',
  )
  assert.equal(
    cleaned.message,
    'Check download at [REDACTED_URL] for detail',
  )
  assert.equal(cleaned.usage.inputTokens, 120)
})

test('resolveRealModel 与安全 resolver 校验 catalog 契约，失败时仅暴露公开 DTO', async () => {
  // 测试意图：验证在缺失 Provider、providerType 错配、未配置凭证或缺少 baseUrl 时，异常消息只引用公开 DTO 字段，不会泄露任何未授权敏感信息。
  const mockProviders = [
    {
      name: 'google',
      providerType: 'google',
      configured: true,
      baseUrl: 'https://generativelanguage.googleapis.com',
      version: '1',
    },
    {
      name: 'openai',
      providerType: 'openai_response',
      configured: false, // 未配置
      baseUrl: 'https://api.openai.com/v1',
      version: '1',
    },
  ]

  const mockModels = [
    {
      providerName: 'google',
      name: 'gemini-3.8-flash',
      config: {
        defaultVariant: 'minimal',
        variants: [
          { id: 'minimal' },
          { id: 'low' },
          { id: 'medium' },
          { id: 'high' },
        ],
      },
    },
  ]

  const fakeCtx = {
    async call(method, url) {
      if (url.startsWith('/api/ai/catalog/providers')) {
        return { json: { code: 0, data: { results: mockProviders, totalCount: mockProviders.length } } }
      }
      if (url.startsWith('/api/ai/catalog/models')) {
        return { json: { code: 0, data: { results: mockModels, totalCount: mockModels.length } } }
      }
      throw new Error(`unexpected url: ${url}`)
    },
  }

  // 1. Google 成功解析
  const resolved = await resolveRealModel(fakeCtx, REAL_MODEL_DEFINITIONS[0])
  assert.equal(resolved.providerName, 'google')
  assert.equal(resolved.modelName, 'gemini-3.8-flash')
  assert.equal(resolved.variant, 'minimal')
  assert.equal(resolved.provider.name, 'google')

  // 2. OpenAI 未配置应被拦截，且错误消息不含敏感凭证
  await assert.rejects(
    () => resolveRealModel(fakeCtx, REAL_MODEL_DEFINITIONS[1]),
    (err) => {
      assert.ok(err.message.includes('openai is not configured'))
      return true
    },
  )

  // 3. MiniMax 未在 mock catalog 中出现应被拦截
  await assert.rejects(
    () => requireRealMiniMaxM3(fakeCtx),
    (err) => {
      assert.ok(err.message.includes('provider minimax-anthropic not found in catalog'))
      return true
    },
  )
})

test('assertProviderUsageAlgebra 针对四大厂商规范代数严格断言正确与违规 fixture', () => {
  // 测试意图：使用合成 fixture 严密验证四大供应商（Google、OpenAI Responses、Anthropic、DeepSeek）的归一化 ModelUsage 代数等式及非法边界。

  // 1. Google Gemini
  const validGemini = {
    inputTokens: 100,
    outputTokens: 20,
    cacheReadTokens: 50,
    cacheWriteTokens: 0,
    cacheWriteLongTokens: 0,
    reasoningTokens: 30,
    providerTotalTokens: 200, // 100 + 50 + 20 + 30
  }
  assert.doesNotThrow(() => assertProviderUsageAlgebra(validGemini, 'google'))
  // 违规：Gemini 不支持 cacheWriteTokens
  assert.throws(
    () => assertProviderUsageAlgebra({ ...validGemini, cacheWriteTokens: 5 }, 'google'),
    /cacheWriteTokens must be 0/,
  )
  // 违规：providerTotal 算术不平
  assert.throws(
    () => assertProviderUsageAlgebra({ ...validGemini, providerTotalTokens: 199 }, 'google'),
    /Gemini providerTotalTokens algebra mismatch/,
  )

  // 2. OpenAI Responses
  const validOpenAi = {
    inputTokens: 80,
    outputTokens: 20,
    cacheReadTokens: 20,
    cacheWriteTokens: 10,
    cacheWriteLongTokens: 0,
    reasoningTokens: 15,
    providerTotalTokens: 145, // wireInput (80+20+10=110) + wireOutput (20+15=35) = 145
  }
  assert.doesNotThrow(() => assertProviderUsageAlgebra(validOpenAi, 'openai_response'))
  // 违规：OpenAI Responses 不支持 cacheWriteLongTokens
  assert.throws(
    () => assertProviderUsageAlgebra({ ...validOpenAi, cacheWriteLongTokens: 5 }, 'openai_response'),
    /cacheWriteLongTokens must be 0/,
  )
  // 违规：providerTotal 算术不平
  assert.throws(
    () => assertProviderUsageAlgebra({ ...validOpenAi, providerTotalTokens: 140 }, 'openai_response'),
    /OpenAI Responses providerTotalTokens algebra mismatch/,
  )

  // 3. Anthropic
  const validAnthropicZeroTotal = {
    inputTokens: 100,
    outputTokens: 50,
    cacheReadTokens: 25,
    cacheWriteTokens: 15,
    cacheWriteLongTokens: 10,
    reasoningTokens: 0,
    providerTotalTokens: 0, // Anthropic 闭包后必须为 0
  }
  assert.doesNotThrow(() => assertProviderUsageAlgebra(validAnthropicZeroTotal, 'anthropic'))
  // 违规：Anthropic reasoningTokens 归一化 DTO 必须为 0（因 wire 计入 outputTokens，不重复计数）
  assert.throws(
    () => assertProviderUsageAlgebra({ ...validAnthropicZeroTotal, reasoningTokens: 5 }, 'anthropic'),
    /Anthropic reasoningTokens must be 0 in normalized DTO/,
  )
  // 违规：Anthropic providerTotalTokens 必须为 0，任何非零均为非法
  assert.throws(
    () => assertProviderUsageAlgebra({ ...validAnthropicZeroTotal, providerTotalTokens: 200 }, 'anthropic'),
    /Anthropic providerTotalTokens must be 0 in normalized DTO/,
  )

  // 4. DeepSeek (OpenAI Chat)
  const validDeepSeek = {
    inputTokens: 100,
    outputTokens: 20,
    cacheReadTokens: 30,
    cacheWriteTokens: 0,
    cacheWriteLongTokens: 0,
    reasoningTokens: 40,
    providerTotalTokens: 190, // wirePrompt (100+30=130) + wireCompletion (20+40=60) = 190
  }
  assert.doesNotThrow(() => assertProviderUsageAlgebra(validDeepSeek, 'openai'))
  // 违规：DeepSeek 不支持 cacheWriteTokens
  assert.throws(
    () => assertProviderUsageAlgebra({ ...validDeepSeek, cacheWriteTokens: 10 }, 'openai'),
    /cacheWriteTokens must be 0/,
  )
  // 违规：providerTotal 算术不平
  assert.throws(
    () => assertProviderUsageAlgebra({ ...validDeepSeek, providerTotalTokens: 180 }, 'openai'),
    /DeepSeek providerTotalTokens algebra mismatch/,
  )
})

test('sanitizeArtifact 与 safeDiagnosticJson 递归严格脱敏推理文本、回放载荷与敏感元数据', () => {
  // 测试意图：确保 Anthropic thinking/redacted data/signature、OpenAI Responses reasoning summary/encrypted_content、OpenAI Chat reasoning_content/details、Gemini thought 兄弟 text/thoughtSignature、replay payload/raw usage/URL/credential/HTTP body 等任何敏感信息均无法在 sanitizeArtifact 与 safeDiagnosticJson 的序列化输出中存活，同时保留必要结构性长度与类型元数据。
  const sensitiveSource = {
    threadId: '00000000-0000-0000-0000-000000000001',
    status: 'IDLE',
    // 凭据与密钥
    apiKey: 'sk-abcdef1234567890',
    secretKey: 'top-secret-val',
    password: 'super-secret-password',
    authorization: 'Bearer super-secret-bearer-token',
    gatewayToken: 'gateway-secret-token',
    daemonToken: 'daemon-secret-token',
    auth: { raw: 'nested-secret-auth' },
    diagnostic: 'TEST_OPENAI_API_KEY=sensitive-key TEST_MINIMAX_API_KEY=another-key Bearer test-bearer-val',
    // URL
    url: 'https://api.openai.com/v1/chat/completions',
    baseUrl: 'https://api.minimaxi.chat/v1',
    endpointUrl: 'http://localhost:8080/api/v1',
    endpoint: 'https://generativelanguage.googleapis.com',
    message: 'Check download at https://example.com/blob/123 and http://internal.corp/data for detail',
    // Anthropic thinking block & redacted thinking & signature
    anthropicThinking: {
      type: 'thinking',
      thinking: 'secret thinking contents from Anthropic Claude model',
      signature: 'anthropic-signature-123456',
    },
    anthropicRedacted: {
      type: 'redacted_thinking',
      data: 'encrypted-thinking-data-789012',
    },
    anthropicThinkingWithText: {
      type: 'thinking',
      text: 'thinking in text field',
      signature: 'anthropic-sig-2',
    },
    // OpenAI Responses reasoning block, summary_text, encrypted_content
    responsesReasoning: {
      type: 'reasoning',
      summary_text: 'OpenAI Responses reasoning summary text',
      encrypted_content: 'responses-encrypted-content-abcdef',
      id: 'rs_12345',
    },
    responsesSummaryBlock: {
      type: 'summary_text',
      text: 'standalone Responses summary block text',
    },
    encryptedReasoningStandalone: 'encrypted-reasoning-standalone-val',
    summaryTextStandalone: 'summary-text-standalone-val',
    summary: 'summary-standalone-val',
    // OpenAI Chat reasoning_content, reasoning_details
    reasoning_content: 'DeepSeek chat reasoning content text',
    reasoning_details: [
      { step: 'detailed reasoning step 1' },
      { step: 'detailed reasoning step 2' },
    ],
    // Gemini thought part with thought: true (禁止残留兄弟 text 与 thoughtSignature)
    geminiPart: {
      thought: true,
      text: 'Gemini internal thinking process that must be stripped completely',
      thoughtSignature: 'gemini-signature-987654',
    },
    thoughtSignatureStandalone: 'gemini-signature-standalone-333',
    // replayState, replayPayload, raw usage, HTTP bodies
    replayState: { cursor: 'secret-cursor', messages: ['secret message'] },
    replayPayload: { payloadData: 'secret-replay-payload-leak' },
    rawUsageJson: '{"prompt_tokens":100,"completion_tokens":50}',
    rawUsageSnippet: '{"usage":{"total":150,"raw_usage_leak":true}}',
    httpBody: '{"stream":true,"prompt":"sensitive-http-body-leak"}',
    requestBody: '{"secret":"request-body-leak"}',
    responseBody: '{"secret":"response-body-leak"}',
    payloadJson: '{"apiKey":"sk-nested-payload-secret","replayPayload":{"bad":"nested-replay-leak"},"url":"https://nested.url.com/api"}',
    // 安全字段（应保留）
    inputTokens: 100,
    outputTokens: 50,
    reasoningTokens: 25,
    providerTotalTokens: 175,
  }

  const cleaned = sanitizeArtifact(sensitiveSource)
  const sanitizedJson = JSON.stringify(cleaned)
  const diagnosticJson = safeDiagnosticJson(sensitiveSource)

  // 1. 验证两者输出中绝对不存在任何敏感明文
  const sensitiveStrings = [
    'sk-abcdef1234567890',
    'top-secret-val',
    'super-secret-password',
    'super-secret-bearer-token',
    'gateway-secret-token',
    'daemon-secret-token',
    'nested-secret-auth',
    'sensitive-key',
    'another-key',
    'test-bearer-val',
    'https://api.openai.com',
    'https://api.minimaxi.chat',
    'http://localhost:8080',
    'https://generativelanguage.googleapis.com',
    'https://example.com',
    'http://internal.corp',
    'secret thinking contents from Anthropic Claude model',
    'anthropic-signature-123456',
    'encrypted-thinking-data-789012',
    'thinking in text field',
    'anthropic-sig-2',
    'OpenAI Responses reasoning summary text',
    'standalone Responses summary block text',
    'responses-encrypted-content-abcdef',
    'encrypted-reasoning-standalone-val',
    'summary-text-standalone-val',
    'summary-standalone-val',
    'DeepSeek chat reasoning content text',
    'detailed reasoning step 1',
    'detailed reasoning step 2',
    'Gemini internal thinking process that must be stripped completely',
    'gemini-signature-987654',
    'gemini-signature-standalone-333',
    'secret-cursor',
    'secret-replay-payload-leak',
    'prompt_tokens',
    'completion_tokens',
    'raw_usage_leak',
    'sensitive-http-body-leak',
    'request-body-leak',
    'response-body-leak',
    'sk-nested-payload-secret',
    'nested-replay-leak',
    'https://nested.url.com',
  ]

  for (const target of sensitiveStrings) {
    assert.equal(
      sanitizedJson.includes(target),
      false,
      `sanitizeArtifact leaked sensitive string: "${target}" in ${sanitizedJson}`,
    )
    assert.equal(
      diagnosticJson.includes(target),
      false,
      `safeDiagnosticJson leaked sensitive string: "${target}" in ${diagnosticJson}`,
    )
  }

  // 2. 验证结构性元数据保留
  assert.equal(cleaned.geminiPart.thought, true)
  assert.equal(cleaned.geminiPart.present, true)
  assert.equal(typeof cleaned.geminiPart.text, 'undefined')
  assert.equal(typeof cleaned.geminiPart.thoughtSignature, 'undefined')
  assert.equal(typeof cleaned.geminiPart.textLength, 'number')
  assert.ok(cleaned.geminiPart.textLength > 0)

  assert.equal(cleaned.anthropicThinking.type, 'thinking')
  assert.equal(cleaned.anthropicThinking.present, true)
  assert.equal(typeof cleaned.anthropicThinking.thinking, 'undefined')
  assert.equal(typeof cleaned.anthropicThinking.signature, 'undefined')
  assert.ok(cleaned.anthropicThinking.length > 0)

  assert.equal(cleaned.anthropicRedacted.type, 'redacted_thinking')
  assert.equal(cleaned.anthropicRedacted.present, true)
  assert.equal(typeof cleaned.anthropicRedacted.data, 'undefined')
  assert.ok(cleaned.anthropicRedacted.length > 0)

  assert.equal(cleaned.responsesReasoning.type, 'reasoning')
  assert.equal(cleaned.responsesReasoning.present, true)
  assert.equal(typeof cleaned.responsesReasoning.summary_text, 'undefined')
  assert.equal(typeof cleaned.responsesReasoning.encrypted_content, 'undefined')
  assert.equal(cleaned.responsesReasoning.id, 'rs_12345')
  assert.equal(cleaned.responsesSummaryBlock.type, 'summary_text')
  assert.equal(cleaned.responsesSummaryBlock.present, true)
  assert.equal(typeof cleaned.responsesSummaryBlock.text, 'undefined')
  assert.ok(cleaned.responsesSummaryBlock.length > 0)

  // 3. 安全 Token 与状态字段正常保留
  assert.equal(cleaned.inputTokens, 100)
  assert.equal(cleaned.outputTokens, 50)
  assert.equal(cleaned.reasoningTokens, 25)
  assert.equal(cleaned.providerTotalTokens, 175)
  assert.equal(cleaned.status, 'IDLE')
})

test('assertAssistantUsage 接受 OpenAI Responses 与 Chat 协议合法的 providerTotalTokens=0，但分类 token 为 0 时仍拒绝，正 total 仍做代数校验', () => {
  // 测试意图：验证上游 Responses 与 Chat Completions 协议在未返回 total_tokens 时归一化为 providerTotalTokens=0 属于协议合法，此时断言必须放行通过；但分类 input/output 事实有效性校验不能被削弱（分类为 0 仍必须拒绝）；且当 providerTotalTokens > 0 时代数等式仍必须严格闭合。

  // 1. OpenAI Responses (providerType: 'openai_response')
  const responsesZeroTotal = {
    inputTokens: 100,
    outputTokens: 20,
    cacheReadTokens: 10,
    cacheWriteTokens: 5,
    cacheWriteLongTokens: 0,
    reasoningTokens: 15,
    providerTotalTokens: 0, // 上游省略 total_tokens，协议合法为 0
  }
  // 零 total 且分类 token > 0 应成功放行
  assert.doesNotThrow(() =>
    assertAssistantUsage(responsesZeroTotal, {
      providerType: 'openai_response',
      requirePositiveIO: true,
    }),
  )

  // 但分类输入全部为 0 时仍必须拒绝
  assert.throws(
    () =>
      assertAssistantUsage(
        {
          ...responsesZeroTotal,
          inputTokens: 0,
          cacheReadTokens: 0,
          cacheWriteTokens: 0,
        },
        { providerType: 'openai_response', requirePositiveIO: true },
      ),
    /inputTokens \+ cacheReadTokens \+ cacheWriteTokens \+ cacheWriteLongTokens must be > 0/,
  )

  // 分类输出与推理全部为 0 时仍必须拒绝
  assert.throws(
    () =>
      assertAssistantUsage(
        { ...responsesZeroTotal, outputTokens: 0, reasoningTokens: 0 },
        { providerType: 'openai_response', requirePositiveIO: true },
      ),
    /outputTokens \+ reasoningTokens must be > 0/,
  )

  // 正 total 时严格执行代数校验：wireInput (100+10+5=115) + wireOutput (20+15=35) = 150
  assert.doesNotThrow(() =>
    assertAssistantUsage(
      { ...responsesZeroTotal, providerTotalTokens: 150 },
      { providerType: 'openai_response', requirePositiveIO: true },
    ),
  )
  assert.throws(
    () =>
      assertAssistantUsage(
        { ...responsesZeroTotal, providerTotalTokens: 149 },
        { providerType: 'openai_response', requirePositiveIO: true },
      ),
    /OpenAI Responses providerTotalTokens algebra mismatch/,
  )

  // 2. OpenAI Chat / DeepSeek (providerType: 'openai')
  const chatZeroTotal = {
    inputTokens: 100,
    outputTokens: 20,
    cacheReadTokens: 30,
    cacheWriteTokens: 0,
    cacheWriteLongTokens: 0,
    reasoningTokens: 40,
    providerTotalTokens: 0, // 上游省略 total_tokens，协议合法为 0
  }
  // 零 total 且分类 token > 0 应成功放行
  assert.doesNotThrow(() =>
    assertAssistantUsage(chatZeroTotal, {
      providerType: 'openai',
      requirePositiveIO: true,
    }),
  )

  // 但分类输入全部为 0 时仍必须拒绝
  assert.throws(
    () =>
      assertAssistantUsage(
        { ...chatZeroTotal, inputTokens: 0, cacheReadTokens: 0 },
        { providerType: 'openai', requirePositiveIO: true },
      ),
    /inputTokens \+ cacheReadTokens must be > 0/,
  )

  // 分类输出与推理全部为 0 时仍必须拒绝
  assert.throws(
    () =>
      assertAssistantUsage(
        { ...chatZeroTotal, outputTokens: 0, reasoningTokens: 0 },
        { providerType: 'openai', requirePositiveIO: true },
      ),
    /outputTokens \+ reasoningTokens must be > 0/,
  )

  // 正 total 时严格执行代数校验：wirePrompt (100+30=130) + wireCompletion (20+40=60) = 190
  assert.doesNotThrow(() =>
    assertAssistantUsage(
      { ...chatZeroTotal, providerTotalTokens: 190 },
      { providerType: 'openai', requirePositiveIO: true },
    ),
  )
  assert.throws(
    () =>
      assertAssistantUsage(
        { ...chatZeroTotal, providerTotalTokens: 189 },
        { providerType: 'openai', requirePositiveIO: true },
      ),
    /DeepSeek providerTotalTokens algebra mismatch/,
  )
})
