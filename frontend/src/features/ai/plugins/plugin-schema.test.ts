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
    })
    const resRef = parsePluginValuesSchema(withRef)
    expect(resRef.ok).toBe(false)
    if (!resRef.ok) {
      expect(resRef.error).toContain('Unsupported schema keyword "$ref"')
    }

    const withOneOf = JSON.stringify({
      type: 'object',
      oneOf: [{ type: 'string' }],
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
    // 1. description not string
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          description: 12345,
          properties: {},
        }),
      ).ok,
    ).toBe(false)

    // 2. properties missing
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
        }),
      ).ok,
    ).toBe(false)

    // 3. properties not object
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: 'invalid',
        }),
      ).ok,
    ).toBe(false)

    // 4. required contains duplicates
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: { foo: { type: 'string' } },
          required: ['foo', 'foo'],
        }),
      ).ok,
    ).toBe(false)

    // 5. required references undeclared property
    const resUndeclared = parsePluginValuesSchema(
      JSON.stringify({
        type: 'object',
        properties: { foo: { type: 'string' } },
        required: ['bar'],
      }),
    )
    expect(resUndeclared.ok).toBe(false)
    if (!resUndeclared.ok) {
      expect(resUndeclared.error).toContain('not defined in properties')
    }

    // 6. required not an array or has non-string
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: { foo: { type: 'string' } },
          required: 'foo',
        }),
      ).ok,
    ).toBe(false)
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: { foo: { type: 'string' } },
          required: [123],
        }),
      ).ok,
    ).toBe(false)

    // 7. enum is empty or has duplicates or non-strings
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: {
            mode: { type: 'string', enum: [] },
          },
        }),
      ).ok,
    ).toBe(false)

    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: {
            mode: { type: 'string', enum: ['A', 'A'] },
          },
        }),
      ).ok,
    ).toBe(false)

    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: {
            mode: { type: 'string', enum: 'A' },
          },
        }),
      ).ok,
    ).toBe(false)

    // 8. additionalProperties not boolean
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: {},
          additionalProperties: 'yes',
        }),
      ).ok,
    ).toBe(false)

    // 9. array missing items
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: { list: { type: 'array' } },
        }),
      ).ok,
    ).toBe(false)

    // 10. unknown element type
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: { bad: { type: 'custom' } },
        }),
      ).ok,
    ).toBe(false)
  })
})
