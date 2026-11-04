import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'

import { REPO_ROOT as REPOSITORY_ROOT } from '../../../lib/repo-root.mjs'

const S3_INFRASTRUCTURE_COMPOSE_FILES = [
  ['deploy/local/compose.yaml', 'minio', 'minio-init', 'postgres', 'app'],
  ['deploy/test/compose.yaml', 'reliability-minio', 'reliability-minio-init', 'reliability-postgres', 'reliability-app'],
]

const REQUIRED_S3_APP_ENVIRONMENTS = [
  'KK_STUDIO_STORAGE_S3_ENDPOINT',
  'KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT',
  'KK_STUDIO_STORAGE_S3_REGION',
  'KK_STUDIO_STORAGE_S3_BUCKET',
  'KK_STUDIO_STORAGE_S3_ACCESS_KEY',
  'KK_STUDIO_STORAGE_S3_SECRET_KEY',
]

test('local and reliability compose files declare minio and minio-init with a one-shot init dependency', () => {
  // Test intent: guard mandatory S3 infrastructure topology in local and reliability stacks.
  // Both stacks must declare minio and minio-init, where minio-init waits for healthy minio,
  // is a one-shot job with no healthcheck, and the app waits for postgres, minio, and the
  // successful completion of minio-init.
  for (const [relativePath, minio, minioInit, postgres, app] of S3_INFRASTRUCTURE_COMPOSE_FILES) {
    const source = readFileSync(path.join(REPOSITORY_ROOT, relativePath), 'utf8')
    const minioSection = extractServiceSection(source, minio)
    assert.ok(minioSection.length > 0, `${relativePath} must declare minio service`)

    const minioInitSection = extractServiceSection(source, minioInit)
    assert.ok(minioInitSection.length > 0, `${relativePath} must declare minio-init service`)
    assert.match(
      minioInitSection,
      new RegExp(`${minio}:\\s*\\n\\s+condition:\\s*service_healthy`),
      `${relativePath}: minio-init must depend on healthy minio`,
    )
    // 一次性作业：没有 healthcheck，命令执行完即成功退出。
    assert.doesNotMatch(
      minioInitSection,
      /healthcheck:/,
      `${relativePath}: minio-init must stay a one-shot job without a healthcheck`,
    )
    assert.doesNotMatch(
      minioInitSection,
      /tail -f \/dev\/null/,
      `${relativePath}: minio-init must exit after initializing the bucket`,
    )

    const appSection = extractServiceSection(source, app)
    assert.ok(appSection.length > 0, `${relativePath} must declare app service`)
    assert.match(
      appSection,
      new RegExp(`${postgres}:\\s*\\n\\s+condition:\\s*service_healthy`),
      `${relativePath}: app must depend on healthy postgres`,
    )
    assert.match(
      appSection,
      new RegExp(`${minio}:\\s*\\n\\s+condition:\\s*service_healthy`),
      `${relativePath}: app must depend on healthy minio`,
    )
    assert.match(
      appSection,
      new RegExp(`${minioInit}:\\s*\\n\\s+condition:\\s*service_completed_successfully`),
      `${relativePath}: app must depend on the successful completion of minio-init`,
    )
  }
})

test('local and reliability app services declare complete six S3 storage configuration keys', () => {
  // Test intent: guard that local and reliability app containers receive all 6 required S3 storage env keys
  // without asserting fragile concrete credential values.
  for (const [relativePath, , , , app] of S3_INFRASTRUCTURE_COMPOSE_FILES) {
    const source = readFileSync(path.join(REPOSITORY_ROOT, relativePath), 'utf8')
    const appSection = extractServiceSection(source, app)
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
    path.join(REPOSITORY_ROOT, 'deploy/test/compose.yaml'),
    'utf8',
  )
  assert.match(
    extractServiceSection(local, 'app'),
    /KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT:\s*http:\/\/\$\{KK_STUDIO_S3_PUBLIC_HOST:-127\.0\.0\.1\}:\$\{KK_STUDIO_S3_PORT:-9000\}/,
  )
  assert.match(
    extractServiceSection(reliability, 'reliability-minio'),
    /127\.0\.0\.1:\$\{RELIABILITY_MINIO_PORT:-19002\}:9000/,
  )
  assert.match(
    extractServiceSection(reliability, 'reliability-app'),
    /KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT:\s*http:\/\/127\.0\.0\.1:\$\{RELIABILITY_MINIO_PORT:-19002\}/,
  )
  assert.doesNotMatch(
    extractServiceSection(reliability, 'reliability-app'),
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
