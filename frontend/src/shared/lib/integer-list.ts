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

export function splitTagInputDraft(value: unknown): {
  committed: number[]
  pending: string | null
} {
  if (!Array.isArray(value)) {
    return { committed: [], pending: null }
  }
  const committed: number[] = []
  let pending: string | null = null

  for (let i = 0; i < value.length; i++) {
    const item = value[i]
    if (typeof item === 'number') {
      committed.push(item)
    } else if (typeof item === 'string') {
      pending = item
    }
  }
  return { committed, pending }
}

export function validateIntegerListTokens(
  tokens: string[],
  existing: (number | string)[],
  options?: ValidateIntegerListOptions,
): { valid: number[]; error: IntegerListValidationError | null } {
  const valid: number[] = []
  const seen = new Set<number>()

  for (const item of existing) {
    if (typeof item === 'number' && Number.isSafeInteger(item)) {
      seen.add(item)
    } else if (typeof item === 'string') {
      const trimmed = item.trim()
      if (/^-?\d+$/u.test(trimmed)) {
        const num = Number(trimmed)
        if (Number.isSafeInteger(num)) {
          seen.add(num)
        }
      }
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
    if (!/^-?\d+$/u.test(trimmed)) {
      return {
        valid: [],
        error: new IntegerListValidationError('notInteger', {
          token: trimmed,
          min: options?.min,
          max: options?.max,
        }),
      }
    }
    const num = Number(trimmed)
    if (!Number.isSafeInteger(num)) {
      return {
        valid: [],
        error: new IntegerListValidationError('notInteger', {
          token: trimmed,
          min: options?.min,
          max: options?.max,
        }),
      }
    }
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
      if (!Number.isSafeInteger(item)) {
        throw new IntegerListValidationError('notInteger', { token: item })
      }
      num = item
    } else if (typeof item === 'string') {
      const trimmed = item.trim()
      if (trimmed === '' || !/^-?\d+$/u.test(trimmed)) {
        throw new IntegerListValidationError('notInteger', { token: item })
      }
      const parsed = Number(trimmed)
      if (!Number.isSafeInteger(parsed)) {
        throw new IntegerListValidationError('notInteger', { token: item })
      }
      num = parsed
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
