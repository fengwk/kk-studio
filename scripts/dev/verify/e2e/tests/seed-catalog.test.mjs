import assert from 'node:assert/strict'
import test from 'node:test'

import { canonicalSeedCatalog, encodePathTail, seedCatalogWithVariants } from '../lib/fixtures.mjs'

test('seed variants replace the Pi snapshot without accepting missing or extra models', () => {
  // 快照只保留上游信息；E2E variant 期望必须逐个匹配且完整。
  const base = [{ name: 'model', config: { variants: [{ id: 'old' }] } }]
  const variants = { model: [{ id: 'new', promptCacheRetention: 'NONE' }] }
  assert.deepEqual(seedCatalogWithVariants(base, variants)[0].config.variants, variants.model)
  assert.equal(base[0].config.variants[0].id, 'old')
  assert.throws(() => seedCatalogWithVariants(base, {}), /size mismatch/)
  assert.throws(() => seedCatalogWithVariants(base, { other: [] }), /missing variant fixture/)
})

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
          { id: 'minimal', reasoningEffort: 'low', promptCacheRetention: 'SHORT' },
          { id: 'max', reasoningEffort: 'high', promptCacheRetention: 'SHORT' },
        ],
      },
    },
  ])
  assert.deepEqual(model.config.variants, [
    { id: 'minimal', reasoningEffort: 'low', promptCacheRetention: 'SHORT', protocolOptionsJson: '{}' },
    { id: 'max', reasoningEffort: 'high', promptCacheRetention: 'SHORT', protocolOptionsJson: '{}' },
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
        variants: [{ id: 'high', reasoningEffort: 'high', promptCacheRetention: 'LONG', protocolOptionsJson: options }],
      },
    },
  ])
  assert.equal(model.config.variants[0].protocolOptionsJson, options)
  assert.equal(model.config.variants[0].promptCacheRetention, 'LONG')
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
      config: { defaultVariant: 'max', variants: [{ id: 'max', reasoningEffort: 'high', promptCacheRetention: 'SHORT' }] },
    },
  ]
  const snapshot = JSON.stringify(input)
  canonicalSeedCatalog(input)
  assert.equal(JSON.stringify(input), snapshot)
})

test('encodePathTail keeps slash segments and encodes spaces', () => {
  // 模型名是单一全局身份但可包含斜杠；路径 tail 必须保留层级分隔，只编码每段中的空格等保留字符。
  assert.equal(encodePathTail('vendor/My Model'), 'vendor/My%20Model')
})
