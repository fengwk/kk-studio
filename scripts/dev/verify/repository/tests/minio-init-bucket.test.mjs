/** Unit tests for the shared MinIO bucket bootstrap script shipped in the mc image. */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../../lib/repo-root.mjs'

const CLIENT = 'deploy/dependencies/minio-client'
const SCRIPT = path.join(repositoryRoot(), CLIENT, 'init-bucket.sh')
const USER = 'init-user'
const PASSWORD = 'init-password-value'
const BUCKET = 'init-bucket'

/**
 * 沙箱内放一个假 mc：按调用顺序把 argv 记进日志，并在伪失败步骤上返回 1。
 * 这样可以在没有真实 MinIO 的情况下断言命令顺序、失败即停与脚本自身的输出。
 */
function createSandbox() {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-studio-mc-init-'))
  const log = path.join(directory, 'calls.log')
  writeFileSync(log, '')
  writeFileSync(
    path.join(directory, 'mc'),
    [
      '#!/bin/sh',
      'printf "%s\\n" "$*" >> "$FAKE_MC_CALL_LOG"',
      'if [ "${FAKE_MC_FAIL_ON:-}" = "$1" ]; then',
      '  echo "fake mc: refusing $1" >&2',
      '  exit 1',
      'fi',
      'if [ "$1" = ls ] && [ "${FAKE_BUCKET_EXISTS:-yes}" = no ]; then exit 1; fi',
      'if [ "$1" = alias ]; then echo "alias output"; fi',
      'exit 0',
      '',
    ].join('\n'),
    { mode: 0o755 },
  )
  return { directory, log }
}

/** 用给定环境执行初始化脚本，返回退出码、合并输出与本次实际发生的 mc 调用。 */
function runScript(sandbox, { env = {}, unset = [], args = [] } = {}) {
  writeFileSync(sandbox.log, '')
  const childEnv = {
    ...process.env,
  }
  for (const name of ['MINIO_ALIAS', 'MINIO_ENDPOINT', 'FAKE_MC_FAIL_ON', 'FAKE_BUCKET_EXISTS']) {
    delete childEnv[name]
  }
  Object.assign(childEnv, {
    PATH: `${sandbox.directory}:${process.env.PATH}`,
    FAKE_MC_CALL_LOG: sandbox.log,
    MINIO_ROOT_USER: USER,
    MINIO_ROOT_PASSWORD: PASSWORD,
    MINIO_BUCKET: BUCKET,
    ...env,
  })
  for (const name of unset) {
    delete childEnv[name]
  }
  const result = spawnSync('/bin/sh', [SCRIPT, ...args], { env: childEnv, encoding: 'utf8' })
  return {
    status: result.status,
    output: `${result.stdout}${result.stderr}`,
    calls: readFileSync(sandbox.log, 'utf8').split('\n').filter((line) => line !== ''),
  }
}

test('the bootstrap runs alias set, mb, anonymous none and stat in that exact order', () => {
  const sandbox = createSandbox()
  try {
    const run = runScript(sandbox)
    assert.equal(run.status, 0)
    assert.equal(run.output, '')
    assert.deepEqual(run.calls, [
      `alias set local http://minio:9000 ${USER} ${PASSWORD}`,
      `mb --ignore-existing local/${BUCKET}`,
      `anonymous set none local/${BUCKET}`,
      `stat local/${BUCKET}`,
    ])
  } finally {
    rmSync(sandbox.directory, { recursive: true, force: true })
  }
})

test('clean mode empties an existing bucket and ensures a private bucket in either case', () => {
  // The fake mc models both outcomes of the existence probe without touching storage.
  const sandbox = createSandbox()
  try {
    for (const bucketExists of [true, false]) {
      const run = runScript(sandbox, {
        args: ['--clean-existing'],
        env: { FAKE_BUCKET_EXISTS: bucketExists ? 'yes' : 'no' },
      })
      assert.equal(run.status, 0)
      assert.equal(run.output, '')
      assert.deepEqual(run.calls, [
        `alias set local http://minio:9000 ${USER} ${PASSWORD}`,
        `ls local/${BUCKET}`,
        ...(bucketExists ? [`rm --recursive --force local/${BUCKET}/`] : []),
        `mb --ignore-existing local/${BUCKET}`,
        `anonymous set none local/${BUCKET}`,
        `stat local/${BUCKET}`,
      ])
    }
  } finally {
    rmSync(sandbox.directory, { recursive: true, force: true })
  }
})

test('explicit alias and endpoint let host-side callers reuse the bootstrap', () => {
  const sandbox = createSandbox()
  try {
    const run = runScript(sandbox, {
      env: { MINIO_ALIAS: 'e2e-local', MINIO_ENDPOINT: 'http://127.0.0.1:9000' },
    })
    assert.equal(run.status, 0)
    assert.deepEqual(run.calls, [
      `alias set e2e-local http://127.0.0.1:9000 ${USER} ${PASSWORD}`,
      `mb --ignore-existing e2e-local/${BUCKET}`,
      `anonymous set none e2e-local/${BUCKET}`,
      `stat e2e-local/${BUCKET}`,
    ])
  } finally {
    rmSync(sandbox.directory, { recursive: true, force: true })
  }
})

test('the first failing mc step aborts the bootstrap and runs no later step', () => {
  // Failure of any mutating or verification step must prevent all following steps.
  const modes = [
    { args: [], steps: [['alias', 1], ['mb', 2], ['anonymous', 3], ['stat', 4]] },
    { args: ['--clean-existing'], steps: [['alias', 1], ['rm', 3], ['mb', 4], ['anonymous', 5], ['stat', 6]] },
  ]
  const sandbox = createSandbox()
  try {
    for (const { args, steps } of modes) {
      for (const [step, expectedCalls] of steps) {
        const run = runScript(sandbox, { args, env: { FAKE_MC_FAIL_ON: step } })
        assert.notEqual(run.status, 0, step)
        assert.equal(run.calls.length, expectedCalls, step)
        assert.equal(run.calls.at(-1).split(' ')[0], step)
      }
    }
  } finally {
    rmSync(sandbox.directory, { recursive: true, force: true })
  }
})

test('the bootstrap never echoes the root password on success or failure', () => {
  const sandbox = createSandbox()
  try {
    for (const options of [{}, { env: { FAKE_MC_FAIL_ON: 'mb' } }, { env: { FAKE_MC_FAIL_ON: 'stat' } }]) {
      const run = runScript(sandbox, options)
      assert.ok(run.calls.length > 0)
      assert.ok(!run.output.includes(PASSWORD))
    }
  } finally {
    rmSync(sandbox.directory, { recursive: true, force: true })
  }
})

test('a missing required variable aborts before the first mc call', () => {
  const sandbox = createSandbox()
  try {
    for (const name of ['MINIO_ROOT_USER', 'MINIO_ROOT_PASSWORD', 'MINIO_BUCKET']) {
      const run = runScript(sandbox, { unset: [name] })
      assert.notEqual(run.status, 0, name)
      assert.deepEqual(run.calls, [], name)
      assert.ok(run.output.includes(name), name)
      assert.ok(!run.output.includes(PASSWORD), name)
    }
  } finally {
    rmSync(sandbox.directory, { recursive: true, force: true })
  }
})
