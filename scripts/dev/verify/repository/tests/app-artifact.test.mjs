import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { crc32 } from 'node:zlib'
import {
  existsSync,
  lstatSync,
  mkdtempSync,
  readFileSync,
  rmSync,
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
const APP_FILE = 'app.jar'
const MANIFEST_FILE = 'manifest.json'

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
  const output = path.join(directory, 'verified-app')
  writeArchive(jar, {
    'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\n',
    'BOOT-INF/classes/static/index.html': '<div id="root"></div>\n',
    'BOOT-INF/classes/static/assets/index-abc123.js': 'console.log("app")\n',
    'BOOT-INF/lib/convention4j-agent-1.2.2.jar': storedZip({ 'META-INF/MANIFEST.MF': '' }),
  })
  return { directory, jar, output }
}

function stageFixture(fixture = workspace()) {
  rmSync(fixture.output, { recursive: true, force: true })
  const result = run(
    'stage',
    '--jar', fixture.jar,
    '--output', fixture.output,
    '--version', VERSION,
    '--source-commit', COMMIT,
  )
  assert.equal(result.status, 0, result.stderr)
  return fixture
}

function manifest(output) {
  return JSON.parse(readFileSync(path.join(output, MANIFEST_FILE), 'utf8'))
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex')
}

test('stage copies only the application JAR and records its digest', () => {
  const fixture = stageFixture()
  try {
    assert.deepEqual(
      readFileSync(path.join(fixture.output, APP_FILE)),
      readFileSync(fixture.jar),
    )
    const staged = manifest(fixture.output)
    assert.deepEqual(Object.keys(staged).sort(), ['sha256', 'sourceCommit', 'version'])
    assert.deepEqual(Object.keys(staged.sha256), [APP_FILE])
    assert.equal(staged.version, VERSION)
    assert.equal(staged.sourceCommit, COMMIT)
    assert.equal(staged.sha256[APP_FILE], sha256(path.join(fixture.output, APP_FILE)))
    assert.equal(existsSync(path.join(fixture.output, 'convention4j-agent')), false)
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})

test('stage refuses an existing output directory', () => {
  const fixture = stageFixture()
  try {
    const result = run(
      'stage',
      '--jar', fixture.jar,
      '--output', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(result.status, 1)
    assert.match(result.stderr, /output directory already exists/u)
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})

test('stage rejects an application JAR without embedded frontend assets', () => {
  const fixture = workspace()
  try {
    writeArchive(fixture.jar, { 'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\n' })
    const result = run(
      'stage',
      '--jar', fixture.jar,
      '--output', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(result.status, 1)
    assert.match(result.stderr, /static\/index\.html/u)
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})

test('verify accepts the closed staged contract', () => {
  const fixture = stageFixture()
  try {
    const result = run(
      'verify',
      '--dir', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(result.status, 0, result.stderr)
    assert.match(result.stdout, /verified app artifact is intact/u)
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})

test('verify rejects manifest or payload tampering', () => {
  const fixture = stageFixture()
  try {
    const appJar = path.join(fixture.output, APP_FILE)
    writeFileSync(appJar, Buffer.concat([readFileSync(appJar), Buffer.from('tamper')]))
    const result = run(
      'verify',
      '--dir', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(result.status, 1)
    assert.match(result.stderr, /integrity check failed for app\.jar/u)

    stageFixture(fixture)
    const staged = manifest(fixture.output)
    staged.version = '9.9.9'
    writeFileSync(path.join(fixture.output, MANIFEST_FILE), `${JSON.stringify(staged)}\n`)
    const versionMismatch = run(
      'verify',
      '--dir', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(versionMismatch.status, 1)
    assert.match(versionMismatch.stderr, /artifact version/u)
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})

test('verify rejects extra files and missing payloads', () => {
  const fixture = stageFixture()
  try {
    writeFileSync(path.join(fixture.output, 'extra.txt'), 'extra')
    const extra = run(
      'verify',
      '--dir', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(extra.status, 1)
    assert.match(extra.stderr, /closed contract/u)

    stageFixture(fixture)
    unlinkSync(path.join(fixture.output, APP_FILE))
    const missing = run(
      'verify',
      '--dir', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(missing.status, 1)
    assert.match(missing.stderr, /application JAR is missing/u)
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})

test('the workflow stage contract has no agent step', () => {
  const workflow = readFileSync(path.join(ROOT, '.github/workflows/docker-publish.yml'), 'utf8')
  const start = workflow.indexOf('      - name: Stage the verified distribution artifact\n')
  assert.ok(start >= 0)
  const stage = workflow.slice(start, workflow.indexOf('      - name:', start + 10))
  assert.match(stage, /node scripts\/dev\/ci\/app-artifact\.mjs stage/u)
  assert.match(stage, /--jar "\$\{APP_JAR\}"/u)
  assert.doesNotMatch(stage, /agent|javaagent|convention4j/iu)
  assert.equal(
    lstatSync(path.join(ROOT, 'scripts/dev/lib/extract-convention4j-agent.sh'), {
      throwIfNoEntry: false,
    }),
    undefined,
  )
})
