import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { ALL_CASES } from '../lib/registry.mjs'
import { REPO_ROOT } from '../../../lib/repo-root.mjs'
import {
  REAL_MODEL_DEFINITIONS,
  MINIMAX_ANTHROPIC_M3,
} from '../lib/real-models.mjs'

test('lib/real-models 模块无副作用导出，且 cases/real 正确兼容重导出', async () => {
  // 测试意图：确保独立导入 lib/real-models.mjs 时具备零副作用（不触发 case 注册），同时 cases/real.mjs 保持向后兼容的透明重导出。
  assert.equal(
    ALL_CASES.length,
    0,
    'importing lib/real-models.mjs must have zero top-level side effects (no case registration)',
  )

  const casesReal = await import('../cases/real.mjs')
  assert.equal(casesReal.REAL_MODEL_DEFINITIONS, REAL_MODEL_DEFINITIONS)
  assert.equal(casesReal.MINIMAX_ANTHROPIC_M3, MINIMAX_ANTHROPIC_M3)
  assert.ok(ALL_CASES.length > 0, 'importing cases/real.mjs registers real cases')
})

test('四指定模型声明式定义与 MINIMAX_ANTHROPIC_M3 契约值严格对齐', () => {
  // 测试意图：验证单一真相源导出的四个真实模型及 MiniMax M3 契约字段完整、命名一致，与公共 seed/credential 契约完全吻合。
  assert.equal(REAL_MODEL_DEFINITIONS.length, 4)

  const expected = [
    {
      idSuffix: 'google_gemini',
      title: 'Google Gemini',
      providerName: 'google',
      modelName: 'gemini-3.8-flash',
      variant: 'minimal',
      variants: ['minimal', 'low', 'medium', 'high'],
      providerType: 'google',
    },
    {
      idSuffix: 'openai_responses',
      title: 'OpenAI Responses',
      providerName: 'openai',
      modelName: 'gpt-5.6-luna',
      variant: 'off',
      variants: ['off', 'low', 'medium', 'high', 'xhigh', 'max'],
      providerType: 'openai_response',
    },
    {
      idSuffix: 'minimax_anthropic',
      title: 'MiniMax Anthropic',
      providerName: 'minimax-anthropic',
      modelName: 'MiniMax-M3',
      variant: 'off',
      variants: ['off', 'minimal', 'low', 'medium', 'high'],
      providerType: 'anthropic',
    },
    {
      idSuffix: 'deepseek_chat',
      title: 'DeepSeek Chat',
      providerName: 'deepseek',
      modelName: 'deepseek-v4-flash',
      variant: 'off',
      variants: ['off', 'low', 'high', 'max'],
      providerType: 'openai',
    },
  ]

  assert.deepEqual(REAL_MODEL_DEFINITIONS, expected)
  assert.equal(MINIMAX_ANTHROPIC_M3, REAL_MODEL_DEFINITIONS[2])
})

test('ui-smoke 必须引用共享契约且杜绝硬编码裸露的 MiniMax 供应商与模型字面量', () => {
  // 测试意图：锁定 UI smoke 与四模型契约的依赖关系，防止 ui-smoke.mjs 再次硬编码 minimax-anthropic / MiniMax-M3 字符串字面量造成契约漂移。
  const uiSmokePath = path.join(REPO_ROOT, 'scripts/dev/verify/e2e/ui-smoke.mjs')
  const content = readFileSync(uiSmokePath, 'utf8')

  // 1. 必须从 lib/real-models.mjs 导入 MINIMAX_ANTHROPIC_M3
  assert.match(
    content,
    /import\s*\{[^}]*MINIMAX_ANTHROPIC_M3[^}]*\}\s*from\s*['"]\.\/lib\/real-models\.mjs['"]/,
    'ui-smoke.mjs must import MINIMAX_ANTHROPIC_M3 from ./lib/real-models.mjs',
  )

  // 2. 源码中所有字符串字面量与模板字面量片段均不得包含裸露的 minimax-anthropic 或 MiniMax-M3
  const stringLiteralRegex = /(['"`])(?:(?!\1)[^\\]|\\.)*\1/g
  const stringLiterals = content.match(stringLiteralRegex) || []
  assert.ok(stringLiterals.length > 0, 'must find string literals in ui-smoke.mjs')

  for (const literal of stringLiterals) {
    assert.equal(
      literal.includes('minimax-anthropic'),
      false,
      `ui-smoke.mjs must not contain bare 'minimax-anthropic' literal: ${literal}`,
    )
    assert.equal(
      literal.includes('MiniMax-M3'),
      false,
      `ui-smoke.mjs must not contain bare 'MiniMax-M3' literal: ${literal}`,
    )
  }

  // 3. 校验关键派生用法存在
  assert.match(
    content,
    /const\s+REAL_UI_MODEL_ID\s*=\s*`\$\{REAL_UI_MODEL\.providerName\}\/\$\{REAL_UI_MODEL\.modelName\}`/,
    'ui-smoke.mjs must derive REAL_UI_MODEL_ID from providerName and modelName',
  )
  assert.match(
    content,
    /const\s+REAL_UI_MODEL_SELECTOR_LABEL\s*=\s*`\$\{REAL_UI_MODEL_ID\} · \$\{REAL_UI_MODEL\.variant\}`/,
    'ui-smoke.mjs must derive selector label with variant',
  )
})
