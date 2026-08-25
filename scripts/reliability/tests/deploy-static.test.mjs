import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const REPOSITORY_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const FORBIDDEN_EXTERNAL_CACHE_SERVICE = ['re', 'dis'].join('')
const FORBIDDEN_EXTERNAL_CACHE_URL_ENVIRONMENT = [
  'KK_STUDIO_',
  FORBIDDEN_EXTERNAL_CACHE_SERVICE.toUpperCase(),
  '_URL',
].join('')
const COMPOSE_FILES = [
  'deploy/local/compose.yaml',
  'deploy/reliability/compose.yaml',
  'deploy/test/compose.yaml',
]

test('deployment compose files contain neither the unsupported PostgreSQL-only external cache service nor its app environment', () => {
  const serviceDeclaration = new RegExp(`^\\s{2}${FORBIDDEN_EXTERNAL_CACHE_SERVICE}:\\s*$`, 'im')
  for (const relativePath of COMPOSE_FILES) {
    const source = readFileSync(path.join(REPOSITORY_ROOT, relativePath), 'utf8')
    assert.doesNotMatch(source, serviceDeclaration, relativePath)
    assert.equal(source.includes(FORBIDDEN_EXTERNAL_CACHE_URL_ENVIRONMENT), false, relativePath)
  }
})

test('deployment, scripts, and current documentation contain no unsupported PostgreSQL-only external cache references', () => {
  const forbidden = new RegExp(FORBIDDEN_EXTERNAL_CACHE_SERVICE, 'i')
  const files = [
    ...walkFiles(path.join(REPOSITORY_ROOT, 'deploy')),
    ...walkFiles(path.join(REPOSITORY_ROOT, 'scripts')),
    ...walkFiles(path.join(REPOSITORY_ROOT, 'docs')),
    path.join(REPOSITORY_ROOT, 'README.md'),
  ]
  const offenders = files
    .filter(file => forbidden.test(readFileSync(file, 'utf8')))
    .map(file => path.relative(REPOSITORY_ROOT, file))

  assert.deepEqual(offenders, [])
})

function walkFiles(root) {
  return readdirSync(root, { withFileTypes: true }).flatMap(entry => {
    const target = path.join(root, entry.name)
    return entry.isDirectory() ? walkFiles(target) : [target]
  })
}
