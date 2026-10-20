/** Verify the runtime agent is extracted only from an unambiguous application JAR. */

import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import {
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readlinkSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../../lib/repo-root.mjs'

const EXTRACT = path.join(repositoryRoot(), 'scripts/dev/lib/extract-convention4j-agent.sh')

/** `jar` from JAVA_HOME when set, otherwise from PATH. */
const JAR = (() => {
  const candidate = process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, 'bin/jar')
  if (candidate && existsSync(candidate)) {
    return candidate
  }
  return spawnSync('jar', ['--version'], { stdio: 'ignore' }).error ? null : 'jar'
})()
const skip = JAR === null && 'the jar command is not available'

/** Build `archive` with the given `{ entryName: content }` files. */
function writeJar(workspace, archive, entries) {
  const staging = mkdtempSync(path.join(workspace, 'jar-'))
  for (const [name, content] of Object.entries(entries)) {
    const file = path.join(staging, name)
    mkdirSync(path.dirname(file), { recursive: true })
    writeFileSync(file, content)
  }
  rmSync(archive, { force: true })
  execFileSync(JAR, ['--create', '--file', archive, '-C', staging, '.'])
}

test('test intent: the matching agent keeps its manifest filename and replaces the stable alias', { skip }, () => {
  // The stable alias must keep the versioned sibling required by Boot-Class-Path.
  const workspace = mkdtempSync(path.join(tmpdir(), 'kk-agent-'))
  try {
    const archive = path.join(workspace, 'app.jar')
    const output = path.join(workspace, 'agent/convention4j-agent.jar')
    for (const [version, content] of [
      ['1.2.2', 'old-agent'],
      ['1.2.3', 'new-agent'],
    ]) {
      writeJar(workspace, archive, { [`BOOT-INF/lib/convention4j-agent-${version}.jar`]: content })

      execFileSync('sh', [EXTRACT, JAR, archive, output])

      const versioned = path.join(path.dirname(output), `convention4j-agent-${version}.jar`)
      assert.ok(lstatSync(output).isSymbolicLink())
      assert.equal(readlinkSync(output), path.basename(versioned))
      assert.equal(readFileSync(output, 'utf8'), content)
      assert.equal(readFileSync(versioned, 'utf8'), content)
    }
    assert.ok(!existsSync(path.join(path.dirname(output), 'convention4j-agent-1.2.2.jar')))
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
})

test('test intent: a missing or ambiguous agent fails closed without overwriting the output', { skip }, () => {
  // Builds must fail instead of silently running without tracing or picking a stale JAR.
  const workspace = mkdtempSync(path.join(tmpdir(), 'kk-agent-'))
  try {
    const archive = path.join(workspace, 'app.jar')
    const output = path.join(workspace, 'agent/convention4j-agent.jar')
    mkdirSync(path.dirname(output))
    writeFileSync(output, 'keep')
    for (const entries of [
      { 'BOOT-INF/lib/convention4j-tracer-1.2.2.jar': 'tracer' },
      {
        'BOOT-INF/lib/convention4j-agent-1.2.1.jar': 'old',
        'BOOT-INF/lib/convention4j-agent-1.2.2.jar': 'new',
      },
    ]) {
      writeJar(workspace, archive, entries)

      const result = spawnSync('sh', [EXTRACT, JAR, archive, output], { encoding: 'utf8' })

      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('exactly one convention4j-agent'))
      assert.equal(readFileSync(output, 'utf8'), 'keep')
    }
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
})
