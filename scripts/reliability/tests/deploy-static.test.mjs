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

test('PostgreSQL-only compose files do not declare an external cache service or URL', () => {
  const serviceDeclaration = new RegExp(`^\\s{2}${FORBIDDEN_EXTERNAL_CACHE_SERVICE}:\\s*$`, 'im')
  for (const relativePath of COMPOSE_FILES) {
    const source = readFileSync(path.join(REPOSITORY_ROOT, relativePath), 'utf8')
    assert.doesNotMatch(source, serviceDeclaration, relativePath)
    assert.equal(source.includes(FORBIDDEN_EXTERNAL_CACHE_URL_ENVIRONMENT), false, relativePath)
  }
})

test('deployment, scripts, and current documentation contain no external cache references', () => {
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

const S3_INFRASTRUCTURE_COMPOSE_FILES = [
  'deploy/local/compose.yaml',
  'deploy/reliability/compose.yaml',
]

const REQUIRED_S3_APP_ENVIRONMENTS = [
  'KK_STUDIO_STORAGE_S3_ENDPOINT',
  'KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT',
  'KK_STUDIO_STORAGE_S3_REGION',
  'KK_STUDIO_STORAGE_S3_BUCKET',
  'KK_STUDIO_STORAGE_S3_ACCESS_KEY',
  'KK_STUDIO_STORAGE_S3_SECRET_KEY',
]

test('local and reliability compose files declare minio and minio-init services with healthy dependencies', () => {
  // Test intent: guard mandatory S3 infrastructure topology in local and reliability stacks.
  // Both stacks must declare minio and minio-init, where minio-init waits for healthy minio,
  // and the app container waits for postgres, minio, and minio-init to be healthy.
  for (const relativePath of S3_INFRASTRUCTURE_COMPOSE_FILES) {
    const source = readFileSync(path.join(REPOSITORY_ROOT, relativePath), 'utf8')
    const minioSection = extractServiceSection(source, 'minio')
    assert.ok(minioSection.length > 0, `${relativePath} must declare minio service`)

    const minioInitSection = extractServiceSection(source, 'minio-init')
    assert.ok(minioInitSection.length > 0, `${relativePath} must declare minio-init service`)
    assert.match(
      minioInitSection,
      /minio:\s*\n\s+condition:\s*service_healthy/,
      `${relativePath}: minio-init must depend on healthy minio`,
    )

    const appSection = extractServiceSection(source, 'app')
    assert.ok(appSection.length > 0, `${relativePath} must declare app service`)
    assert.match(
      appSection,
      /postgres:\s*\n\s+condition:\s*service_healthy/,
      `${relativePath}: app must depend on healthy postgres`,
    )
    assert.match(
      appSection,
      /minio:\s*\n\s+condition:\s*service_healthy/,
      `${relativePath}: app must depend on healthy minio`,
    )
    assert.match(
      appSection,
      /minio-init:\s*\n\s+condition:\s*service_healthy/,
      `${relativePath}: app must depend on healthy minio-init`,
    )
  }
})

test('local and reliability app services declare complete six S3 storage configuration keys', () => {
  // Test intent: guard that local and reliability app containers receive all 6 required S3 storage env keys
  // without asserting fragile concrete credential values.
  for (const relativePath of S3_INFRASTRUCTURE_COMPOSE_FILES) {
    const source = readFileSync(path.join(REPOSITORY_ROOT, relativePath), 'utf8')
    const appSection = extractServiceSection(source, 'app')
    assert.ok(appSection.length > 0, `${relativePath} must declare app service`)

    for (const envKey of REQUIRED_S3_APP_ENVIRONMENTS) {
      const pattern = new RegExp(`^\\s+${envKey}:`, 'm')
      assert.match(
        appSection,
        pattern,
        `${relativePath}: app environment must declare ${envKey}`,
      )
    }
  }
})

test('local and reliability advertise browser-reachable S3 endpoints', () => {
  // Test intent: prevent presigned URLs from advertising a Docker-only hostname or a bind address.
  const local = readFileSync(path.join(REPOSITORY_ROOT, 'deploy/local/compose.yaml'), 'utf8')
  const reliability = readFileSync(
    path.join(REPOSITORY_ROOT, 'deploy/reliability/compose.yaml'),
    'utf8',
  )
  assert.match(
    extractServiceSection(local, 'app'),
    /KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT:\s*http:\/\/\$\{KK_STUDIO_S3_PUBLIC_HOST:-127\.0\.0\.1\}:\$\{KK_STUDIO_S3_PORT:-9000\}/,
  )
  assert.match(
    extractServiceSection(reliability, 'minio'),
    /127\.0\.0\.1:\$\{RELIABILITY_MINIO_PORT:-19002\}:9000/,
  )
  assert.match(
    extractServiceSection(reliability, 'app'),
    /KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT:\s*http:\/\/127\.0\.0\.1:\$\{RELIABILITY_MINIO_PORT:-19002\}/,
  )
  assert.doesNotMatch(
    extractServiceSection(reliability, 'app'),
    /KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT:\s*http:\/\/minio:9000/,
  )
})

function extractServiceSection(composeContent, serviceName) {
  const lines = composeContent.split('\n')
  let inService = false
  const sectionLines = []
  for (const line of lines) {
    if (/^ {2}[a-zA-Z0-9_-]+:\s*$/.test(line)) {
      if (line.trim() === `${serviceName}:`) {
        inService = true
        sectionLines.push(line)
        continue
      } else if (inService) {
        break
      }
    }
    if (inService) {
      if (/^[a-zA-Z0-9_-]+:\s*$/.test(line)) {
        break
      }
      sectionLines.push(line)
    }
  }
  return sectionLines.join('\n')
}

function walkFiles(root) {
  return readdirSync(root, { withFileTypes: true }).flatMap(entry => {
    const target = path.join(root, entry.name)
    return entry.isDirectory() ? walkFiles(target) : [target]
  })
}
