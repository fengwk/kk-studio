/** Fixtures for the shared preview entry point and the dev app config loader tests. */

import { spawnSync } from 'node:child_process'
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

import { REPOSITORY_ROOT, cleanEnvironment, runBash, shellQuote } from './shell.mjs'

export const DEV_SCRIPT = path.join(REPOSITORY_ROOT, 'scripts/dev/app.sh')
export const SHARED_PREVIEW_SCRIPT = path.join(REPOSITORY_ROOT, 'scripts/dev/shared-preview.sh')
export const SHARED_PREVIEW_TEMPLATE = path.join(REPOSITORY_ROOT, 'scripts/dev/shared-preview.env.example')

// 外部数据面凭据：PREVIEW_MARKER 未设置时不校验完整性，标记生效后缺一不可。
export const REQUIRED_KEYS = [
  'KK_STUDIO_DB_URL',
  'KK_STUDIO_DB_USER',
  'KK_STUDIO_DB_PASSWORD',
  'KK_STUDIO_STORAGE_S3_ENDPOINT',
  'KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT',
  'KK_STUDIO_STORAGE_S3_REGION',
  'KK_STUDIO_STORAGE_S3_BUCKET',
  'KK_STUDIO_STORAGE_S3_ACCESS_KEY',
  'KK_STUDIO_STORAGE_S3_SECRET_KEY',
]
export const PREVIEW_MARKER = 'KK_STUDIO_SHARED_PREVIEW'

// 配置值统一带这个前缀，便于断言任何失败路径都没有回显过真实内容。
export const VALUE_PREFIX = 'fixture-value-'

const PREVIEW_INPUTS = [
  'SHARED_PREVIEW_ENV_FILE',
  PREVIEW_MARKER,
  'SPRING_PROFILES_ACTIVE',
  'SPRING_FLYWAY_ENABLED',
  'KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED',
  'BACKEND_PORT',
  'FRONTEND_PORT',
]

/** A syntactically valid owner-only config fixture with marker values. */
export function placeholderConfig({ omit = [], empty = [] } = {}) {
  const lines = ['# kk-studio shared-preview fixture', '']
  for (const key of REQUIRED_KEYS) {
    if (omit.includes(key)) {
      continue
    }
    let value
    if (empty.includes(key)) {
      value = ''
    } else if (key === 'KK_STUDIO_DB_URL') {
      value = `jdbc:postgresql://${VALUE_PREFIX}host/${VALUE_PREFIX}database`
    } else {
      value = `${VALUE_PREFIX}${key.toLowerCase()}`
    }
    lines.push(`${key}=${value}`)
  }
  return lines.join('\n') + '\n'
}

/** Write a config fixture with an explicit mode; the loader reads permissions. */
export function writeConfig(directory, content, { name = 'shared-preview.env', mode = 0o600 } = {}) {
  const file = path.join(directory, name)
  writeFileSync(file, content)
  chmodSync(file, mode)
  return file
}

/** Environment without any inherited preview or data-plane input. */
export function cleanPreviewEnvironment() {
  const environment = cleanEnvironment([
    'SHARED_PREVIEW_ENV_FILE',
    'KK_STUDIO_REPO_ROOT',
    PREVIEW_MARKER,
    'SPRING_PROFILES_ACTIVE',
    'SPRING_FLYWAY_ENABLED',
    'KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED',
    'BACKEND_PORT',
    'FRONTEND_PORT',
    'BACKEND_HOST',
    'FRONTEND_HOST',
  ])
  for (const key of REQUIRED_KEYS) {
    delete environment[key]
  }
  return environment
}

/** Source the real dev app script and run one snippet against its parsed configuration. */
export function sourceDevScript(snippet, overrides = {}) {
  return runBash(`source ${shellQuote(DEV_SCRIPT)}\n${snippet}`, {
    env: { ...cleanPreviewEnvironment(), ...overrides },
  })
}

/** Run the shared preview entry point with only the inputs a caller is allowed to provide. */
export function runSharedPreview(args, overrides = {}) {
  const environment = { PATH: process.env.PATH, HOME: process.env.HOME ?? '/' }
  for (const name of PREVIEW_INPUTS) {
    delete environment[name]
  }
  return spawnSync('bash', [SHARED_PREVIEW_SCRIPT, ...args], {
    cwd: REPOSITORY_ROOT,
    env: { ...environment, ...overrides },
    encoding: 'utf8',
  })
}

/** Run `body(directory)` with a fresh temporary directory that is removed afterwards. */
export function withTempDir(body) {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-dev-'))
  try {
    return body(directory)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
}
