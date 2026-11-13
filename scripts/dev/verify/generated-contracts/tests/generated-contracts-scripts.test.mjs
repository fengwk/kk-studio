/**
 * 独立 write/check 入口的契约回归：不得真正调用 Maven 或 protoc，只用 PATH 上的 stub 记录实际命令。
 * Go 生成物的真实一致性由 daemon-go/scripts/check-generated.sh 检查，golden round-trip 由 Go 测试覆盖。
 *
 * 这两个脚本是前端 HTTP 契约与 Go 协议生成的开发者入口；其失败路径与参数属于 CI 门禁本身。
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
const ARTIFACT_TEST = '-Dtest=TsContractArtifactTest'
const UPDATE_PROPERTY = '-Dkkstudio.contract.generation.update=true'

/** Write an executable stub script into `dir`. */
function writeStub(dir, name, body) {
  const stub = path.join(dir, name)
  writeFileSync(stub, body)
  chmodSync(stub, statSync(stub).mode | 0o111)
}

/**
 * Run `body({ log, environment })` with stub `mvn` and `protoc` executables that record their arguments.
 * HOME points at the stub directory so no Maven-resolved protoc is picked up.
 */
function withStubMaven(body) {
  const stubDir = mkdtempSync(path.join(tmpdir(), 'kk-studio-contracts-'))
  try {
    const log = path.join(stubDir, 'mvn.log')
    writeStub(
      stubDir,
      'mvn',
      '#!/usr/bin/env bash\n{\n  printf "MAVEN JAVA_HOME=%s\\n" "${JAVA_HOME:-}"\n  printf "MAVEN ARGS=%s\\n" "$*"\n} >> "${KK_STUDIO_GENERATED_CONTRACTS_LOG:?}"\n',
    )
    writeStub(
      stubDir,
      'protoc',
      '#!/usr/bin/env bash\nprintf "PROTOC ARGS=%s\\n" "$*" >> "${KK_STUDIO_GENERATED_CONTRACTS_LOG:?}"\n',
    )
    const environment = {
      ...process.env,
      PATH: `${stubDir}${path.delimiter}${process.env.PATH}`,
      HOME: stubDir,
      JAVA_HOME_21: '/stub/jdk21',
      KK_STUDIO_GENERATED_CONTRACTS_LOG: log,
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
    assert.ok(logged.includes('MAVEN JAVA_HOME=/stub/jdk21'))
    assert.ok(logged.includes('-pl web -am'))
    assert.ok(logged.includes(ARTIFACT_TEST))
    assert.ok(!logged.includes('control_pb.ts'))
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
    assert.ok(!logged.includes('control_pb.ts'))
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
