import { describe, expect, it } from 'vitest'
import { parsePluginValuesSchema } from './plugin-schema'

describe('plugin-schema parser', () => {
  it('parses valid controlled schemas correctly', () => {
    const validJson = JSON.stringify({
      type: 'object',
      description: 'Test plugin config',
      properties: {
        enabled: { type: 'boolean', description: 'Enable plugin' },
        retries: { type: 'integer', description: 'Retry count' },
        timeoutSeconds: { type: 'number', description: 'Timeout in seconds' },
        mode: {
          type: 'string',
          description: 'Execution mode',
          enum: ['FAST', 'THOROUGH'],
        },
        tags: {
          type: 'array',
          description: 'Tags list',
          items: { type: 'string' },
        },
        nested: {
          type: 'object',
          description: 'Nested settings',
          properties: {
            endpoint: { type: 'string' },
          },
          required: ['endpoint'],
          additionalProperties: false,
        },
      },
      required: ['enabled'],
      additionalProperties: false,
    })

    const result = parsePluginValuesSchema(validJson)
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.schema.description).toBe('Test plugin config')
      expect(result.schema.properties.enabled.kind).toBe('boolean')
      expect(result.schema.properties.mode.kind).toBe('string')
      if (result.schema.properties.mode.kind === 'string') {
        expect(result.schema.properties.mode.enum).toEqual(['FAST', 'THOROUGH'])
      }
      expect(result.schema.properties.nested.kind).toBe('object')
      expect(result.schema.required).toEqual(['enabled'])
    }
  })

  it('rejects unsupported schema keywords explicitly', () => {
    const withRef = JSON.stringify({
      type: 'object',
      $ref: '#/definitions/MyType',
      properties: {},
      required: [],
      additionalProperties: false,
    })
    const resRef = parsePluginValuesSchema(withRef)
    expect(resRef.ok).toBe(false)
    if (!resRef.ok) {
      expect(resRef.error).toContain('Unsupported schema keyword "$ref"')
    }

    const withOneOf = JSON.stringify({
      type: 'object',
      oneOf: [{ type: 'string' }],
      properties: {},
      required: [],
      additionalProperties: false,
    })
    const resOneOf = parsePluginValuesSchema(withOneOf)
    expect(resOneOf.ok).toBe(false)
    if (!resOneOf.ok) {
      expect(resOneOf.error).toContain('Unsupported schema keyword "oneOf"')
    }

    const withMin = JSON.stringify({
      type: 'object',
      properties: {
        port: { type: 'integer', minimum: 1024 },
      },
      required: [],
      additionalProperties: false,
    })
    const resMin = parsePluginValuesSchema(withMin)
    expect(resMin.ok).toBe(false)
    if (!resMin.ok) {
      expect(resMin.error).toContain('Unsupported schema keyword "minimum"')
    }
  })

  it('rejects non-object root or malformed JSON or duplicate keys', () => {
    expect(parsePluginValuesSchema('not json').ok).toBe(false)
    expect(parsePluginValuesSchema('').ok).toBe(false)
    expect(parsePluginValuesSchema('   ').ok).toBe(false)
    expect(parsePluginValuesSchema('""').ok).toBe(false)
    expect(parsePluginValuesSchema('{"type": "string"}').ok).toBe(false)

    // Duplicate schema keys rejected
    const dupKeyJson = '{"type": "object", "properties": {}, "properties": {}}'
    expect(parsePluginValuesSchema(dupKeyJson).ok).toBe(false)
  })

  it('strictly validates description, properties, required, enum, and additionalProperties', () => {
    const valid = {
      type: 'object',
      properties: { foo: { type: 'string' } },
      required: [],
      additionalProperties: false,
    }
    // 每个负例只改变一个边界，避免缺失的其它字段掩盖被测校验。
    for (const change of [
      { description: 12345 },
      { properties: undefined },
      { properties: 'invalid' },
      { required: undefined },
      { required: 'foo' },
      { required: [123] },
      { required: [''] },
      { required: ['  '] },
      { required: ['foo', 'foo'] },
      { required: ['bar'] },
      { required: ['constructor'] },
      { additionalProperties: undefined },
      { additionalProperties: 'yes' },
      { properties: { bad: null } },
      { properties: { bad: { type: 123 } } },
      { properties: { bad: { type: 'custom' } } },
      { properties: { list: { type: 'array' } } },
      { properties: { mode: { type: 'string', enum: [] } } },
      { properties: { mode: { type: 'string', enum: ['A', 'A'] } } },
      { properties: { mode: { type: 'string', enum: [''] } } },
      { properties: { mode: { type: 'string', enum: [' '] } } },
      { properties: { mode: { type: 'string', enum: [123] } } },
      { properties: { mode: { type: 'string', enum: 'A' } } },
      { properties: { nested: { type: 'object', properties: {}, additionalProperties: false } } },
    ]) {
      expect(parsePluginValuesSchema(JSON.stringify({ ...valid, ...change })).ok)
        .toBe(false)
    }
    const undeclared = parsePluginValuesSchema(
      JSON.stringify({ ...valid, required: ['bar'] }),
    )
    expect(undeclared.ok).toBe(false)
    if (!undeclared.ok) {
      expect(undeclared.error).toContain('not defined in properties')
    }
  })

  it('accepts null descriptions and declared special names without prototype inheritance', () => {
    const result = parsePluginValuesSchema(
      '{"type":"object","description":null,"properties":{"__proto__":{"type":"string"},'
        + '"constructor":{"type":"number"}},"required":["constructor"],"additionalProperties":true}',
    )
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.schema.description).toBeUndefined()
      expect(Object.hasOwn(result.schema.properties, '__proto__')).toBe(true)
      expect(result.schema.properties.constructor).toEqual({ kind: 'number', description: undefined })
      expect(result.schema.additionalProperties).toBe(true)
    }
  })
})
