/**
 * Shared provider credential synchronization primitives for dev test workflows.
 *
 * This module provides internal building blocks shared across development verification
 * tools (such as E2E real provider credential synchronization and reliability matrix
 * credential synchronization). It is a private implementation module, not an external CLI
 * entry point.
 *
 * Every HTTP call goes through an injectable `request(url, { body, headers })` that resolves
 * to the parsed JSON response (GET without a body, PUT with one), so tests never need a server.
 */

import { isValidPort, splitUrl } from './url-split.mjs'

export const DEFAULT_MODEL_CALL_TIMEOUT_MILLIS = 1800000
export const DEFAULT_MODEL_CALL_IDLE_TIMEOUT_MILLIS = 120000

/** A caller supplied an invalid credential pair or base URL. */
export class ValidationError extends Error {
  constructor(message) {
    super(message)
    this.name = 'ValidationError'
  }
}

/** A non-2xx backend answer; only the status code ever reaches an error message. */
export class HttpError extends Error {
  constructor(status) {
    super(`HTTP ${status}`)
    this.name = 'HttpError'
    this.status = status
  }
}

/**
 * @typedef {object} ProviderSpec
 * @property {string} name
 * @property {string} providerType
 * @property {string} baseUrlEnv
 * @property {string} apiKeyEnv
 * @property {boolean} requiresV1Suffix
 * @property {string} defaultDescription
 */

/** Validate and normalize a provider base URL safely without leaking URL values. */
export function normalizeBaseUrl(baseUrlRaw, requiresV1Suffix, envName = '', providerName = '') {
  const context = (
    providerName && envName
      ? `for provider '${providerName}' (${envName})`
      : envName
        ? `in ${envName}`
        : providerName
          ? `for provider '${providerName}'`
          : ''
  ).trim()
  const prefix = context ? ` ${context}` : ''

  const cleaned = (baseUrlRaw || '').trim()
  if (!cleaned) {
    throw new ValidationError(`Base URL${prefix} must not be empty`)
  }

  const parsed = splitUrl(cleaned)
  if (!isValidPort(parsed.port)) {
    throw new ValidationError(`Invalid base URL format${prefix}`)
  }
  if (!['http', 'https'].includes(parsed.scheme.toLowerCase())) {
    throw new ValidationError(`Invalid base URL scheme${prefix}; must be http or https`)
  }
  if (!parsed.hostname) {
    throw new ValidationError(`Invalid base URL${prefix}; missing hostname`)
  }
  if (parsed.netloc.includes('@')) {
    throw new ValidationError(`Invalid base URL${prefix}; must not contain user credentials`)
  }
  if (parsed.query) {
    throw new ValidationError(`Invalid base URL${prefix}; must not contain query parameters`)
  }
  if (parsed.fragment) {
    throw new ValidationError(`Invalid base URL${prefix}; must not contain a fragment`)
  }

  let trimmed = cleaned.replace(/\/+$/, '')
  if (requiresV1Suffix && !trimmed.endsWith('/v1')) {
    trimmed += '/v1'
  }
  return trimmed
}

/** Default HTTP helper: GET without a body, PUT with one; resolves to the parsed JSON body. */
async function defaultRequest(url, { body, headers = {}, timeoutMillis = 30000 } = {}) {
  const response = await fetch(url, {
    method: body === undefined ? 'GET' : 'PUT',
    headers,
    body,
    signal: AbortSignal.timeout(timeoutMillis),
  })
  if (!response.ok) {
    throw new HttpError(response.status)
  }
  return response.json()
}

/** Fetch existing providers from the backend. */
export async function getProviders(backendUrl, request = defaultRequest) {
  const base = backendUrl.replace(/\/+$/, '')
  try {
    return await request(`${base}/api/providers?pageNumber=1&pageSize=50`)
  } catch {
    throw new Error('Failed to fetch existing providers from backend')
  }
}

/** Percent-encode every character outside the RFC 3986 unreserved set. */
function quoteAll(value) {
  return encodeURIComponent(value).replace(/[!'()*]/g, (char) => `%${char.charCodeAt(0).toString(16).toUpperCase()}`)
}

/** Update a single provider on the backend with URL-encoded provider path. */
export async function putProvider(backendUrl, providerName, payload, request = defaultRequest) {
  const base = backendUrl.replace(/\/+$/, '')
  try {
    return await request(`${base}/api/providers/${quoteAll(providerName)}`, {
      body: JSON.stringify(payload),
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    if (error instanceof HttpError) {
      throw new Error(`Failed to update provider ${providerName} (HTTP ${error.status})`)
    }
    throw new Error(`Failed to update provider ${providerName}`)
  }
}

/** Construct provider update payload while preserving current metadata and timeouts. */
export function buildPayload(baseUrl, apiKey, current, spec = null) {
  const providerType = spec !== null ? spec.providerType : current.providerType || 'openai_responses'
  const description = current.description || (spec !== null ? spec.defaultDescription : '')
  return {
    description,
    providerType,
    baseUrl,
    credential: apiKey,
    expectedVersion: String(current.version),
    modelCallTimeoutMillis: Math.trunc(Number(current.modelCallTimeoutMillis || DEFAULT_MODEL_CALL_TIMEOUT_MILLIS)),
    modelCallIdleTimeoutMillis: Math.trunc(
      Number(current.modelCallIdleTimeoutMillis || DEFAULT_MODEL_CALL_IDLE_TIMEOUT_MILLIS),
    ),
  }
}

/** Status lines keep the `True`/`False` spelling the shell tooling has always printed. */
const flag = (value) => (value ? 'True' : 'False')

/** Synchronize a fixed provider-spec set from an explicit environment snapshot. */
export async function syncProviderSpecs(backendUrl, specs, envValues, request = defaultRequest) {
  const base = backendUrl.replace(/\/+$/, '')

  // Step 1: Pre-HTTP validation of pairs and URL formats across all specs
  const toConfigure = []
  for (const spec of specs) {
    const rawBase = (envValues[spec.baseUrlEnv] || '').trim()
    const rawKey = (envValues[spec.apiKeyEnv] || '').trim()

    if (!rawBase && !rawKey) {
      continue
    }
    if (!rawBase || !rawKey) {
      throw new ValidationError(
        `Provider ${spec.name} credentials require both ${spec.baseUrlEnv} and ${spec.apiKeyEnv}`,
      )
    }
    const normalizedUrl = normalizeBaseUrl(rawBase, spec.requiresV1Suffix, spec.baseUrlEnv, spec.name)
    toConfigure.push([spec, normalizedUrl, rawKey])
  }

  // Step 2: Skip without HTTP when no provider credentials are provided
  if (toConfigure.length === 0) {
    return specs.map((spec) => `${spec.name}: skip (no credential pair)`)
  }

  // Step 3: Fetch existing providers once
  const listed = await getProviders(base, request)
  const rows = listed?.data?.results || []
  const rowMap = new Map(
    rows.filter((row) => row !== null && typeof row === 'object' && 'name' in row).map((row) => [row.name, row]),
  )

  // Step 4: Pre-PUT validation for all configured providers (must fail before any PUT)
  const validations = []
  for (const [spec, normalizedUrl, apiKey] of toConfigure) {
    const current = rowMap.get(spec.name)
    if (current === undefined) {
      throw new Error(`E2E database is missing seeded provider name=${spec.name}`)
    }
    if (current.version == null || !String(current.version).trim()) {
      throw new Error(`E2E database provider row for ${spec.name} is missing required version`)
    }
    if (current.providerType !== spec.providerType) {
      throw new Error(
        `E2E database provider row for ${spec.name} has unexpected providerType: ${current.providerType}`,
      )
    }
    validations.push([spec, buildPayload(normalizedUrl, apiKey, current, spec)])
  }

  // Step 5: Execute PUT requests
  const results = new Map()
  for (const [spec, payload] of validations) {
    const body = await putProvider(base, spec.name, payload, request)
    const data = body?.data || {}
    results.set(spec.name, `${spec.name}: configured=${flag(data.configured)} baseUrl_set=${flag(data.baseUrl)}`)
  }

  // Step 6: Return formatted lines in deterministic spec order
  return specs.map((spec) => results.get(spec.name) ?? `${spec.name}: skip (no credential pair)`)
}
