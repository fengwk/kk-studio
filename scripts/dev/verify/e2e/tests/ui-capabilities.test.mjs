import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'

import { missingUiCapabilities } from '../lib/ui-capabilities.mjs'
import { REPO_ROOT } from '../../../lib/repo-root.mjs'

test('capability gate skips cases whose flag is absent and allows them when present', () => {
  // 测试意图：锁定 UI 矩阵「旗标 → 能力 → case 选择」契约，避免新增能力时漏判而静默跑错集合。
  assert.deepEqual(missingUiCapabilities({}, {}), [])
  assert.deepEqual(missingUiCapabilities({ requiresTools: true }, { withTools: true }), [])
  assert.deepEqual(missingUiCapabilities({ requiresTools: true }, {}), ['--with-tools'])
  assert.deepEqual(
    missingUiCapabilities({ requiresCanvasFunction: true }, { withCanvasFunction: true }),
    [],
  )
  assert.deepEqual(
    missingUiCapabilities({ requiresCanvasFunction: true }, {}),
    ['--with-canvas-function'],
  )
})

test('capability gate reports every missing flag for a case that needs multiple capabilities', () => {
  // 测试意图：多点能力缺失时 --only 报错必须完整列出，不能只提示其中一个而误导排障。
  assert.deepEqual(
    missingUiCapabilities({ requiresTools: true, requiresCanvasFunction: true }, {}),
    ['--with-tools', '--with-canvas-function'],
  )
})

test('ui-smoke parses the canvas flag and gates the canvas case on it', () => {
  // 测试意图：新增旗标必须同时进入 parseArgs 与 case 声明，否则能力既无法开启也无法生效。
  const source = readFileSync(path.join(REPO_ROOT, 'scripts/dev/verify/e2e/ui-smoke.mjs'), 'utf8')
  assert.match(source, /a === '--with-canvas-function'/)
  assert.match(source, /withCanvasFunction: false/)
  assert.match(source, /'ui\.canvas\.function_fake_flow'/)
  assert.match(source, /\{ requiresCanvasFunction: true \}/)
})

test('run.sh forwards --with-canvas-function into the UI matrix', () => {
  // 测试意图：--ui 与 --with-canvas-function 组合时 runner 必须把能力透传给 ui-smoke，而不是只影响 API 矩阵。
  const source = readFileSync(path.join(REPO_ROOT, 'scripts/dev/verify/e2e/run.sh'), 'utf8')
  assert.match(
    source,
    /if \[ "\$WITH_CANVAS_FUNCTION" = "true" \]; then\n\s+UI_ARGS\+=\(--with-canvas-function\)\n\s+fi/,
  )
})
