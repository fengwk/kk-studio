import assert from 'node:assert/strict'
import test from 'node:test'

import { canonicalSeedCatalog } from '../lib/fixtures.mjs'

/**
 * 测试意图：`seed.structured_model_config` 用公开 API 输出比对种子目录。种子资源是持久化形态（variant 允许省略
 * `protocolOptionsJson`），公开读取契约把 null / 空选项规范化为 `{}` 字符串；期望侧必须按同一契约补齐该字段，而不是把字段
 * 从整体比较中丢掉，也不能改写既有选项文本或其它字段。
 */
test('canonical seed catalog fills missing protocolOptionsJson with {}', () => {
  const [model] = canonicalSeedCatalog([
    {
      provider: 'anthropic',
      name: 'claude-fable-5',
      config: {
        defaultVariant: 'max',
        variants: [
          { id: 'minimal', reasoningEffort: 'low' },
          { id: 'max', reasoningEffort: 'high' },
        ],
      },
    },
  ])
  assert.deepEqual(model.config.variants, [
    { id: 'minimal', reasoningEffort: 'low', protocolOptionsJson: '{}' },
    { id: 'max', reasoningEffort: 'high', protocolOptionsJson: '{}' },
  ])
})

test('canonical seed catalog preserves explicit options text and every other field', () => {
  // 既有选项文本（含高精度小数与大整数）必须逐字保留；variant / config / model 的其它字段不得丢失或改写。
  const options = '{"temperature":0.30000000000000004,"max_tokens":9007199254740993}'
  const [model] = canonicalSeedCatalog([
    {
      provider: 'openai',
      name: 'gpt-5.4',
      modelId: 'gpt-5.4',
      description: 'GPT-5.4',
      config: {
        limit: { context: 272000, output: 128000 },
        defaultVariant: 'high',
        variants: [{ id: 'high', reasoningEffort: 'high', protocolOptionsJson: options }],
      },
    },
  ])
  assert.equal(model.config.variants[0].protocolOptionsJson, options)
  assert.equal(model.modelId, 'gpt-5.4')
  assert.equal(model.description, 'GPT-5.4')
  assert.deepEqual(model.config.limit, { context: 272000, output: 128000 })
  assert.equal(model.config.defaultVariant, 'high')
})

test('canonical seed catalog does not mutate its input', () => {
  const input = [
    {
      provider: 'zai',
      name: 'glm-5.2',
      config: { defaultVariant: 'max', variants: [{ id: 'max', reasoningEffort: 'high' }] },
    },
  ]
  const snapshot = JSON.stringify(input)
  canonicalSeedCatalog(input)
  assert.equal(JSON.stringify(input), snapshot)
})
