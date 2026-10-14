import { parseStrictJsonObject } from './plugin-config-json'

export type SupportedSchemaType =
  | 'string'
  | 'integer'
  | 'number'
  | 'boolean'
  | 'array'
  | 'object'

export type PluginSchemaElement =
  | {
      kind: 'string'
      description?: string
      enum?: readonly string[]
    }
  | {
      kind: 'integer'
      description?: string
    }
  | {
      kind: 'number'
      description?: string
    }
  | {
      kind: 'boolean'
      description?: string
    }
  | {
      kind: 'array'
      description?: string
      items: PluginSchemaElement
    }
  | {
      kind: 'object'
      description?: string
      properties: Record<string, PluginSchemaElement>
      required: readonly string[]
      additionalProperties: boolean
    }

export interface PluginRootSchema {
  description?: string
  properties: Record<string, PluginSchemaElement>
  required: readonly string[]
  additionalProperties: boolean
}

export type ParseSchemaResult =
  | {
      ok: true
      schema: PluginRootSchema
    }
  | {
      ok: false
      error: string
    }

const ALLOWED_OBJECT_KEYS = new Set([
  'type',
  'description',
  'properties',
  'required',
  'additionalProperties',
])

const ALLOWED_STRING_KEYS = new Set(['type', 'description', 'enum'])
const ALLOWED_SCALAR_KEYS = new Set(['type', 'description'])
const ALLOWED_ARRAY_KEYS = new Set(['type', 'description', 'items'])

function checkUnknownKeys(
  record: Record<string, unknown>,
  allowed: Set<string>,
  path: string,
): void {
  for (const key of Object.keys(record)) {
    if (!allowed.has(key)) {
      throw new Error(`Unsupported schema keyword "${key}" at ${path}`)
    }
  }
}

function parseElement(raw: unknown, path: string): PluginSchemaElement {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new Error(`Schema at ${path} must be an object`)
  }
  const record = raw as Record<string, unknown>
  const type = record.type
  if (typeof type !== 'string') {
    throw new Error(`Schema at ${path} must have a string "type"`)
  }

  // description 错型时严禁静默忽略
  let description: string | undefined
  if ('description' in record) {
    if (typeof record.description !== 'string') {
      throw new Error(`"description" at ${path} must be a string`)
    }
    description = record.description
  }

  switch (type) {
    case 'string': {
      checkUnknownKeys(record, ALLOWED_STRING_KEYS, path)

      let enumValues: string[] | undefined
      if ('enum' in record) {
        if (!Array.isArray(record.enum)) {
          throw new Error(`"enum" at ${path} must be an array of strings`)
        }
        if (record.enum.length === 0) {
          throw new Error(`"enum" at ${path} must not be empty`)
        }
        const seen = new Set<string>()
        for (let i = 0; i < record.enum.length; i++) {
          const item = record.enum[i]
          if (typeof item !== 'string') {
            throw new Error(`"enum" item at ${path}[${i}] must be a string`)
          }
          if (seen.has(item)) {
            throw new Error(`Duplicate enum value "${item}" at ${path}`)
          }
          seen.add(item)
        }
        enumValues = record.enum
      }
      return { kind: 'string', description, enum: enumValues }
    }
    case 'integer': {
      checkUnknownKeys(record, ALLOWED_SCALAR_KEYS, path)
      return { kind: 'integer', description }
    }
    case 'number': {
      checkUnknownKeys(record, ALLOWED_SCALAR_KEYS, path)
      return { kind: 'number', description }
    }
    case 'boolean': {
      checkUnknownKeys(record, ALLOWED_SCALAR_KEYS, path)
      return { kind: 'boolean', description }
    }
    case 'array': {
      checkUnknownKeys(record, ALLOWED_ARRAY_KEYS, path)
      if (!('items' in record)) {
        throw new Error(`Array schema at ${path} must declare "items"`)
      }
      const items = parseElement(record.items, `${path}.items`)
      return { kind: 'array', description, items }
    }
    case 'object': {
      checkUnknownKeys(record, ALLOWED_OBJECT_KEYS, path)

      if (!('properties' in record)) {
        throw new Error(`"properties" at ${path} is required`)
      }
      if (
        !record.properties ||
        typeof record.properties !== 'object' ||
        Array.isArray(record.properties)
      ) {
        throw new Error(`"properties" at ${path} must be an object`)
      }

      // 使用 Object.fromEntries 避免原型污染并保留合法字段名
      const entries: [string, PluginSchemaElement][] = []
      for (const [key, propVal] of Object.entries(
        record.properties as Record<string, unknown>,
      )) {
        entries.push([key, parseElement(propVal, `${path}.properties.${key}`)])
      }
      const properties: Record<string, PluginSchemaElement> = Object.fromEntries(entries)

      const required: string[] = []
      if ('required' in record) {
        if (!Array.isArray(record.required)) {
          throw new Error(`"required" at ${path} must be an array of strings`)
        }
        const seenReq = new Set<string>()
        for (let i = 0; i < record.required.length; i++) {
          const reqName = record.required[i]
          if (typeof reqName !== 'string') {
            throw new Error(`"required" item at ${path}[${i}] must be a string`)
          }
          if (seenReq.has(reqName)) {
            throw new Error(`Duplicate required property "${reqName}" at ${path}`)
          }
          if (!(reqName in properties)) {
            throw new Error(
              `Required property "${reqName}" at ${path} is not defined in properties`,
            )
          }
          seenReq.add(reqName)
          required.push(reqName)
        }
      }

      let additionalProperties = false
      if ('additionalProperties' in record) {
        if (typeof record.additionalProperties !== 'boolean') {
          throw new Error(`"additionalProperties" at ${path} must be a boolean`)
        }
        additionalProperties = record.additionalProperties
      }

      return {
        kind: 'object',
        description,
        properties,
        required,
        additionalProperties,
      }
    }
    default:
      throw new Error(`Unsupported schema type "${String(type)}" at ${path}`)
  }
}

/**
 * 严格解析受控配置 valuesSchema JSON 字符串。
 * 使用严格 parseStrictJsonObject 杜绝 duplicate keys、注释、尾随逗号。
 */
export function parsePluginValuesSchema(schemaJson: string): ParseSchemaResult {
  if (!schemaJson || schemaJson.trim().length === 0) {
    return { ok: false, error: 'valuesSchema must not be empty' }
  }

  // 用严格解析器拒绝 duplicate keys
  const strictCheck = parseStrictJsonObject(schemaJson)
  if (!strictCheck.ok) {
    return {
      ok: false,
      error: `Malformed schema JSON: ${strictCheck.error}`,
    }
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(schemaJson)
  } catch (err) {
    return {
      ok: false,
      error: `Malformed schema JSON: ${err instanceof Error ? err.message : String(err)}`,
    }
  }

  try {
    const root = parseElement(parsed, 'root')
    if (root.kind !== 'object') {
      return { ok: false, error: 'Root schema must be of type "object"' }
    }
    return {
      ok: true,
      schema: {
        description: root.description,
        properties: root.properties,
        required: root.required,
        additionalProperties: root.additionalProperties,
      },
    }
  } catch (err) {
    return {
      ok: false,
      error: err instanceof Error ? err.message : String(err),
    }
  }
}
