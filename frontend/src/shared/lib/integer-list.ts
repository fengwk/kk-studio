/**
 * 整数列表与 HTTP 状态码名单校验通用工具。
 */

export const HTTP_STATUS_CODE_MIN = 400
export const HTTP_STATUS_CODE_MAX = 599

export type IntegerListErrorCode =
  | 'emptyToken'
  | 'notInteger'
  | 'outOfRange'
  | 'duplicate'

export class IntegerListValidationError extends Error {
  readonly code: IntegerListErrorCode
  readonly token?: string | number
  readonly min?: number
  readonly max?: number

  constructor(
    code: IntegerListErrorCode,
    options?: { token?: string | number; min?: number; max?: number; message?: string },
  ) {
    super(options?.message ?? `Integer list validation failed: ${code}`)
    this.name = 'IntegerListValidationError'
    this.code = code
    this.token = options?.token
    this.min = options?.min
    this.max = options?.max
  }
}

export function parseIntegerListTokens(raw: string): string[] {
  if (raw.includes(',')) {
    const parts = raw.split(',')
    const tokens: string[] = []
    for (const p of parts) {
      const pt = p.trim()
      if (pt === '') {
        tokens.push('')
      } else {
        tokens.push(...pt.split(/\s+/).filter(Boolean))
      }
    }
    return tokens
  }
  return raw.trim().split(/\s+/).filter(Boolean)
}

export interface ValidateIntegerListOptions {
  min?: number
  max?: number
}

export function validateIntegerListTokens(
  tokens: string[],
  existing: (number | string)[],
  options?: ValidateIntegerListOptions,
): { valid: number[]; error: IntegerListValidationError | null } {
  const valid: number[] = []
  const seen = new Set<number>()

  for (const item of existing) {
    if (typeof item === 'number') {
      seen.add(item)
    } else if (typeof item === 'string' && /^\d+$/u.test(item.trim())) {
      seen.add(parseInt(item.trim(), 10))
    }
  }

  for (const token of tokens) {
    const trimmed = token.trim()
    if (trimmed === '') {
      return {
        valid: [],
        error: new IntegerListValidationError('emptyToken', {
          token: trimmed,
          min: options?.min,
          max: options?.max,
        }),
      }
    }
    if (!/^\d+$/u.test(trimmed)) {
      return {
        valid: [],
        error: new IntegerListValidationError('notInteger', {
          token: trimmed,
          min: options?.min,
          max: options?.max,
        }),
      }
    }
    const num = parseInt(trimmed, 10)
    if (options?.min != null && num < options.min) {
      return {
        valid: [],
        error: new IntegerListValidationError('outOfRange', {
          token: trimmed,
          min: options?.min,
          max: options?.max,
        }),
      }
    }
    if (options?.max != null && num > options.max) {
      return {
        valid: [],
        error: new IntegerListValidationError('outOfRange', {
          token: trimmed,
          min: options?.min,
          max: options?.max,
        }),
      }
    }
    if (seen.has(num)) {
      return {
        valid: [],
        error: new IntegerListValidationError('duplicate', {
          token: trimmed,
          min: options?.min,
          max: options?.max,
        }),
      }
    }
    seen.add(num)
    valid.push(num)
  }

  return { valid, error: null }
}

export function assembleIntegerList(
  items: unknown,
  options?: ValidateIntegerListOptions,
): number[] {
  if (!Array.isArray(items)) {
    throw new IntegerListValidationError('notInteger')
  }
  const result: number[] = []
  const seen = new Set<number>()
  for (const item of items) {
    let num: number
    if (typeof item === 'number') {
      if (!Number.isInteger(item) || !Number.isFinite(item)) {
        throw new IntegerListValidationError('notInteger', { token: item })
      }
      num = item
    } else if (typeof item === 'string') {
      const trimmed = item.trim()
      if (trimmed === '' || !/^\d+$/u.test(trimmed)) {
        throw new IntegerListValidationError('notInteger', { token: item })
      }
      num = parseInt(trimmed, 10)
    } else {
      throw new IntegerListValidationError('notInteger')
    }

    if (options?.min != null && num < options.min) {
      throw new IntegerListValidationError('outOfRange', {
        token: num,
        min: options.min,
        max: options.max,
      })
    }
    if (options?.max != null && num > options.max) {
      throw new IntegerListValidationError('outOfRange', {
        token: num,
        min: options.min,
        max: options.max,
      })
    }

    if (seen.has(num)) {
      throw new IntegerListValidationError('duplicate', { token: num })
    }
    seen.add(num)
    result.push(num)
  }
  return result
}

export function assembleHttpStatusCodeList(items: unknown): number[] {
  return assembleIntegerList(items, {
    min: HTTP_STATUS_CODE_MIN,
    max: HTTP_STATUS_CODE_MAX,
  })
}
