import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { ALL_CASES, caseEnabled, getCase, parseArgs as parseApiArgs } from '../run-matrix.mjs'
import { parseArgs as parseUiArgs } from '../ui-smoke.mjs'
import { REPO_ROOT } from '../../../lib/repo-root.mjs'
import {
  REAL_MODEL_DEFINITIONS,
  MINIMAX_ANTHROPIC_M3,
} from '../lib/real-models.mjs'

test('real case 模块显式导出 case 数组', async () => {
  // 矩阵行展开后仍可独立导入，不依赖全局注册顺序。
  const { cases } = await import('../cases/real.mjs')
  assert.ok(cases.length > 0)
  assert.ok(cases.every((entry) => entry.requires instanceof Set))
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
      imageInput: true,
    },
    {
      idSuffix: 'openai_responses',
      title: 'OpenAI Responses',
      providerName: 'openai',
      modelName: 'gpt-6-luna',
      variant: 'off',
      variants: ['off', 'low', 'medium', 'high', 'xhigh', 'max'],
      providerType: 'openai_responses',
      imageInput: true,
    },
    {
      idSuffix: 'minimax_anthropic',
      title: 'MiniMax Anthropic',
      providerName: 'minimax-anthropic',
      modelName: 'MiniMax-M3',
      variant: 'off',
      variants: ['off', 'minimal', 'low', 'medium', 'high'],
      providerType: 'anthropic',
      imageInput: true,
    },
    {
      idSuffix: 'deepseek_chat',
      title: 'DeepSeek Chat',
      providerName: 'deepseek',
      modelName: 'deepseek-v4.1-flash',
      variant: 'off',
      variants: ['off', 'low', 'high', 'max'],
      providerType: 'openai',
    },
  ]

  assert.deepEqual(REAL_MODEL_DEFINITIONS, expected)
  assert.equal(MINIMAX_ANTHROPIC_M3, REAL_MODEL_DEFINITIONS[2])
})

test('真实图片用例仅注册给 seed 声明 IMAGE 输入的模型', () => {
  // 用 seed 目录对照矩阵，防止图片能力与付费、存储门禁漂移。
  const catalog = JSON.parse(readFileSync(path.join(REPO_ROOT,
    'platform/runtime/src/test/resources/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/pi-model-catalog.json'), 'utf8'))
  const expectedIds = REAL_MODEL_DEFINITIONS.filter((def) => {
    const model = catalog.find((entry) => entry.provider === def.providerName && entry.name === def.modelName)
    assert.ok(model, `missing seed model: ${def.modelName}`)
    return model.config.abilities.inputModalities.includes('IMAGE')
  }).map((def) => `real.image_input.${def.idSuffix}`)
  const imageCases = ALL_CASES.filter((entry) => entry.id.startsWith('real.image_input.'))
  assert.deepEqual(imageCases.map((entry) => entry.id), expectedIds)
  for (const entry of imageCases) {
    assert.equal(entry.level, 'L2')
    assert.deepEqual([...entry.requires].sort(), ['canvas-storage', 'real'])
  }
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

  // 3. 预检、Agent 配置和 selector 都从已解析的 UI 模型派生。
  assert.match(content, /requireRealUiModel\(args\.backendUrl, args\.realModel\)/)
  assert.match(content, /settings: \{ modelName: args\.realModel\.modelName, variant: args\.realModel\.variant \}/)
  assert.match(content, /hasText: `\$\{args\.realModel\.modelName\} \(\$\{args\.realModel\.variant\}\)`/)
})

const sharedRealIds = ['real.stop_partial_continue', 'branch.same_session_new_thread', 'real.task_delegation', 'tool.read_turn']

function expectedModelIds(def) {
  const kinds = ['text_cache', 'reasoning_levels', ...(def.imageInput ? ['image_input'] : [])]
  return kinds.map((kind) => `real.${kind}.${def.idSuffix}`)
}

test('real selection narrows API inventory and uses the same model in UI, preserving opt-in capability gates', () => {
  const select = (...flags) => {
    const args = parseApiArgs(flags)
    return ALL_CASES.filter((c) => caseEnabled(c, args) && c.requires.has('real')).map((c) => c.id).sort()
  }
  const optIns = ['--with-branch', '--with-tools', '--with-canvas-storage']
  for (const def of REAL_MODEL_DEFINITIONS) {
    const flags = ['--real-model', def.idSuffix]
    assert.equal(parseUiArgs(flags).realModel, parseApiArgs(flags).realModel)
    assert.deepEqual(select(...flags, ...optIns), [...expectedModelIds(def), ...sharedRealIds].sort())
    assert.deepEqual(select(...flags), [`real.reasoning_levels.${def.idSuffix}`, 'real.stop_partial_continue', `real.text_cache.${def.idSuffix}`])
  }
  assert.deepEqual(select('--with-tools', '--with-canvas-storage'), [])
  assert.equal(parseUiArgs([]).real, false)
  assert.equal(parseUiArgs(['--real']).realModel, MINIMAX_ANTHROPIC_M3)
  assert.deepEqual(select('--real', ...optIns), [...REAL_MODEL_DEFINITIONS.flatMap(expectedModelIds), ...sharedRealIds].sort())
})

test('shared branch, stop and task use the UI-selected model when creating their first Agent', async () => {
  const selected = parseUiArgs(['--real-model', 'openai_responses']).realModel
  const { realModel } = parseApiArgs(['--real-model', selected.idSuffix])
  const stopBeforeExecution = new Error('fixture stops at agent creation')
  // Only return the selected catalog rows; intercept the first write before any model turn.
  for (const id of sharedRealIds.filter((id) => id !== 'tool.read_turn')) {
    await assert.rejects(getCase(id).run({
      realModel,
      async call(method, route, body) {
        if (method === 'GET' && route === '/api/platform/providers') {
          return { json: [{ name: selected.providerName, providerType: selected.providerType, baseUrl: 'http://fixture.invalid', credential: { configured: true } }] }
        }
        if (method === 'GET' && route === '/api/platform/models') {
          return { json: [{ name: selected.modelName, config: { variants: selected.variants.map((id) => ({ id })) } }] }
        }
        assert.equal(method, 'POST')
        assert.equal(route, '/api/platform/agents')
        assert.deepEqual(body.execution.settings, { modelName: selected.modelName, variant: selected.variant })
        throw stopBeforeExecution
      },
    }), (error) => error === stopBeforeExecution, id)
  }
})

test('generated docs describe model selection and preserve shared defaults', () => {
  for (const id of sharedRealIds) {
    const { docs } = getCase(id)
    assert.match(docs, /--real-model/)
    assert.match(docs, /未指定时/)
    if (id === 'tool.read_turn' || id === 'real.task_delegation') assert.match(docs, /E2E_BUILTIN_MODEL/)
  }
})
