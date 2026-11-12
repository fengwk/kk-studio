/** The release gate keeps every active repository version source aligned. */

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
</project>`
const IDE_EXTENSION_PACKAGE = (version) => JSON.stringify({ name: 'kk-studio-ide-bridge', version })
const FRONTEND_PACKAGE = (version) => JSON.stringify({ name: 'kk-studio-frontend', version })
const GO_CONFIG = (version) => `package config\n\nvar DaemonVersion = "${version}"\n`
const inputs = (
  version,
  {
    property = version,
    ideExtensionPackageVersion = version,
    frontendPackageVersion = version,
    goVersion = version,
  } = {},
) => ({
  pomXml: POM(version, property),
  ideExtensionPackageJson: IDE_EXTENSION_PACKAGE(ideExtensionPackageVersion),
  frontendPackageJson: FRONTEND_PACKAGE(frontendPackageVersion),
  goConfig: GO_CONFIG(goVersion),
})

test('test intent: aligned Go and application versions pass and the tag must carry a v prefix', () => {
  assert.deepEqual(checkAlignment(inputs('1.2.3')), { expected: '1.2.3', mismatches: [] })
  assert.deepEqual(checkAlignment({ ...inputs('1.2.3'), tag: 'v1.2.3' }), { expected: '1.2.3', mismatches: [] })
})

test('test intent: each active runtime and package version source is compared to the root version', () => {
  const result = checkAlignment({
    ...inputs('1.2.3', { property: '1.2.4', goVersion: '1.2.2', ideExtensionPackageVersion: '1.2.0', frontendPackageVersion: '1.2.6' }),
    tag: 'v1.2.5',
  })
  assert.equal(result.mismatches.length, 5)
  assert.ok(result.mismatches.some((line) => line.startsWith('pom.xml kk-studio.version is "1.2.4"')))
  assert.ok(result.mismatches.some((line) => line.startsWith('daemon-go/internal/config/version.go DaemonVersion is "1.2.2"')))
  assert.ok(result.mismatches.some((line) => line.startsWith('daemon-go/ide-extension/package.json version is "1.2.0"')))
  assert.ok(result.mismatches.some((line) => line.startsWith('frontend/package.json version is "1.2.6"')))
  assert.ok(result.mismatches.some((line) => line.startsWith('release tag "v1.2.5" is "1.2.5"')))
})

test('test intent: missing or ambiguous Go and package versions and invalid tags fail closed', () => {
  assert.throws(() => checkAlignment({ ...inputs('1'), pomXml: '<project></project>' }), /root <version>/)
  assert.throws(() => checkAlignment({ ...inputs('1'), ideExtensionPackageJson: '{}' }), /daemon IDE extension package version/)
  assert.throws(() => checkAlignment({ ...inputs('1'), frontendPackageJson: '{}' }), /frontend package version/)
  assert.throws(() => checkAlignment({ ...inputs('1'), goConfig: 'const DaemonVersion = version\n' }), /exactly one literal/)
  assert.throws(() => checkAlignment({ ...inputs('1'), goConfig: `${GO_CONFIG('1')}${GO_CONFIG('1')}` }), /exactly one literal/)
  for (const tag of ['', ' ', ' v1.2.3', 'v1.2.3 ', 'refs/heads/1.2.3', 'v', '1.2.3']) {
    assert.throws(() => checkAlignment({ ...inputs('1.2.3'), tag }), /invalid RELEASE_TAG/, JSON.stringify(tag))
  }
})

test('test intent: CLI lists only Go daemon sources and available optional package sources', () => {
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
  const sources = [
    'pom.xml',
    'kk-studio.version',
    'daemon-go/internal/config/version.go',
    ...(requireExists(root, 'daemon-go/ide-extension/package.json') ? ['daemon-go/ide-extension/package.json'] : []),
    ...(requireExists(root, 'frontend/package.json') ? ['frontend/package.json'] : []),
  ]
  assert.equal(result.stdout, `repository version ${version} is aligned across ${sources.slice(0, -1).join(', ')} and ${sources.at(-1)}\n`)
})

test('test intent: CLI strictly rejects a tag that differs from the aligned repository version', () => {
  const root = repositoryRoot()
  const version = /<version>([^<]+)<\/version>/.exec(readFileSync(path.join(root, 'pom.xml'), 'utf8'))[1]
  const run = (tag) => spawnSync(process.execPath, [path.join(root, 'scripts/dev/verify/repository/check-version-alignment.mjs')], {
    cwd: root,
    env: { ...process.env, RELEASE_TAG: tag },
    encoding: 'utf8',
  })
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

function requireExists(root, relativePath) {
  try {
    readFileSync(path.join(root, relativePath))
    return true
  } catch {
    return false
  }
}
