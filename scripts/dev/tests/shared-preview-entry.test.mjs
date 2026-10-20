/** scripts/dev/shared-preview.sh is the one-command shared preview over the packaged Backend. */

import assert from 'node:assert/strict'
import { mkdirSync, rmSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'

import { readText } from './support/shell.mjs'
import {
  PREVIEW_MARKER,
  SHARED_PREVIEW_SCRIPT,
  VALUE_PREFIX,
  placeholderConfig,
  runSharedPreview,
  withTempDir,
  writeConfig,
} from './support/shared-preview.mjs'

const output = (result) => result.stdout + result.stderr

test('test intent: the shared preview forces the prod profile without Flyway or Harness workers', () => {
  // The shared preview must never own migrations or distributed Harness work.
  const entryPoint = readText(SHARED_PREVIEW_SCRIPT)
  for (const forced of [
    'export SPRING_PROFILES_ACTIVE=prod',
    'export SPRING_FLYWAY_ENABLED=false',
    'export KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false',
    `export ${PREVIEW_MARKER}=true`,
  ]) {
    assert.ok(entryPoint.includes(forced), forced)
  }
  assert.ok(entryPoint.includes('exec "$SCRIPT_HOME/app.sh" "$@"'))
})

test('test intent: the owner-only home config is used unless SHARED_PREVIEW_ENV_FILE overrides it', () => {
  withTempDir((temporary) => {
    const home = path.join(temporary, 'home')
    const configDir = path.join(home, '.config', 'kk-studio')
    mkdirSync(configDir, { recursive: true })
    writeConfig(configDir, placeholderConfig())

    let result = runSharedPreview(['status'], { HOME: home })
    assert.equal(result.status, 0, output(result))
    assert.ok(result.stdout.includes('backend: stopped'))
    // 入口不设置监听地址：Backend/Vite 仍由 scripts/dev/app.sh 默认绑定 loopback。
    assert.ok(result.stdout.includes('url=http://127.0.0.1:18080'))
    assert.ok(result.stdout.includes('url=http://127.0.0.1:5173'))

    rmSync(path.join(configDir, 'shared-preview.env'))
    const overrideDir = path.join(temporary, 'elsewhere')
    mkdirSync(overrideDir)
    const override = writeConfig(overrideDir, placeholderConfig())
    result = runSharedPreview(['status'], { HOME: home, SHARED_PREVIEW_ENV_FILE: override })
    assert.equal(result.status, 0, output(result))
  })
})

test('test intent: conflicting caller switches are replaced but listen overrides are kept', () => {
  withTempDir((temporary) => {
    const config = writeConfig(temporary, placeholderConfig())
    const result = runSharedPreview(['status'], {
      SHARED_PREVIEW_ENV_FILE: config,
      SPRING_PROFILES_ACTIVE: 'e2e',
      SPRING_FLYWAY_ENABLED: 'true',
      KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED: 'true',
      BACKEND_PORT: '19080',
      FRONTEND_PORT: '5199',
    })
    assert.equal(result.status, 0, output(result))
    assert.ok(result.stdout.includes('url=http://127.0.0.1:19080'))
    assert.ok(result.stdout.includes('url=http://127.0.0.1:5199'))
  })
})

test('test intent: a missing config stops the preview instead of starting prod without data', () => {
  withTempDir((temporary) => {
    const result = runSharedPreview(['status'], { HOME: temporary })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('SHARED_PREVIEW_ENV_FILE'))
  })
})

test('test intent: group or other readable config fails closed and is never printed', () => {
  withTempDir((temporary) => {
    const config = writeConfig(temporary, placeholderConfig(), { mode: 0o644 })
    const result = runSharedPreview(['status'], { SHARED_PREVIEW_ENV_FILE: config })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('group or other permissions'))
    assert.ok(!output(result).includes(VALUE_PREFIX))
  })
})

test('test intent: --help documents the forwarded lifecycle and no arguments fails', () => {
  const help = runSharedPreview(['--help'])
  assert.equal(help.status, 0, output(help))
  assert.ok(help.stdout.includes('start|stop|restart|status|logs|tail'))
  assert.ok(help.stdout.includes('SHARED_PREVIEW_ENV_FILE'))

  const empty = runSharedPreview([])
  assert.notEqual(empty.status, 0)
  assert.ok(output(empty).includes('Usage:'))
})
