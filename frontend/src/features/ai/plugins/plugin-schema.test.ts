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

  it('rejects non-object root or malformed JSON', () => {
    expect(parsePluginValuesSchema('not json').ok).toBe(false)
    expect(parsePluginValuesSchema('').ok).toBe(false)
    expect(parsePluginValuesSchema('   ').ok).toBe(false)
    expect(parsePluginValuesSchema('""').ok).toBe(false)
    expect(parsePluginValuesSchema('{"type": "string"}').ok).toBe(false)
  })

  it('rejects invalid schema structure inside elements', () => {
    // Array missing items
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: {
            list: { type: 'array' },
          },
        }),
      ).ok,
    ).toBe(false)

    // Enum not an array
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: {
            choice: { type: 'string', enum: 'not-array' },
          },
        }),
      ).ok,
    ).toBe(false)

    // Unsupported schema type
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: {
            unknownType: { type: 'custom_type' },
          },
        }),
      ).ok,
    ).toBe(false)

    // Properties not an object
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: 'invalid',
        }),
      ).ok,
    ).toBe(false)

    // Required not an array
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          required: 'invalid',
        }),
      ).ok,
    ).toBe(false)

    // Element not an object
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: {
            bad: 123,
          },
        }),
      ).ok,
    ).toBe(false)

    // Element missing type
    expect(
      parsePluginValuesSchema(
        JSON.stringify({
          type: 'object',
          properties: {
            bad: {},
          },
        }),
      ).ok,
    ).toBe(false)
  })
})
