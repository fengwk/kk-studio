import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { crc32 } from 'node:zlib'
import {
  appendFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  renameSync,
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
const AGENT_VERSION = '1.2.3'
const AGENT_NAME = `convention4j-agent-${AGENT_VERSION}.jar`
const AGENT_ENTRY = `BOOT-INF/lib/${AGENT_NAME}`
const STABLE_AGENT = 'convention4j-agent/convention4j-agent.jar'
const VERSIONED_AGENT = `convention4j-agent/${AGENT_NAME}`

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

/** Minimal agent bytes keep a Boot-Class-Path like the real artifact while staying byte-stable. */
function agentArchive() {
  return storedZip({
    'META-INF/MANIFEST.MF': `Manifest-Version: 1.0\nBoot-Class-Path: ${AGENT_NAME}\n`,
  })
}

function run(...args) {
  return spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' })
}

/** Resolve the real JDK 21 `jar`, which the extractor and synthetic boot JAR require. */
function jdk21Jar() {
  for (const home of [process.env.JAVA_HOME_21, process.env.JAVA_HOME_21_X64, process.env.JAVA_HOME]) {
    if (!home) {
      continue
    }
    const jar = path.join(home, 'bin/jar')
    const version = existsSync(jar) ? spawnSync(jar, ['--version'], { encoding: 'utf8' }) : null
    if (version?.status === 0 && /^jar 21\./u.test(version.stdout.trim())) {
      return jar
    }
  }
  throw new Error('JDK 21 is required to run the agent extraction regression')
}

/** Build an actual JAR via the JDK so the extractor exercises its real archive commands. */
function writeJdkJar(jar, archive, entries) {
  const staging = mkdtempSync(path.join(os.tmpdir(), 'kk-studio-jar-'))
  try {
    for (const [name, content] of Object.entries(entries)) {
      const file = path.join(staging, name)
      mkdirSync(path.dirname(file), { recursive: true })
      writeFileSync(file, content)
    }
    execFileSync(jar, ['--create', '--file', archive, '-C', staging, '.'])
  } finally {
    rmSync(staging, { recursive: true, force: true })
  }
}

/** Extract the workflow's real stage shell so the regression cannot drift from production. */
function workflowStageShell() {
  const workflow = readFileSync(path.join(ROOT, '.github/workflows/docker-publish.yml'), 'utf8')
  const start = workflow.indexOf('      - name: Stage the verified distribution artifact\n')
  assert.ok(start >= 0, 'missing workflow stage step')
  const lines = workflow.slice(start).split('\n')
  const runIndex = lines.indexOf('        run: |')
  assert.ok(runIndex >= 0, 'missing workflow stage run block')
  const body = []
  for (const line of lines.slice(runIndex + 1)) {
    if (!line.startsWith('          ')) {
      break
    }
    body.push(line.slice(10))
  }
  assert.ok(body.length > 0, 'empty workflow stage run block')
  return body.join('\n')
}

function workspace() {
  const directory = mkdtempSync(path.join(os.tmpdir(), 'kk-studio-app-artifact-'))
  const jar = path.join(directory, 'distribution.jar')
  const agent = path.join(directory, AGENT_NAME)
  const output = path.join(directory, 'verified-app')
  const agentBytes = agentArchive()
  writeArchive(jar, {
    'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\n',
    'BOOT-INF/classes/static/index.html': '<div id="root"></div>\n',
    'BOOT-INF/classes/static/assets/index-abc123.js': 'console.log("app")\n',
    [AGENT_ENTRY]: agentBytes,
  })
  writeFileSync(agent, agentBytes)
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
      STABLE_AGENT,
      VERSIONED_AGENT,
    ].sort())
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
    unlinkSync(path.join(fixture.output, STABLE_AGENT))
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
    unlinkSync(path.join(fixture.output, STABLE_AGENT))
    symlinkSync('/etc/hostname', path.join(fixture.output, STABLE_AGENT))
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
      [AGENT_ENTRY]: agentArchive(),
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
      [AGENT_ENTRY]: agentArchive(),
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
      [AGENT_ENTRY]: agentArchive(),
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

test('stage rejects an agent whose basename does not match the application dependency', () => {
  const fixture = workspace()
  try {
    const mismatched = path.join(fixture.directory, 'convention4j-agent-1.2.2.jar')
    writeFileSync(mismatched, readFileSync(fixture.agent))
    const result = run(
      'stage',
      '--jar', fixture.jar,
      '--agent', mismatched,
      '--output', fixture.output,
      '--version', VERSION,
      '--source-commit', COMMIT,
    )
    assert.equal(result.status, 1)
    assert.match(result.stderr, /does not match the application dependency/u)
    assert.equal(existsSync(fixture.output), false)
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})

test('verify requires the derived versioned agent and identical agent bytes', () => {
  const fixture = stageFixture()
  try {
    unlinkSync(path.join(fixture.output, VERSIONED_AGENT))
    let result = run('verify', '--dir', fixture.output, '--version', VERSION, '--source-commit', COMMIT)
    assert.equal(result.status, 1)
    assert.match(result.stderr, /closed contract/u)

    stageFixture(fixture)
    renameSync(
      path.join(fixture.output, VERSIONED_AGENT),
      path.join(fixture.output, 'convention4j-agent/convention4j-agent-1.2.4.jar'),
    )
    result = run('verify', '--dir', fixture.output, '--version', VERSION, '--source-commit', COMMIT)
    assert.equal(result.status, 1)
    assert.match(result.stderr, /closed contract/u)

    stageFixture(fixture)
    const versioned = path.join(fixture.output, VERSIONED_AGENT)
    appendFileSync(versioned, 'tampered')
    const manifestPath = path.join(fixture.output, 'manifest.json')
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
    manifest.sha256[VERSIONED_AGENT] = createHash('sha256').update(readFileSync(versioned)).digest('hex')
    writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`)
    result = run('verify', '--dir', fixture.output, '--version', VERSION, '--source-commit', COMMIT)
    assert.equal(result.status, 1)
    assert.match(result.stderr, /stable and versioned files differ/u)
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true })
  }
})

test('the workflow stage keeps both the stable and versioned agent files', () => {
  // Regression for hosted CI: the stable agent is a symlink, while Boot-Class-Path needs the
  // versioned file. Run the actual workflow stage shell against a synthetic Spring Boot JAR built
  // by the real JDK and extracted by the real shell extractor.
  const jar = jdk21Jar()
  const directory = mkdtempSync(path.join(os.tmpdir(), 'kk-studio-app-artifact-integration-'))
  try {
    const version = '9.9.9'
    const agentName = `convention4j-agent-${version}.jar`
    const agent = path.join(directory, agentName)
    const appJar = path.join(directory, `kk-studio-web-${VERSION}.jar`)
    writeJdkJar(jar, agent, {
      'META-INF/MANIFEST.MF': `Manifest-Version: 1.0\nBoot-Class-Path: ${agentName}\n`,
    })
    const agentBytes = readFileSync(agent)
    writeJdkJar(jar, appJar, {
      'BOOT-INF/classes/static/index.html': '<div id="root"></div>\n',
      'BOOT-INF/classes/static/assets/index-abc123.js': 'console.log("app")\n',
      [`BOOT-INF/lib/${agentName}`]: agentBytes,
    })

    const runnerTemp = path.join(directory, 'runner-temp')
    const output = path.join(runnerTemp, 'verified-app')
    mkdirSync(runnerTemp)
    const staged = spawnSync('bash', ['-e', '-c', workflowStageShell()], {
      cwd: ROOT,
      encoding: 'utf8',
      env: {
        ...process.env,
        APP_JAR: appJar,
        APP_VERSION: VERSION,
        RUNNER_TEMP: runnerTemp,
        GITHUB_SHA: COMMIT,
        PATH: `${path.dirname(jar)}:${process.env.PATH ?? ''}`,
      },
    })
    assert.equal(staged.status, 0, staged.stderr)

    const stable = path.join(output, STABLE_AGENT)
    const versioned = path.join(output, 'convention4j-agent', agentName)
    for (const file of [stable, versioned]) {
      assert.equal(lstatSync(file).isFile(), true, `${file} must be a regular file`)
      assert.equal(lstatSync(file).isSymbolicLink(), false, `${file} must not be a symlink`)
      assert.deepEqual(readFileSync(file), agentBytes)
    }
    const manifest = JSON.parse(readFileSync(path.join(output, 'manifest.json'), 'utf8'))
    assert.deepEqual(Object.keys(manifest.sha256).sort(), [
      'app.jar',
      STABLE_AGENT,
      `convention4j-agent/${agentName}`,
    ].sort())

    const verified = run('verify', '--dir', output, '--version', VERSION, '--source-commit', COMMIT)
    assert.equal(verified.status, 0, verified.stderr)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})
