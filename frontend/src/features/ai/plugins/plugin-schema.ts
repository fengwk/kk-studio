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
      enum?: string[]
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
      required: string[]
      additionalProperties: boolean
    }

export interface PluginRootSchema {
  description?: string
  properties: Record<string, PluginSchemaElement>
  required: string[]
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
): string | null {
  for (const key of Object.keys(record)) {
    if (!allowed.has(key)) {
      return `Unsupported schema keyword "${key}" at ${path}`
    }
  }
  return null
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

  const description =
    typeof record.description === 'string' ? record.description : undefined

  switch (type) {
    case 'string': {
      const err = checkUnknownKeys(record, ALLOWED_STRING_KEYS, path)
      if (err) throw new Error(err)

      let enumValues: string[] | undefined
      if ('enum' in record) {
        if (!Array.isArray(record.enum) || record.enum.some((e) => typeof e !== 'string')) {
          throw new Error(`"enum" at ${path} must be an array of strings`)
        }
        enumValues = record.enum as string[]
      }
      return { kind: 'string', description, enum: enumValues }
    }
    case 'integer': {
      const err = checkUnknownKeys(record, ALLOWED_SCALAR_KEYS, path)
      if (err) throw new Error(err)
      return { kind: 'integer', description }
    }
    case 'number': {
      const err = checkUnknownKeys(record, ALLOWED_SCALAR_KEYS, path)
      if (err) throw new Error(err)
      return { kind: 'number', description }
    }
    case 'boolean': {
      const err = checkUnknownKeys(record, ALLOWED_SCALAR_KEYS, path)
      if (err) throw new Error(err)
      return { kind: 'boolean', description }
    }
    case 'array': {
      const err = checkUnknownKeys(record, ALLOWED_ARRAY_KEYS, path)
      if (err) throw new Error(err)
      if (!record.items) {
        throw new Error(`Array schema at ${path} must declare "items"`)
      }
      const items = parseElement(record.items, `${path}.items`)
      return { kind: 'array', description, items }
    }
    case 'object': {
      const err = checkUnknownKeys(record, ALLOWED_OBJECT_KEYS, path)
      if (err) throw new Error(err)
      const properties: Record<string, PluginSchemaElement> = {}
      if (record.properties) {
        if (typeof record.properties !== 'object' || Array.isArray(record.properties)) {
          throw new Error(`"properties" at ${path} must be an object`)
        }
        for (const [key, propVal] of Object.entries(
          record.properties as Record<string, unknown>,
        )) {
          properties[key] = parseElement(propVal, `${path}.properties.${key}`)
        }
      }
      const required: string[] = []
      if (record.required) {
        if (!Array.isArray(record.required) || record.required.some((r) => typeof r !== 'string')) {
          throw new Error(`"required" at ${path} must be an array of strings`)
        }
        required.push(...(record.required as string[]))
      }
      const additionalProperties = record.additionalProperties === true
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
 */
export function parsePluginValuesSchema(schemaJson: string): ParseSchemaResult {
  if (!schemaJson || schemaJson.trim().length === 0) {
    return { ok: false, error: 'valuesSchema must not be empty' }
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
