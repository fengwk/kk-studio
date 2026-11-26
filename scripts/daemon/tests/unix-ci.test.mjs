import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
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

function releaseStep(job, step) {
  const block = jobBlock(workflow('daemon-release.yml'), job).split(`      - name: ${step}\n`)[1]
  const body = block?.match(/^        run: \|\n((?:^ {10}[^\n]*\n|^\n)+)/m)?.[1]
  assert.ok(body, `missing shell body: ${step}`)
  return body.replace(/^ {10}/gm, '')
}

test('release tag gate accepts the installer version alphabet and length limit', () => {
  const script = releaseStep('stage', 'Require a safe release tag on an approved commit')
  const tag = `v1.2.3+${'a'.repeat(58)}`
  // Isolate tag validation from the separate Git ancestry gate.
  const run = (value) => spawnSync('bash', ['-c', `git() { echo fixture; }\n${script}`], {
    encoding: 'utf8',
    env: { ...process.env, RELEASE_TAG: value, GITHUB_EVENT_NAME: 'push', GITHUB_OUTPUT: '/dev/null' },
  })
  const accepted = run(tag)
  assert.equal(accepted.status, 0, accepted.stderr)
  for (const invalid of [`${tag}a`, 'v1/2', 'v']) {
    const rejected = run(invalid)
    assert.equal(rejected.status, 1)
    assert.match(rejected.stderr, /unsupported release tag/)
  }
})

test('Unix installer help distinguishes the Linux journal from macOS log files', () => {
  const result = spawnSync('bash', [path.join(ROOT, 'scripts/daemon/install.sh'), 'help'], { encoding: 'utf8' })
  assert.equal(result.status, 0, result.stderr)
  assert.match(result.stdout, /Service logs on Linux: journalctl --user -u kk-studio-daemon-<environment-id>\.service/)
  assert.match(result.stdout, /Service logs on macOS: daemon\.stdout\.log and daemon\.stderr\.log in the environment directory/)
})

test('release reruns verify GitHub digests before making any writes', { skip: process.platform !== 'linux' }, (t) => {
  const script = releaseStep('publish', 'Publish GitHub Release')
  const stage = mkdtempSync(path.join(tmpdir(), 'kk-release-publish-'))
  t.after(() => rmSync(stage, { recursive: true, force: true }))
  const platform = 'linux-amd64'
  const tag = 'v1.3.1+test'
  const stem = `kk-studio-daemon-${tag}-${platform}`
  const names = ['LICENSE', 'THIRD_PARTY_NOTICES', `${stem}.tar.gz`, `${stem}.tar.gz.sha256`, `${stem}.json`]
  mkdirSync(path.join(stage, platform))
  const assets = names.map((name) => {
    writeFileSync(path.join(stage, platform, name), name)
    return { name, digest: `sha256:${createHash('sha256').update(name).digest('hex')}` }
  })
  const [first, ...rest] = assets
  // Stub only gh; execute the YAML shell with real files, jq, and sha256sum. No remote call is possible.
  const gh = `exec 3>&1
  gh() {
    if [ "$*" = "release view $RELEASE_TAG --json isDraft,assets" ]; then
      echo view >&3
      printf '%s\\n' "$FIXTURE_RELEASE"
    else
      printf '%s\\n' "$*" >&3
    fi
  }
  `
  const run = (isDraft, remoteAssets) => spawnSync('bash', ['-c', `${gh}${script}`], {
    encoding: 'utf8',
    env: {
      ...process.env, RELEASE_TAG: tag, RELEASE_ROOT: stage, RELEASE_PLATFORMS: platform,
      FIXTURE_RELEASE: JSON.stringify({ isDraft, assets: remoteAssets }),
    },
  })
  const published = run(false, assets)
  assert.equal(published.status, 0, published.stderr)
  assert.equal(published.stdout, 'view\npublished release already has the staged assets\n')

  const draft = run(true, rest)
  assert.equal(draft.status, 0, draft.stderr)
  assert.equal(draft.stdout, `view\nrelease upload ${tag} ${path.join(stage, platform, first.name)}\nrelease edit ${tag} --draft=false\n`)

  for (const [isDraft, remoteAssets, diagnostic] of [
    [false, rest, /missing asset LICENSE/],
    [true, [...rest, { ...first, name: 'extra' }], /outside the staged set/],
    [false, [{ name: first.name }, ...rest], /missing or mismatched digest/],
    [true, [{ ...rest[0], digest: first.digest }, ...rest.slice(1)], /missing or mismatched digest/],
    [false, [...assets, first], /duplicated/],
  ]) {
    const rejected = run(isDraft, remoteAssets)
    assert.equal(rejected.status, 1, rejected.stderr)
    assert.match(rejected.stderr, diagnostic)
    assert.equal(rejected.stdout, 'view\n')
  }
})

test('daemon CI owns the release script suite and is reusable', () => {
  const ci = workflow('daemon-ci.yml')
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

test('Docker publishing waits for reusable Daemon CI before publishing', () => {
  const docker = workflow('docker-publish.yml')
  assert.ok(jobBlock(docker, 'daemon_ci').includes('./.github/workflows/daemon-ci.yml'))
  const publish = jobBlock(docker, 'publish')
  const needs = publish.split('needs:\n')[1].split('    if:')[0]
  for (const name of ['preflight', 'validate', 'validate_frontend', 'daemon_ci']) {
    assert.ok(needs.includes(`      - ${name}\n`), name)
    assert.ok(publish.includes(`needs.${name}.result == 'success'`), name)
  }
})
