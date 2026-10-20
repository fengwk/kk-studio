/** The E2E shell helpers read API responses through json-probe.mjs. */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import test from 'node:test'

import { REPOSITORY_ROOT } from '../../../tests/support/shell.mjs'

const PROBE = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/e2e/lib/json-probe.mjs')
const probe = (args, document) =>
  spawnSync(process.execPath, [PROBE, ...args], { input: JSON.stringify(document), encoding: 'utf8' })
const models = (first) => ({ data: { results: first === undefined ? [] : [first] } })

test('test intent: model-config-print requires a structured config with a default variant', () => {
  const ok = probe(['model-config-print'], models({ config: { defaultVariant: 'default' } }))
  assert.equal(ok.status, 0)
  assert.equal(ok.stdout, 'frontend proxy model.config.defaultVariant= default\n')
  for (const bad of [undefined, {}, { config: 'x' }, { config: {} }]) {
    assert.notEqual(probe(['model-config-print'], models(bad)).status, 0, JSON.stringify(bad))
  }
})

test('test intent: model-config-check succeeds only for a structured config object', () => {
  assert.equal(probe(['model-config-check'], models({ config: {} })).status, 0)
  assert.equal(probe(['model-config-check'], models({ config: [] })).status, 1)
  assert.equal(probe(['model-config-check'], models(undefined)).status, 1)
})

test('test intent: environment-status prints the named status or an empty line', () => {
  const rows = { data: [{ name: 'a', status: 'READY' }, { name: 'b', status: 'OFFLINE' }] }
  assert.equal(probe(['environment-status', 'b'], rows).stdout, 'OFFLINE\n')
  assert.equal(probe(['environment-status', 'zzz'], rows).stdout, '\n')
})

test('test intent: providers-ready lists the missing target providers in sorted order', () => {
  const configured = (...names) => ({ data: { results: names.map((name) => ({ name, configured: true, baseUrl: 'u' })) } })
  assert.equal(probe(['providers-ready'], configured('google', 'openai', 'minimax-anthropic', 'deepseek')).stdout, 'ok\n')
  assert.equal(probe(['providers-ready'], configured('openai')).stdout, 'missing:deepseek,google,minimax-anthropic\n')
})
