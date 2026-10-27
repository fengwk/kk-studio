/** The release workflow gate that keeps the POM, daemon package, runtime constant and optional tag on one version. */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import path from 'node:path'

import { repositoryRoot } from '../../../lib/repo-root.mjs'
import { checkAlignment } from '../check-version-alignment.mjs'

const POM = (version, property = version) => `<project>
  <parent><groupId>g</groupId><artifactId>p</artifactId><version>9.9.9</version></parent>
  <version>${version}</version>
  <properties><kk-studio.version>${property}</kk-studio.version></properties>
  <dependencies><dependency><version>0.0.1</version></dependency></dependencies>
</project>`
const PACKAGE = (version) => JSON.stringify({ name: 'daemon', version })
const CONFIG = (version) => `/** 构建版本。 */\nexport const DAEMON_VERSION = '${version}'\n`
const inputs = (version, { property = version, packageVersion = version, runtimeVersion = version } = {}) => ({
  pomXml: POM(version, property),
  packageJson: PACKAGE(packageVersion),
  configTs: CONFIG(runtimeVersion),
})

test('test intent: aligned internal sources pass without a tag and the tag may carry a v prefix', () => {
  const result = checkAlignment(inputs('1.2.3'))
  assert.deepEqual(result, { expected: '1.2.3', mismatches: [] })
  for (const tag of ['v1.2.3', '1.2.3']) {
    assert.deepEqual(checkAlignment({ ...inputs('1.2.3'), tag }), { expected: '1.2.3', mismatches: [] })
  }
})

test('test intent: a runtime constant left at the previous version fails the gate', () => {
  // 旧门禁只比较 POM 与 package，遗漏 config.ts 会让 4.0.1 发布出 4.0.0 的 daemon。
  const result = checkAlignment(inputs('1.2.3', { runtimeVersion: '1.2.2' }))
  assert.deepEqual(result.mismatches, [
    'daemon/src/config.ts DAEMON_VERSION is "1.2.2", expected "1.2.3"',
  ])
})

test('test intent: every diverging source is reported against the root version', () => {
  const result = checkAlignment({
    ...inputs('1.2.3', { property: '1.2.4', packageVersion: '1.2.2', runtimeVersion: '1.2.1' }),
    tag: 'v1.2.5',
  })
  assert.equal(result.mismatches.length, 4)
  assert.ok(result.mismatches.some((line) => line.startsWith('pom.xml kk-studio.version is "1.2.4"')))
  assert.ok(result.mismatches.some((line) => line.startsWith('daemon/package.json version is "1.2.2"')))
  assert.ok(result.mismatches.some((line) => line.startsWith('daemon/src/config.ts DAEMON_VERSION is "1.2.1"')))
  assert.ok(result.mismatches.some((line) => line.startsWith('release tag "v1.2.5" is "1.2.5"')))
})

test('test intent: unreadable or ambiguous versions and invalid tags fail instead of passing silently', () => {
  assert.throws(() => checkAlignment({ ...inputs('1'), pomXml: '<project></project>' }), /root <version>/)
  assert.throws(() => checkAlignment({ ...inputs('1'), packageJson: '{}' }), /daemon package version/)
  assert.throws(() => checkAlignment({ ...inputs('1'), configTs: 'const DAEMON_VERSION = "1"\n' }), /exactly one literal/)
  assert.throws(
    () => checkAlignment({ ...inputs('1'), configTs: `${CONFIG('1')}${CONFIG('1')}` }),
    /exactly one literal/,
  )
  assert.throws(
    () => checkAlignment({ ...inputs('1'), configTs: 'export const DAEMON_VERSION = readVersion()\n' }),
    /exactly one literal/,
  )
  for (const tag of ['', ' ', ' v1.2.3', 'v1.2.3 ', 'refs/heads/1.2.3', 'v']) {
    assert.throws(() => checkAlignment({ ...inputs('1.2.3'), tag }), /invalid RELEASE_TAG/, JSON.stringify(tag))
  }
})

test('test intent: the CLI checks only internal versions when RELEASE_TAG is absent', () => {
  const root = repositoryRoot()
  const version = /<version>([^<]+)<\/version>/.exec(readFileSync(path.join(root, 'pom.xml'), 'utf8'))[1]
  const env = { ...process.env }
  delete env.RELEASE_TAG
  const result = spawnSync(process.execPath, [path.join(root, 'scripts/dev/verify/repository/check-version-alignment.mjs')], {
    cwd: root,
    env,
    encoding: 'utf8',
  })
  assert.equal(result.status, 0, result.stderr)
  assert.equal(
    result.stdout,
    `repository version ${version} is aligned across pom.xml, kk-studio.version, daemon/package.json and daemon/src/config.ts\n`,
  )
})

test('test intent: the CLI strictly checks an explicitly supplied release tag', () => {
  const root = repositoryRoot()
  const version = /<version>([^<]+)<\/version>/.exec(readFileSync(path.join(root, 'pom.xml'), 'utf8'))[1]
  const run = (tag) => spawnSync(
    process.execPath,
    [path.join(root, 'scripts/dev/verify/repository/check-version-alignment.mjs')],
    { cwd: root, env: { ...process.env, RELEASE_TAG: tag }, encoding: 'utf8' },
  )

  const aligned = run(`v${version}`)
  assert.equal(aligned.status, 0, aligned.stderr)
  assert.ok(aligned.stdout.includes(`and release tag v${version}\n`))

  assert.equal(run(`v${version}-drift`).status, 1)
  for (const tag of ['', ' ', ' v1.2.3', 'v1.2.3 ', 'refs/heads/1.2.3']) {
    const result = run(tag)
    assert.equal(result.status, 1, JSON.stringify(tag))
    assert.match(result.stderr, /invalid RELEASE_TAG/)
  }
})
