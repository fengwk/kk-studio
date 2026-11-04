import {
  scanStrictJson,
  utf8ByteLength as strictUtf8ByteLength,
  type StrictJsonViolation,
} from '../strict-json.js'

export const MAX_MESSAGE_BYTES = 16 * 1024 * 1024
export const MAX_SAFE_BIGINT = 9007199254740991n

export const CANONICAL_UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const SHA256_PATTERN = /^[0-9a-f]{64}$/
const MEDIA_TYPE_PATTERN = /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/

export interface TextOptions {
  nonBlank?: boolean
  maxChars?: number
  maxBytes?: number
  pattern?: RegExp
}

/** Counts UTF-8 bytes and rejects unpaired surrogates instead of replacing them. */
function utf8ByteLength(value: string, name: string): number {
  return strictUtf8ByteLength(value, name)
}

export function requireText(value: unknown, name: string, options: TextOptions = {}): string {
  if (typeof value !== 'string') {
    throw new Error(`${name} must be a string`)
  }
  const bytes = utf8ByteLength(value, name)
  if (options.nonBlank && value.trim() === '') {
    throw new Error(`${name} must not be blank`)
  }
  if (options.maxChars !== undefined && value.length > options.maxChars) {
    throw new Error(`${name} exceeds ${options.maxChars} characters`)
  }
  if (options.maxBytes !== undefined && bytes > options.maxBytes) {
    throw new Error(`${name} exceeds ${options.maxBytes} UTF-8 bytes`)
  }
  if (options.pattern !== undefined && !options.pattern.test(value)) {
    throw new Error(`${name} has invalid syntax`)
  }
  return value
}

export function requireUuid(value: unknown, name: string): string {
  if (typeof value !== 'string' || !CANONICAL_UUID_PATTERN.test(value)) {
    throw new Error(`${name} must be a canonical lowercase UUID`)
  }
  return value
}

export function requireLowerSha256(value: unknown, name: string): string {
  if (typeof value !== 'string' || !SHA256_PATTERN.test(value)) {
    throw new Error(`${name} must be a lowercase SHA-256`)
  }
  return value
}

export function requireMediaType(value: unknown, name: string, maxBytes: number): string {
  return requireText(value, name, {
    nonBlank: true,
    maxBytes,
    pattern: MEDIA_TYPE_PATTERN,
  })
}

export function requireSafeLong(
  value: unknown,
  name: string,
  min: bigint,
  max: bigint,
): bigint {
  if (typeof value !== 'bigint') {
    throw new Error(`${name} must be an int64`)
  }
  if (value < min || value > max) {
    throw new Error(`${name} must be in ${min}..${max}`)
  }
  return value
}

/** Strictly validates opaque JSON object text and returns the original bytes as a string. */
export function parseJsonObject(value: unknown, name: string, maxBytes: number): string {
  const text = requireJsonText(value, name, maxBytes)
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error(`${name} must be valid JSON`)
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error(`${name} must be a JSON object`)
  }
  return text
}

/** Strictly validates opaque JSON text (including JSON null) and returns it unchanged. */
export function parseJsonValue(value: unknown, name: string, maxBytes: number): string {
  const text = requireJsonText(value, name, maxBytes)
  try {
    JSON.parse(text)
  } catch {
    throw new Error(`${name} must be valid JSON`)
  }
  return text
}

function requireJsonText(value: unknown, name: string, maxBytes: number): string {
  const text = requireText(value, name, { maxBytes })
  if (text.trim() === '') {
    throw new Error(`${name} is required`)
  }
  const violation = scanStrictJson(text)
  if (violation !== null) {
    throw new Error(`${name} ${violationMessage(violation)}`)
  }
  return text
}

function violationMessage(violation: StrictJsonViolation): string {
  switch (violation.kind) {
    case 'duplicate':
      return `contains duplicate field: ${violation.key}`
    case 'nesting':
      return `exceeds maximum nesting depth ${violation.limit}`
    case 'numberLength':
      return `number exceeds ${violation.limit} characters`
    case 'syntax':
      return 'must be valid JSON'
  }
}
