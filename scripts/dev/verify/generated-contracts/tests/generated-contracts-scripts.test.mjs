/**
 * 独立 write/check 入口的契约回归：不得真正调用 Maven，只用 PATH 上的 stub mvn 记录实际命令。
 *
 * 这两个脚本是前端传输契约生成的开发者入口；它们的失败路径与参数同样属于 CI 门禁本身，因此必须回归。
 */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { accessSync, chmodSync, constants, existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../../lib/repo-root.mjs'

const REPOSITORY_ROOT = repositoryRoot()
const CAPABILITY_DIR = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/generated-contracts')
const CHECK_SCRIPT = path.join(CAPABILITY_DIR, 'check.sh')
const WRITE_SCRIPT = path.join(CAPABILITY_DIR, 'write.sh')
const ARTIFACT_TEST = '-Dtest=TsContractArtifactTest,DaemonContractArtifactTest'
const UPDATE_PROPERTY = '-Dkkstudio.contract.generation.update=true'

/** Run `body({ log, environment })` with a stub `mvn` that records JAVA_HOME and its arguments. */
function withStubMaven(body) {
  const stubDir = mkdtempSync(path.join(tmpdir(), 'kk-studio-contracts-'))
  try {
    const log = path.join(stubDir, 'mvn.log')
    const stub = path.join(stubDir, 'mvn')
    writeFileSync(
      stub,
      '#!/usr/bin/env bash\n{\n  printf "JAVA_HOME=%s\\n" "${JAVA_HOME:-}"\n  printf "ARGS=%s\\n" "$*"\n} > "${KK_STUDIO_GENERATED_CONTRACTS_LOG:?}"\n',
    )
    chmodSync(stub, statSync(stub).mode | 0o111)
    const environment = {
      ...process.env,
      PATH: `${stubDir}${path.delimiter}${process.env.PATH}`,
      JAVA_HOME_21: '/stub/jdk21',
      KK_STUDIO_GENERATED_CONTRACTS_LOG: log,
      KK_STUDIO_REPO_ROOT: REPOSITORY_ROOT,
    }
    delete environment.JAVA_HOME
    return body({ log, environment })
  } finally {
    rmSync(stubDir, { recursive: true, force: true })
  }
}

const run = (script, environment) => spawnSync(script, { env: environment, encoding: 'utf8' })

test('test intent: the entry scripts exist and are executable', () => {
  for (const script of [CHECK_SCRIPT, WRITE_SCRIPT]) {
    assert.ok(existsSync(script), `${script} must exist`)
    accessSync(script, constants.X_OK)
  }
})

test('test intent: check runs the targeted read-only reactor test', () => {
  withStubMaven(({ log, environment }) => {
    const result = run(CHECK_SCRIPT, environment)
    assert.equal(result.status, 0, result.stderr)
    const logged = readFileSync(log, 'utf8')
    assert.ok(logged.includes('JAVA_HOME=/stub/jdk21'))
    assert.ok(logged.includes('-pl web -am'))
    assert.ok(logged.includes(ARTIFACT_TEST))
    assert.ok(logged.trimEnd().endsWith('test'), logged)
    assert.ok(!logged.includes(UPDATE_PROPERTY), 'check must never rewrite the artifact')
  })
})

test('test intent: write enables the explicit update property', () => {
  withStubMaven(({ log, environment }) => {
    const result = run(WRITE_SCRIPT, environment)
    assert.equal(result.status, 0, result.stderr)
    const logged = readFileSync(log, 'utf8')
    assert.ok(logged.includes(ARTIFACT_TEST))
    assert.ok(logged.includes(UPDATE_PROPERTY))
  })
})

test('test intent: a missing JDK 21 fails before invoking Maven', () => {
  withStubMaven(({ log, environment }) => {
    const bare = { ...environment }
    delete bare.JAVA_HOME_21
    delete bare.JAVA_HOME
    for (const script of [CHECK_SCRIPT, WRITE_SCRIPT]) {
      const result = run(script, bare)
      assert.notEqual(result.status, 0, script)
      assert.ok(result.stderr.includes('JAVA_HOME_21'), script)
      assert.ok(!existsSync(log), 'maven must not run without a JDK 21')
    }
  })
})
