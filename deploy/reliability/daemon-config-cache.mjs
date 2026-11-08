#!/usr/bin/env node
/**
 * 物化 Environment 卡片的 daemon.json 本地缓存。
 *
 * <p>Studio 数据库是唯一配置权威。本脚本按 UUID 或唯一 name 读取卡片；若卡片的 studioUrl 不是本次
 * Daemon 实际可达的 Studio origin，则用读取到的 CAS version 整体替换 note+daemonConfig 后，以
 * PUT 响应中的新 version 写缓存。卡片备注不进入 daemon.json，注册 token 也由调用方独立写入
 * daemon.token，绝不经过本脚本的参数、日志或 JSON。
 *
 * <p>参数只包含非机密值：--config、--studio-url，以及 --environment-id/--environment-name 二选一。
 * 输出文件固定为 {environmentId, version:string, daemonConfig:{studioUrl,bashExecutable,lsp,proxyUrl}}，
 * 写入同目录临时文件后原子 rename，显式 mode 0600。
 */

import { isAbsolute } from 'node:path'
import { renameSync, rmSync, writeFileSync } from 'node:fs'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const VERSION_PATTERN = /^(0|[1-9][0-9]*)$/
const CONFIG_FIELDS = new Set(['studioUrl', 'bashExecutable', 'lsp', 'proxyUrl'])
const LONG_MAX = 9223372036854775807n
const REQUEST_TIMEOUT_MILLIS = 10_000

class CacheError extends Error {}

function fail(message) {
  throw new CacheError(message)
}

function text(value, path) {
  if (typeof value !== 'string') {
    fail(`${path} must be a string`)
  }
  if (value.trim() === '') {
    fail(`${path} must be a nonblank string`)
  }
  return value
}

function optionalText(value, path) {
  if (value === undefined || value === null) {
    return null
  }
  return text(value, path)
}

function origin(value, path) {
  let url
  try {
    url = new URL(text(value, path))
  } catch {
    fail(`${path} must be an HTTP(S) origin`)
  }
  if (
    (url.protocol !== 'http:' && url.protocol !== 'https:')
    || url.username !== ''
    || url.password !== ''
    || url.search !== ''
    || url.hash !== ''
    || (url.pathname !== '' && url.pathname !== '/')
  ) {
    fail(`${path} must be an HTTP(S) origin without userinfo, path, query or fragment`)
  }
  return `${url.protocol}//${url.host}`
}

function canonicalConfig(raw) {
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) {
    fail('environment card daemonConfig must be an object')
  }
  for (const field of Object.keys(raw)) {
    if (!CONFIG_FIELDS.has(field)) {
      fail('environment card daemonConfig has an unknown field')
    }
  }
  const lsp = raw.lsp ?? null
  if (lsp !== null && (typeof lsp !== 'object' || Array.isArray(lsp))) {
    fail('environment card daemonConfig.lsp must be an object or null')
  }
  return {
    studioUrl: origin(raw.studioUrl, 'environment card daemonConfig.studioUrl'),
    bashExecutable: optionalText(raw.bashExecutable, 'environment card daemonConfig.bashExecutable'),
    lsp,
    proxyUrl: optionalText(raw.proxyUrl, 'environment card daemonConfig.proxyUrl'),
  }
}

function requireCard(card, { expectedId = '', expectedName = '' } = {}) {
  if (card === null || typeof card !== 'object' || Array.isArray(card)) {
    fail('environment card response must be an object')
  }
  if (typeof card.id !== 'string' || !UUID_PATTERN.test(card.id)) {
    fail('environment card id must be a canonical UUID')
  }
  if (expectedId !== '' && card.id !== expectedId) {
    fail('environment card id does not match --environment-id')
  }
  if (expectedName !== '' && card.name !== expectedName) {
    fail('environment card name does not match --environment-name')
  }
  if (typeof card.version !== 'string' || !VERSION_PATTERN.test(card.version)) {
    fail('environment card version must be a canonical non-negative decimal string')
  }
  if (BigInt(card.version) > LONG_MAX) {
    fail('environment card version must fit a signed 64-bit integer')
  }
  const note = card.note ?? null
  if (note !== null && typeof note !== 'string') {
    fail('environment card note must be a string or null')
  }
  return {
    id: card.id,
    name: card.name,
    version: card.version,
    note,
    daemonConfig: canonicalConfig(card.daemonConfig),
  }
}

function parseArguments(argv) {
  const parsed = {
    config: '',
    studioUrl: '',
    environmentId: '',
    environmentName: '',
  }
  const flags = new Map([
    ['--config', 'config'],
    ['--studio-url', 'studioUrl'],
    ['--environment-id', 'environmentId'],
    ['--environment-name', 'environmentName'],
  ])
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index]
    const key = flags.get(flag)
    if (key === undefined) {
      fail('unknown argument')
    }
    if (index + 1 >= argv.length) {
      fail(`missing value for ${flag}`)
    }
    if (parsed[key] !== '') {
      fail(`duplicate argument: ${flag}`)
    }
    parsed[key] = argv[index + 1]
  }
  if (!isAbsolute(parsed.config)) {
    fail('--config must be an absolute path')
  }
  origin(parsed.studioUrl, '--studio-url')
  if ((parsed.environmentId === '') === (parsed.environmentName === '')) {
    fail('exactly one of --environment-id or --environment-name is required')
  }
  if (parsed.environmentId !== '' && !UUID_PATTERN.test(parsed.environmentId)) {
    fail('--environment-id must be a canonical UUID')
  }
  if (parsed.environmentName !== '') {
    optionalText(parsed.environmentName, '--environment-name')
  }
  return parsed
}

async function request(base, path, options = {}) {
  let response
  try {
    response = await fetch(`${base}${path}`, {
      ...options,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MILLIS),
    })
  } catch {
    fail('environment card API is unreachable')
  }
  if (!response.ok) {
    fail(`environment card API returned HTTP ${response.status}`)
  }
  try {
    return await response.json()
  } catch {
    fail('environment card API returned invalid JSON')
  }
}

async function loadCard(base, { environmentId, environmentName }) {
  if (environmentId !== '') {
    return requireCard(
      await request(base, `/api/platform/environments/${environmentId}`),
      { expectedId: environmentId },
    )
  }
  const cards = await request(base, '/api/platform/environments')
  if (!Array.isArray(cards)) {
    fail('environment list response must be an array')
  }
  const matches = cards.filter((candidate) => candidate?.name === environmentName)
  if (matches.length !== 1) {
    fail('environment name must match exactly one card')
  }
  return requireCard(matches[0], { expectedName: environmentName })
}

async function materialize({ config, studioUrl, environmentId, environmentName }) {
  const base = origin(studioUrl, '--studio-url')
  let card = await loadCard(base, { environmentId, environmentName })
  if (card.daemonConfig.studioUrl !== base) {
    const updated = await request(base, `/api/platform/environments/${card.id}`, {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        expectedVersion: card.version,
        note: card.note,
        daemonConfig: { ...card.daemonConfig, studioUrl: base },
      }),
    })
    card = requireCard(updated, {
      expectedId: card.id,
      expectedName: environmentName,
    })
  }
  if (card.daemonConfig.studioUrl !== base) {
    fail('environment card daemonConfig.studioUrl was not updated')
  }

  const envelope = {
    environmentId: card.id,
    version: card.version,
    daemonConfig: card.daemonConfig,
  }
  const temporary = `${config}.${process.pid}.tmp`
  try {
    writeFileSync(temporary, `${JSON.stringify(envelope)}\n`, { mode: 0o600 })
    renameSync(temporary, config)
  } catch {
    try {
      rmSync(temporary, { force: true })
    } catch {}
    fail('cannot write daemon configuration cache')
  }
}

async function main() {
  try {
    await materialize(parseArguments(process.argv.slice(2)))
  } catch (error) {
    const message = error instanceof CacheError
      ? error.message
      : 'cannot prepare daemon configuration cache'
    process.stderr.write(`ERROR: ${message}\n`)
    process.exitCode = 1
  }
}

await main()
