import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../dev/lib/repo-root.mjs'

const ROOT = repositoryRoot()
const workflow = (name) => readFileSync(path.join(ROOT, '.github/workflows', name), 'utf8')

function jobBlock(text, name) {
  const match = new RegExp(`^  ${name}:\\n([\\s\\S]*?)(?=^  [A-Za-z_][A-Za-z_0-9]*:|$(?![\\s\\S]))`, 'm').exec(text)
  assert.ok(match, `missing job: ${name}`)
  return match[1]
}

test('Go daemon CI owns the release script suite and is reusable', () => {
  const ci = workflow('daemon-go-ci.yml')
  assert.match(ci, /^  workflow_call:/m)
  const quality = jobBlock(ci, 'quality')
  assert.ok(quality.includes('actions/setup-node'))
  assert.ok(quality.includes('actions/setup-go'))
  assert.ok(quality.includes('Run repository script tests'))
  assert.ok(quality.includes('GO_BIN: go'))
  assert.ok(quality.includes('scripts/daemon/tests/*.test.mjs'))
  assert.ok(quality.includes('scripts/dev/verify/reliability/tests/daemon-entrypoint-contracts.test.mjs'))
  assert.ok(quality.includes('scripts/dev/verify/supply-chain/tests/supply-chain.test.mjs'))
})

test('release staging runs the script suite and host smoke includes Windows', () => {
  const release = workflow('daemon-release.yml')
  const stage = jobBlock(release, 'stage')
  assert.ok(stage.includes('Run repository script tests'))
  assert.ok(stage.includes('actions/setup-go'))
  assert.ok(stage.includes('GO_BIN: go'))
  const smoke = jobBlock(release, 'smoke_archives')
  assert.ok(smoke.includes('windows-latest'))
  assert.ok(smoke.includes('smoke-archive.ps1'))
})

test('Docker publishing waits for reusable Go Daemon CI before publishing', () => {
  const docker = workflow('docker-publish.yml')
  assert.ok(jobBlock(docker, 'daemon_ci').includes('./.github/workflows/daemon-go-ci.yml'))
  const publish = jobBlock(docker, 'publish')
  const needs = publish.split('needs:\n')[1].split('    if:')[0]
  for (const name of ['preflight', 'validate', 'validate_frontend', 'daemon_ci']) {
    assert.ok(needs.includes(`      - ${name}\n`), name)
    assert.ok(publish.includes(`needs.${name}.result == 'success'`), name)
  }
})
