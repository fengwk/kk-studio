import { ApiError } from '@/shared/api/client'
import type { InstantTimestamp } from '@/shared/api/contracts/base'
import type {
  PluginAuthKindDTO,
  PluginAuthPrepareDTO,
  PluginConfigurationDTO,
  PluginDTO,
  PluginSecretProjectionDTO,
  PluginStatus,
} from '@/shared/api/contracts/ai-plugin'

function invalidPayload(detail: string): ApiError {
  return new ApiError(`Plugin API returned an invalid payload: ${detail}`)
}

function checkUnknownKeys(
  record: Record<string, unknown>,
  allowed: Set<string>,
  path: string,
): void {
  for (const key of Object.keys(record)) {
    if (!allowed.has(key)) {
      throw invalidPayload(`Unknown property at ${path}`)
    }
  }
}

function requireRecord(value: unknown, path: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw invalidPayload(`${path} must be an object`)
  }
  return value as Record<string, unknown>
}

function requireArray(value: unknown, path: string): unknown[] {
  if (!Array.isArray(value)) {
    throw invalidPayload(`${path} must be an array`)
  }
  return value
}

function requireString(value: unknown, path: string): string {
  if (typeof value !== 'string') {
    throw invalidPayload(`${path} must be a string`)
  }
  return value
}

function requireNonEmptyString(value: unknown, path: string): string {
  const text = requireString(value, path)
  if (text.length === 0) {
    throw invalidPayload(`${path} must be a non-empty string`)
  }
  return text
}

function requireNullableString(value: unknown, path: string): string | null {
  if (value === null || value === undefined) {
    return null
  }
  return requireString(value, path)
}

function requireNullableInstant(value: unknown, path: string): InstantTimestamp {
  if (value === null || value === undefined) {
    return null
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string' && Number.isFinite(Date.parse(value))) {
    return value
  }
  throw invalidPayload(`${path} must be epoch seconds or an ISO date-time string`)
}

const CANONICAL_DECIMAL = /^(?:0|[1-9]\d*)$/
const MAX_LONG = 9223372036854775807n

function requireCanonicalDecimalString(value: unknown, path: string): string {
  const text = requireString(value, path)
  if (!CANONICAL_DECIMAL.test(text)) {
    throw invalidPayload(`${path} must be a canonical non-negative decimal string`)
  }
  if (BigInt(text) > MAX_LONG) {
    throw invalidPayload(`${path} exceeds 64-bit signed integer range`)
  }
  return text
}

function requireNullableCanonicalDecimalString(value: unknown, path: string): string | null {
  if (value === null || value === undefined) {
    return null
  }
  return requireCanonicalDecimalString(value, path)
}

function requireBoolean(value: unknown, path: string): boolean {
  if (typeof value !== 'boolean') {
    throw invalidPayload(`${path} must be a boolean`)
  }
  return value
}

function requireStringArray(value: unknown, path: string): string[] {
  const arr = requireArray(value, path)
  return arr.map((item, index) => requireString(item, `${path}[${index}]`))
}

const PLUGIN_STATUSES: readonly PluginStatus[] = [
  'NOT_CONNECTED',
  'KEY_UNAVAILABLE',
  'CONNECTED',
  'REFRESH_FAILED',
  'REFRESH_UNCERTAIN',
  'REAUTH_REQUIRED',
]

function isPluginStatus(value: unknown): value is PluginStatus {
  return typeof value === 'string' && (PLUGIN_STATUSES as readonly string[]).includes(value)
}

const ALLOWED_AUTH_DEEP_LINK_KEYS = new Set(['type', 'regionCandidates'])
const ALLOWED_AUTH_STATIC_KEY_KEYS = new Set(['type', 'secretField'])

export function decodePluginAuthKindDTO(value: unknown, path = 'authKind'): PluginAuthKindDTO | null {
  if (value === null || value === undefined) {
    return null
  }
  const rec = requireRecord(value, path)
  const type = requireString(rec.type, `${path}.type`)
  if (type === 'DEEP_LINK') {
    checkUnknownKeys(rec, ALLOWED_AUTH_DEEP_LINK_KEYS, path)
    const rawCandidates = requireArray(rec.regionCandidates, `${path}.regionCandidates`)
    if (rawCandidates.length === 0) {
      throw invalidPayload(`${path}.regionCandidates must be a non-empty array`)
    }
    const regionCandidates = rawCandidates.map((c, i) =>
      requireNonEmptyString(c, `${path}.regionCandidates[${i}]`),
    )
    return {
      type: 'DEEP_LINK',
      regionCandidates,
    }
  }
  if (type === 'STATIC_KEY') {
    checkUnknownKeys(rec, ALLOWED_AUTH_STATIC_KEY_KEYS, path)
    const secretField = requireNonEmptyString(rec.secretField, `${path}.secretField`)
    return {
      type: 'STATIC_KEY',
      secretField,
    }
  }
  throw invalidPayload(`${path}.type must be DEEP_LINK or STATIC_KEY`)
}

const ALLOWED_SECRET_PROJECTION_KEYS = new Set(['description', 'configured'])

export function decodePluginSecretProjectionDTO(
  value: unknown,
  path = 'secrets',
): PluginSecretProjectionDTO {
  const rec = requireRecord(value, path)
  checkUnknownKeys(rec, ALLOWED_SECRET_PROJECTION_KEYS, path)
  const description = requireString(rec.description, `${path}.description`)
  const configured = requireBoolean(rec.configured, `${path}.configured`)
  return { description, configured }
}

const ALLOWED_CONFIG_KEYS = new Set([
  'revision',
  'valuesJson',
  'valuesSchema',
  'defaultsJson',
  'secrets',
])

export function decodePluginConfigurationDTO(
  value: unknown,
  path = 'configuration',
): PluginConfigurationDTO {
  const rec = requireRecord(value, path)
  checkUnknownKeys(rec, ALLOWED_CONFIG_KEYS, path)
  const revision = requireNullableCanonicalDecimalString(rec.revision, `${path}.revision`)
  const valuesJson = requireString(rec.valuesJson, `${path}.valuesJson`)
  const valuesSchema = requireString(rec.valuesSchema, `${path}.valuesSchema`)
  const defaultsJson = requireString(rec.defaultsJson, `${path}.defaultsJson`)
  const secretsRecord = requireRecord(rec.secrets, `${path}.secrets`)

  const secrets: Record<string, PluginSecretProjectionDTO> = Object.fromEntries(
    Object.entries(secretsRecord).map(([key, val], index) => [
      key,
      decodePluginSecretProjectionDTO(val, `${path}.secrets[${index}]`),
    ]),
  )

  return {
    revision,
    valuesJson,
    valuesSchema,
    defaultsJson,
    secrets,
  }
}

const ALLOWED_PLUGIN_KEYS = new Set([
  'pluginId',
  'name',
  'version',
  'authKind',
  'status',
  'region',
  'expiresAt',
  'nextRefreshAt',
  'lastRefreshedAt',
  'lastRefreshError',
  'configuration',
  'desiredEpoch',
  'appliedEpoch',
  'desiredRevision',
  'appliedRevision',
  'connectionCheckSupported',
  'harnessToolNames',
  'canvasFunctionNames',
  'applicationError',
])

export function decodePluginDTO(value: unknown, path = 'plugin'): PluginDTO {
  const rec = requireRecord(value, path)
  checkUnknownKeys(rec, ALLOWED_PLUGIN_KEYS, path)
  const pluginId = requireNonEmptyString(rec.pluginId, `${path}.pluginId`)
  const name = requireNonEmptyString(rec.name, `${path}.name`)
  const version = requireNonEmptyString(rec.version, `${path}.version`)
  const authKind = decodePluginAuthKindDTO(rec.authKind, `${path}.authKind`)

  let status: PluginStatus | null = null
  if (rec.status !== null && rec.status !== undefined) {
    if (!isPluginStatus(rec.status)) {
      throw invalidPayload(`${path}.status must be a valid PluginStatus or null`)
    }
    status = rec.status
  }

  const region = requireNullableString(rec.region, `${path}.region`)
  const expiresAt = requireNullableInstant(rec.expiresAt, `${path}.expiresAt`)
  const nextRefreshAt = requireNullableInstant(rec.nextRefreshAt, `${path}.nextRefreshAt`)
  const lastRefreshedAt = requireNullableInstant(rec.lastRefreshedAt, `${path}.lastRefreshedAt`)
  const lastRefreshError = requireNullableString(rec.lastRefreshError, `${path}.lastRefreshError`)
  const configuration = decodePluginConfigurationDTO(rec.configuration, `${path}.configuration`)
  const desiredEpoch = requireCanonicalDecimalString(rec.desiredEpoch, `${path}.desiredEpoch`)
  const appliedEpoch = requireNullableCanonicalDecimalString(rec.appliedEpoch, `${path}.appliedEpoch`)
  const desiredRevision = requireCanonicalDecimalString(rec.desiredRevision, `${path}.desiredRevision`)
  const appliedRevision = requireNullableCanonicalDecimalString(rec.appliedRevision, `${path}.appliedRevision`)
  const connectionCheckSupported = requireBoolean(
    rec.connectionCheckSupported,
    `${path}.connectionCheckSupported`,
  )
  const harnessToolNames = requireStringArray(rec.harnessToolNames, `${path}.harnessToolNames`)
  const canvasFunctionNames = requireStringArray(rec.canvasFunctionNames, `${path}.canvasFunctionNames`)
  const applicationError = requireNullableString(rec.applicationError, `${path}.applicationError`)

  // 严格闭合语义校验
  if (authKind === null) {
    if (status !== null) {
      throw invalidPayload(`${path}.status must be null when authKind is null`)
    }
    if (
      region !== null ||
      expiresAt !== null ||
      nextRefreshAt !== null ||
      lastRefreshedAt !== null ||
      lastRefreshError !== null
    ) {
      throw invalidPayload(`${path} token/refresh metadata must be null when authKind is null`)
    }
  } else if (authKind.type === 'STATIC_KEY') {
    if (status === null) {
      throw invalidPayload(`${path}.status must not be null for STATIC_KEY authKind`)
    }
    if (
      region !== null ||
      expiresAt !== null ||
      nextRefreshAt !== null ||
      lastRefreshedAt !== null
    ) {
      throw invalidPayload(`${path} token/refresh metadata must be null for STATIC_KEY authKind`)
    }
    if (!Object.hasOwn(configuration.secrets, authKind.secretField)) {
      throw invalidPayload(
        `${path}.configuration.secrets must contain the declared static key field`,
      )
    }
  } else if (authKind.type === 'DEEP_LINK') {
    if (status === null) {
      throw invalidPayload(`${path}.status must not be null for DEEP_LINK authKind`)
    }
    if (region !== null && !authKind.regionCandidates.includes(region)) {
      throw invalidPayload(`${path}.region must be one of regionCandidates`)
    }
  }

  return {
    pluginId,
    name,
    version,
    authKind,
    status,
    region,
    expiresAt,
    nextRefreshAt,
    lastRefreshedAt,
    lastRefreshError,
    configuration,
    desiredEpoch,
    appliedEpoch,
    desiredRevision,
    appliedRevision,
    connectionCheckSupported,
    harnessToolNames,
    canvasFunctionNames,
    applicationError,
  }
}

export function decodePluginList(value: unknown, path = 'plugins'): PluginDTO[] {
  const arr = requireArray(value, path)
  return arr.map((item, index) => decodePluginDTO(item, `${path}[${index}]`))
}

const ALLOWED_AUTH_PREPARE_KEYS = new Set(['authAttemptId', 'loginUrl'])

export function decodePluginAuthPrepareDTO(
  value: unknown,
  path = 'authPrepare',
): PluginAuthPrepareDTO {
  const rec = requireRecord(value, path)
  checkUnknownKeys(rec, ALLOWED_AUTH_PREPARE_KEYS, path)
  const authAttemptId = requireNonEmptyString(rec.authAttemptId, `${path}.authAttemptId`)
  const loginUrl = requireNonEmptyString(rec.loginUrl, `${path}.loginUrl`)
  return { authAttemptId, loginUrl }
}
