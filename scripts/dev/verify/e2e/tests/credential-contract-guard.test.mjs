/** Permanent guards for the four-provider real E2E credential boundary. */

import assert from 'node:assert/strict'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../../lib/repo-root.mjs'

const REPOSITORY_ROOT = repositoryRoot()
const ALLOWED_CREDENTIAL_ENVIRONMENTS = new Set([
  'TEST_GOOGLE_BASE_URL',
  'TEST_GOOGLE_API_KEY',
  'TEST_OPENAI_RESPONSES_BASE_URL',
  'TEST_OPENAI_RESPONSES_API_KEY',
  'TEST_ANTHROPIC_BASE_URL',
  'TEST_ANTHROPIC_API_KEY',
  'TEST_OPENAI_BASE_URL',
  'TEST_OPENAI_API_KEY',
])
const NON_ALLOWED_CREDENTIAL =
  /\bTEST_(?!(?:GOOGLE|OPENAI_RESPONSES|ANTHROPIC|OPENAI|MINIMAX)_(?:BASE_URL|API_KEY)\b)[A-Z0-9_]+_(?:BASE_URL|API_KEY)\b/g
const SYNC_SCRIPT = 'scripts/dev/verify/e2e/sync-provider-credentials.mjs'

function* walk(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      yield* walk(file)
    } else if (entry.isFile()) {
      yield file
    }
  }
}

/** Only production scripts, configuration, compose, and documentation. */
function* productionFiles() {
  for (const file of walk(path.join(REPOSITORY_ROOT, 'scripts'))) {
    const parts = path.relative(REPOSITORY_ROOT, file).split(path.sep)
    if (['.sh', '.mjs'].includes(path.extname(file)) && !parts.includes('tests')) {
      yield file
    }
  }
  for (const file of walk(path.join(REPOSITORY_ROOT, 'docs'))) {
    if (path.extname(file) === '.md') {
      yield file
    }
  }
  yield path.join(REPOSITORY_ROOT, 'README.md')
  yield path.join(REPOSITORY_ROOT, 'deploy/local/README.md')
  yield path.join(REPOSITORY_ROOT, 'deploy/local/compose.yaml')
  yield* walk(path.join(REPOSITORY_ROOT, 'web/src/main'))
}

test('test intent: production scopes contain no unallowed credential environment', () => {
  const offenders = []
  for (const file of productionFiles()) {
    const matches = readFileSync(file, 'utf8').match(NON_ALLOWED_CREDENTIAL)
    if (matches) {
      offenders.push(`${path.relative(REPOSITORY_ROOT, file)}: ${matches}`)
    }
  }
  assert.deepEqual(offenders, [])
})

test('test intent: the app-side synchronizer and its configuration stay absent', () => {
  const javaRoot = path.join(REPOSITORY_ROOT, 'web/src/main')
  const files = [...walk(javaRoot)]
  const classes = files.filter((file) => /^E2eProviderCredential.*\.java$/.test(path.basename(file)))
  const configured = files
    .filter((file) => readFileSync(file, 'utf8').includes('e2e-provider-sync'))
    .map((file) => path.relative(REPOSITORY_ROOT, file))

  assert.deepEqual(classes, [])
  assert.deepEqual(configured, [])
})

test('test intent: compose never passes the TEST_ environment', () => {
  const compose = readFileSync(path.join(REPOSITORY_ROOT, 'deploy/local/compose.yaml'), 'utf8')
  assert.ok(!compose.includes('TEST_'))
})

test('test intent: the sync script reads exactly the allowed credential environments', () => {
  const source = readFileSync(path.join(REPOSITORY_ROOT, SYNC_SCRIPT), 'utf8')
  const constants = new Map(
    [...source.matchAll(/^export const (\w+) = '([^']*)'$/gm)].map((match) => [match[1], match[2]]),
  )
  const reads = new Set(
    [...source.matchAll(/\benv\[(\w+)\]/g)]
      .map((match) => constants.get(match[1]))
      .filter((name) => name !== undefined && /^TEST_[A-Z0-9_]+_(?:BASE_URL|API_KEY)$/.test(name)),
  )

  assert.deepEqual(reads, ALLOWED_CREDENTIAL_ENVIRONMENTS)
  assert.ok(existsSync(path.join(REPOSITORY_ROOT, SYNC_SCRIPT)))
})
