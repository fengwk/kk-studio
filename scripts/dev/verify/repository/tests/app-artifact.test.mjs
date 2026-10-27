import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { crc32 } from 'node:zlib'
import {
  appendFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../../lib/repo-root.mjs'

const ROOT = repositoryRoot()
const SCRIPT = path.join(ROOT, 'scripts/dev/ci/app-artifact.mjs')
const VERSION = '4.0.1'
const COMMIT = '0123456789abcdef0123456789abcdef01234567'

/** Build a stored-only ZIP so the test does not depend on a JDK or external unzip. */
function storedZip(entries) {
  const locals = []
  const centrals = []
  let offset = 0
  for (const [name, content] of Object.entries(entries)) {
    const data = Buffer.from(content)
    const nameBuffer = Buffer.from(name)
    const digest = crc32(data)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(0x0800, 6)
    local.writeUInt32LE(digest, 14)
    local.writeUInt32LE(data.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(nameBuffer.length, 26)
    locals.push(local, nameBuffer, data)

    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt16LE(0x0800, 8)
    central.writeUInt32LE(digest, 16)
    central.writeUInt32LE(data.length, 20)
    central.writeUInt32LE(data.length, 24)
    central.writeUInt16LE(nameBuffer.length, 28)
    central.writeUInt32LE(offset, 42)
    centrals.push(central, nameBuffer)
    offset += local.length + nameBuffer.length + data.length
  }
  const directory = Buffer.concat(centrals)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(Object.keys(entries).length, 8)
  end.writeUInt16LE(Object.keys(entries).length, 10)
  end.writeUInt32LE(directory.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, directory, end])
}

function writeArchive(file, entries) {
  writeFileSync(file, storedZip(entries))
}

function run(...args) {
  return spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' })
}

function workspace() {
  const directory = mkdtempSync(path.join(os.tmpdir(), 'kk-studio-app-artifact-'))
  const jar = path.join(directory, 'distribution.jar')
  const agent = path.join(directory, 'convention4j-agent.jar')
  const output = path.join(directory, 'verified-app')
  writeArchive(jar, {
    'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\n',
    'BOOT-INF/classes/static/index.html': '<div id="root"></div>\n',
    'BOOT-INF/classes/static/assets/index-abc123.js': 'console.log("app")\n',
  })
  writeArchive(agent, { 'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\n' })
  return { directory, jar, agent, output }
}

function stageFixture(fixture = workspace()) {
  // The staging contract never replaces an existing directory, so tests clear only their own temp output.
  rmSync(fixture.output, { recursive: true, force: true })
  const result = run(
    'stage',
    '--jar', fixture.jar,
    '--agent', fixture.agent,
    '--output', fixture.output,
    '--version', VERSION,
    '--source-commit', COMMIT,
  )
  assert.equal(result.status, 0, result.stderr)
  return fixture
}

test('stage writes the closed artifact contract and verify accepts it', () => {
  const fixture = stageFixture()
  try {
    const manifest = JSON.parse(readFileSync(path.join(fixture.output, 'manifest.json'), 'utf8'))
    assert.deepEqual(Object.keys(manifest).sort(), ['sha256', 'sourceCommit', 'version'])
    assert.equal(manifest.version, VERSION)
    assert.equal(manifest.sourceCommit, COMMIT)
    assert.deepEqual(Object.keys(manifest.sha256).sort(), [
      'app.jar',
      'convention4j-agent/convention4j-agent.jar',
    ])
    const verified = run(
      'verify',
      '--dir', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(verified.status, 0, verified.stderr)
    assert.match(verified.stdout, /verified app artifact is intact/u)
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})

test('stage rejects an existing output directory without touching its contents', () => {
  const fixture = workspace()
  try {
    mkdirSync(fixture.output)
    const sentinel = path.join(fixture.output, 'sentinel.txt')
    writeFileSync(sentinel, 'preserve me\n')
    const result = run(
      'stage',
      '--jar', fixture.jar,
      '--agent', fixture.agent,
      '--output', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(result.status, 1)
    assert.match(result.stderr, /output directory already exists/u)
    assert.equal(readFileSync(sentinel, 'utf8'), 'preserve me\n')
    assert.equal(existsSync(path.join(fixture.output, 'app.jar')), false)
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})

test('verify fails closed on a foreign commit or version', () => {
  const fixture = stageFixture()
  try {
    for (const [label, args] of [
      ['commit', ['--version', VERSION, '--source-commit', 'f'.repeat(40)]],
      ['version', ['--version', '4.0.2', '--source-commit', COMMIT]],
    ]) {
      const result = run('verify', '--dir', fixture.output, ...args)
      assert.equal(result.status, 1, `${label} must be rejected`)
      assert.match(result.stderr, /does not match/u)
    }
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})

test('verify fails closed on a damaged artifact and a missing file', () => {
  const fixture = stageFixture()
  try {
    appendFileSync(path.join(fixture.output, 'app.jar'), 'tampered')
    const damaged = run(
      'verify',
      '--dir', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(damaged.status, 1)
    assert.match(damaged.stderr, /integrity check failed for app\.jar/u)

    stageFixture(fixture)
    unlinkSync(path.join(fixture.output, 'convention4j-agent/convention4j-agent.jar'))
    const missing = run(
      'verify',
      '--dir', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(missing.status, 1)
    assert.match(missing.stderr, /artifact file set is not the closed contract/u)
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})

test('verify fails closed on extra files, corrupt manifests and symlinks', () => {
  const fixture = stageFixture()
  try {
    writeFileSync(path.join(fixture.output, 'unexpected.txt'), 'extra\n')
    let result = run('verify', '--dir', fixture.output, '--version', VERSION, '--source-commit', COMMIT)
    assert.equal(result.status, 1)
    assert.match(result.stderr, /closed contract/u)

    stageFixture(fixture)
    writeFileSync(path.join(fixture.output, 'manifest.json'), '{not json')
    result = run('verify', '--dir', fixture.output, '--version', VERSION, '--source-commit', COMMIT)
    assert.equal(result.status, 1)
    assert.match(result.stderr, /not valid JSON/u)

    stageFixture(fixture)
    unlinkSync(path.join(fixture.output, 'convention4j-agent/convention4j-agent.jar'))
    symlinkSync('/etc/hostname', path.join(fixture.output, 'convention4j-agent/convention4j-agent.jar'))
    result = run('verify', '--dir', fixture.output, '--version', VERSION, '--source-commit', COMMIT)
    assert.equal(result.status, 1)
    assert.match(result.stderr, /must not contain symlinks/u)
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})

test('stage rejects a JAR without the embedded frontend or with an invalid commit', () => {
  const fixture = workspace()
  try {
    writeArchive(fixture.jar, {
      'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\n',
      'BOOT-INF/classes/static/assets/index-abc123.js': 'console.log("app")\n',
    })
    let result = run(
      'stage',
      '--jar', fixture.jar,
      '--agent', fixture.agent,
      '--output', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(result.status, 1)
    assert.match(result.stderr, /does not contain BOOT-INF\/classes\/static\/index\.html/u)

    writeArchive(fixture.jar, {
      'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\n',
      'BOOT-INF/classes/static/index.html': '<div id="root"></div>\n',
    })
    result = run(
      'stage',
      '--jar', fixture.jar,
      '--agent', fixture.agent,
      '--output', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(result.status, 1)
    assert.match(result.stderr, /does not contain embedded frontend assets/u)

    writeArchive(fixture.jar, {
      'BOOT-INF/classes/static/index.html': '<div id="root"></div>\n',
      'BOOT-INF/classes/static/assets/index-abc123.js': 'console.log("app")\n',
    })
    result = run(
      'stage',
      '--jar', fixture.jar,
      '--agent', fixture.agent,
      '--output', fixture.output,
      '--version', VERSION,
      '--source-commit', 'not-a-commit',
    )
    assert.equal(result.status, 1)
    assert.match(result.stderr, /full 40-character Git SHA/u)
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})
