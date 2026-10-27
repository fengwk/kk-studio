#!/usr/bin/env node
/**
 * Stage and verify the application artifact shared by the Docker validation and publish jobs.
 *
 * The staged directory is a closed contract: `app.jar`, the stable agent alias
 * `convention4j-agent/convention4j-agent.jar`, the versioned agent file required by the agent
 * manifest's `Boot-Class-Path`, and `manifest.json`. The output directory must not already exist;
 * stage creates it and the required parent directories, never replacing existing content. The
 * manifest binds all three payloads to the distribution version and source commit, so the publish
 * job can reject an artifact from another run, commit or version without rebuilding the
 * application.
 *
 * Usage:
 *   node scripts/dev/ci/app-artifact.mjs stage \
 *     --jar web/target/kk-studio-web-<version>.jar \
 *     --agent <path>/convention4j-agent-<version>.jar \
 *     --output <artifact-dir> --version <version> --source-commit <sha>
 *   node scripts/dev/ci/app-artifact.mjs verify \
 *     --dir <artifact-dir> --version <version> --source-commit <sha>
 */

import { createHash } from 'node:crypto'
import {
  accessSync,
  constants,
  copyFileSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'

export const APP_FILE = 'app.jar'
export const AGENT_DIR = 'convention4j-agent'
export const AGENT_FILE = `${AGENT_DIR}/convention4j-agent.jar`
export const MANIFEST_FILE = 'manifest.json'

const STATIC_INDEX = 'BOOT-INF/classes/static/index.html'
const STATIC_ASSETS = 'BOOT-INF/classes/static/assets/'
const AGENT_LIBRARY_ENTRY = /^BOOT-INF\/lib\/convention4j-agent-[^/]+\.jar$/u
const AGENT_LIBRARY_NAME = /^convention4j-agent-[0-9A-Za-z][0-9A-Za-z._+-]*\.jar$/u
const SOURCE_COMMIT = /^[0-9a-f]{40}$/u
const VERSION = /^[0-9A-Za-z][0-9A-Za-z._-]*$/u
const SHA256 = /^[0-9a-f]{64}$/u

/**
 * Read the ZIP central directory without relying on the JDK or an external unzip binary.
 * This checks repository-built Spring Boot and agent artifacts; it is not a general-purpose
 * security validator for untrusted ZIP input.
 */
export function listZipEntries(archive) {
  const buffer = readFileSync(archive)
  if (buffer.length < 22) {
    throw new Error(`${archive} is not a ZIP/JAR archive`)
  }
  let eocd = -1
  const earliest = Math.max(0, buffer.length - 0xffff - 22)
  for (let offset = buffer.length - 22; offset >= earliest; offset -= 1) {
    if (buffer.readUInt32LE(offset) === 0x06054b50) {
      eocd = offset
      break
    }
  }
  if (eocd < 0) {
    throw new Error(`${archive} is not a ZIP/JAR archive`)
  }
  const count = buffer.readUInt16LE(eocd + 10)
  const directoryOffset = buffer.readUInt32LE(eocd + 16)
  if (count === 0xffff || directoryOffset === 0xffffffff) {
    throw new Error(`${archive} uses unsupported ZIP64 entries`)
  }
  const entries = []
  let cursor = directoryOffset
  for (let index = 0; index < count; index += 1) {
    if (cursor + 46 > buffer.length || buffer.readUInt32LE(cursor) !== 0x02014b50) {
      throw new Error(`${archive} has a corrupt ZIP central directory`)
    }
    const nameLength = buffer.readUInt16LE(cursor + 28)
    const extraLength = buffer.readUInt16LE(cursor + 30)
    const commentLength = buffer.readUInt16LE(cursor + 32)
    entries.push(buffer.toString('utf8', cursor + 46, cursor + 46 + nameLength))
    cursor += 46 + nameLength + extraLength + commentLength
  }
  return entries
}

function requireValue(value, label) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${label} is required`)
  }
  return value
}

function requireVersion(value) {
  if (!VERSION.test(value)) {
    throw new Error(`unsupported version: ${JSON.stringify(value)}`)
  }
}

function requireSourceCommit(value) {
  if (!SOURCE_COMMIT.test(value)) {
    throw new Error('source commit must be a full 40-character Git SHA')
  }
}

function requireReadableFile(file, label) {
  const stat = lstatSync(file, { throwIfNoEntry: false })
  if (!stat || !stat.isFile()) {
    throw new Error(`${label} is missing or not a regular file: ${file}`)
  }
  accessSync(file, constants.R_OK)
}

function requireFrontendEntries(archive) {
  const entries = listZipEntries(archive)
  if (!entries.includes(STATIC_INDEX)) {
    throw new Error(`${archive} does not contain ${STATIC_INDEX}`)
  }
  if (!entries.some((entry) => entry.startsWith(STATIC_ASSETS) && !entry.endsWith('/'))) {
    throw new Error(`${archive} does not contain embedded frontend assets below ${STATIC_ASSETS}`)
  }
}

/** Derive the single versioned agent name that the application manifest must boot. */
function convention4jAgentName(archive) {
  const matches = listZipEntries(archive).filter((entry) => AGENT_LIBRARY_ENTRY.test(entry))
  if (matches.length !== 1) {
    throw new Error(`expected exactly one convention4j-agent dependency in ${archive}`)
  }
  const name = matches[0].slice('BOOT-INF/lib/'.length)
  if (!AGENT_LIBRARY_NAME.test(name) || path.posix.basename(name) !== name) {
    throw new Error(`convention4j agent dependency has an unsafe filename: ${JSON.stringify(name)}`)
  }
  return name
}

function versionedAgentFile(name) {
  return `${AGENT_DIR}/${name}`
}

function payloadFiles(versionedAgent) {
  return [APP_FILE, AGENT_FILE, versionedAgentFile(versionedAgent)]
}

function expectedArtifactFiles(versionedAgent) {
  return [...payloadFiles(versionedAgent), MANIFEST_FILE].sort()
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex')
}

function artifactFiles(root) {
  const files = []
  const visit = (directory, relative) => {
    for (const name of readdirSync(directory).sort()) {
      const absolute = path.join(directory, name)
      const pathname = relative ? `${relative}/${name}` : name
      const stat = lstatSync(absolute)
      if (stat.isSymbolicLink()) {
        throw new Error(`artifact must not contain symlinks: ${pathname}`)
      }
      if (stat.isDirectory()) {
        visit(absolute, pathname)
      } else if (stat.isFile()) {
        files.push(pathname)
      } else {
        throw new Error(`artifact contains an unsupported entry: ${pathname}`)
      }
    }
  }
  visit(root, '')
  return files.sort()
}

function requireExactManifest(manifest, versionedAgent) {
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
    throw new Error('manifest.json must contain an object')
  }
  const keys = Object.keys(manifest).sort()
  if (keys.join(',') !== 'sha256,sourceCommit,version') {
    throw new Error('manifest.json must contain exactly version, sourceCommit and sha256')
  }
  if (typeof manifest.sha256 !== 'object' || manifest.sha256 === null || Array.isArray(manifest.sha256)) {
    throw new Error('manifest.json sha256 must contain the artifact file digests')
  }
  if (Object.keys(manifest.sha256).sort().join(',') !== payloadFiles(versionedAgent).sort().join(',')) {
    throw new Error('manifest.json sha256 must cover exactly app.jar and both convention4j agent files')
  }
  return manifest
}

export function stage({ jar, agent, output, version, sourceCommit }) {
  requireVersion(requireValue(version, 'version'))
  requireSourceCommit(requireValue(sourceCommit, 'source commit'))
  requireReadableFile(jar, 'application JAR')
  requireReadableFile(agent, 'convention4j agent')
  requireFrontendEntries(jar)
  const expectedAgentName = convention4jAgentName(jar)
  const actualAgentName = path.basename(agent)
  if (actualAgentName !== expectedAgentName) {
    throw new Error(
      `convention4j agent basename ${JSON.stringify(actualAgentName)} does not match the application dependency ${JSON.stringify(expectedAgentName)}`,
    )
  }
  if (listZipEntries(agent).length === 0) {
    throw new Error('convention4j agent is not a readable JAR')
  }

  const resolvedOutput = path.resolve(requireValue(output, 'output directory'))
  if (lstatSync(resolvedOutput, { throwIfNoEntry: false })) {
    throw new Error(`output directory already exists: ${resolvedOutput}`)
  }
  mkdirSync(path.join(resolvedOutput, AGENT_DIR), { recursive: true })
  copyFileSync(jar, path.join(resolvedOutput, APP_FILE))
  copyFileSync(agent, path.join(resolvedOutput, AGENT_FILE))
  copyFileSync(agent, path.join(resolvedOutput, versionedAgentFile(expectedAgentName)))

  const manifest = {
    version,
    sourceCommit,
    sha256: Object.fromEntries(
      payloadFiles(expectedAgentName).map((file) => [file, sha256(path.join(resolvedOutput, file))]),
    ),
  }
  writeFileSync(
    path.join(resolvedOutput, MANIFEST_FILE),
    `${JSON.stringify(manifest, null, 2)}\n`,
    { mode: 0o600 },
  )
  return manifest
}

export function verify({ dir, version, sourceCommit }) {
  requireVersion(requireValue(version, 'version'))
  requireSourceCommit(requireValue(sourceCommit, 'source commit'))
  const root = path.resolve(dir)
  const stat = lstatSync(root, { throwIfNoEntry: false })
  if (!stat || !stat.isDirectory()) {
    throw new Error(`artifact directory is missing: ${root}`)
  }
  const appJar = path.join(root, APP_FILE)
  requireReadableFile(appJar, 'application JAR')
  const versionedAgent = convention4jAgentName(appJar)
  const actual = artifactFiles(root)
  const expected = expectedArtifactFiles(versionedAgent)
  if (actual.join('\n') !== expected.join('\n')) {
    throw new Error(
      `artifact file set is not the closed contract: expected ${expected.join(', ')}, got ${actual.join(', ')}`,
    )
  }

  let manifest
  try {
    manifest = requireExactManifest(
      JSON.parse(readFileSync(path.join(root, MANIFEST_FILE), 'utf8')),
      versionedAgent,
    )
  } catch (error) {
    if (error instanceof SyntaxError) {
      throw new Error('manifest.json is not valid JSON')
    }
    throw error
  }
  if (manifest.version !== version) {
    throw new Error(`artifact version ${JSON.stringify(manifest.version)} does not match ${JSON.stringify(version)}`)
  }
  if (manifest.sourceCommit !== sourceCommit) {
    throw new Error('artifact source commit does not match this workflow run')
  }
  for (const file of payloadFiles(versionedAgent)) {
    const digest = manifest.sha256[file]
    if (typeof digest !== 'string' || !SHA256.test(digest)) {
      throw new Error(`manifest.json has an invalid sha256 for ${file}`)
    }
    const actualDigest = sha256(path.join(root, file))
    if (actualDigest !== digest) {
      throw new Error(`artifact integrity check failed for ${file}`)
    }
  }
  const stableAgent = readFileSync(path.join(root, AGENT_FILE))
  const versionedAgentBytes = readFileSync(path.join(root, versionedAgentFile(versionedAgent)))
  if (!stableAgent.equals(versionedAgentBytes)) {
    throw new Error('convention4j agent stable and versioned files differ')
  }
  requireFrontendEntries(appJar)
  if (listZipEntries(path.join(root, versionedAgentFile(versionedAgent))).length === 0) {
    throw new Error('convention4j agent is not a readable JAR')
  }
  return manifest
}

function main(argv = process.argv.slice(2)) {
  let parsed
  try {
    parsed = parseArgs({
      args: argv,
      allowPositionals: true,
      options: {
        jar: { type: 'string' },
        agent: { type: 'string' },
        output: { type: 'string' },
        dir: { type: 'string' },
        version: { type: 'string' },
        'source-commit': { type: 'string' },
      },
    })
  } catch (error) {
    process.stderr.write(`app-artifact: ${error.message}\n`)
    return 2
  }
  const [command] = parsed.positionals
  const { values } = parsed
  try {
    if (command === 'stage') {
      const manifest = stage({
        jar: values.jar,
        agent: values.agent,
        output: values.output,
        version: values.version,
        sourceCommit: values['source-commit'],
      })
      process.stdout.write(
        `verified app artifact staged: version=${manifest.version} commit=${manifest.sourceCommit} sha256=${manifest.sha256[APP_FILE]}\n`,
      )
      return 0
    }
    if (command === 'verify') {
      const manifest = verify({
        dir: values.dir,
        version: values.version,
        sourceCommit: values['source-commit'],
      })
      process.stdout.write(
        `verified app artifact is intact: version=${manifest.version} commit=${manifest.sourceCommit} sha256=${manifest.sha256[APP_FILE]}\n`,
      )
      return 0
    }
    process.stderr.write('usage: app-artifact.mjs <stage|verify> [options]\n')
    return 2
  } catch (error) {
    process.stderr.write(`app-artifact: ${error.message}\n`)
    return 1
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = main()
}
