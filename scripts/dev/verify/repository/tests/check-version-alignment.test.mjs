/** The release workflow gate that keeps the POM, daemon package and release tag on one version. */

import assert from 'node:assert/strict'
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

test('test intent: aligned sources pass and the tag may carry a v prefix', () => {
  for (const tag of ['v1.2.3', '1.2.3']) {
    const result = checkAlignment({ pomXml: POM('1.2.3'), packageJson: PACKAGE('1.2.3'), tag })
    assert.deepEqual(result, { expected: '1.2.3', mismatches: [] })
  }
})

test('test intent: every diverging source is reported against the root version', () => {
  const result = checkAlignment({ pomXml: POM('1.2.3', '1.2.4'), packageJson: PACKAGE('1.2.2'), tag: 'v1.2.5' })
  assert.equal(result.mismatches.length, 3)
  assert.ok(result.mismatches.some((line) => line.startsWith('pom.xml kk-studio.version is "1.2.4"')))
  assert.ok(result.mismatches.some((line) => line.startsWith('daemon/package.json version is "1.2.2"')))
  assert.ok(result.mismatches.some((line) => line.startsWith('release tag "v1.2.5" is "1.2.5"')))
})

test('test intent: unreadable versions fail instead of passing silently', () => {
  assert.throws(() => checkAlignment({ pomXml: '<project></project>', packageJson: PACKAGE('1'), tag: 'v1' }), /root <version>/)
  assert.throws(() => checkAlignment({ pomXml: POM('1'), packageJson: '{}', tag: 'v1' }), /daemon package version/)
})

test('test intent: the current repository is aligned with its own version as the tag', () => {
  const root = repositoryRoot()
  const version = /<version>([^<]+)<\/version>/.exec(
    spawnSync('node', ['-e', "process.stdout.write(require('fs').readFileSync('pom.xml','utf8'))"], { cwd: root, encoding: 'utf8' }).stdout,
  )[1]
  const result = spawnSync(process.execPath, [path.join(root, 'scripts/dev/verify/repository/check-version-alignment.mjs')], {
    cwd: root,
    env: { ...process.env, RELEASE_TAG: `v${version}` },
    encoding: 'utf8',
  })
  assert.equal(result.status, 0, result.stderr)
  assert.ok(result.stdout.includes(`repository version ${version} is aligned`))
})
