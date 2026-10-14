import { describe, expect, it } from 'vitest'
import {
  getNodeSafeValue,
  getNodeSlice,
  isValidJsonNumberLiteral,
  parseStrictJsonObject,
  updateStrictJsonField,
} from './plugin-config-json'
import { findNodeAtLocation } from 'jsonc-parser'

describe('plugin-config-json', () => {
  const sampleWithBigNumbers =
    '{"bigInt":9007199254740993123456789,"decimal":1.230000,"exp":1e1000,"enabled":true}'

  it('parses valid strict JSON and retrieves raw tokens without precision loss', () => {
    const res = parseStrictJsonObject(sampleWithBigNumbers)
    expect(res.ok).toBe(true)
    expect(res.tree).toBeDefined()

    const tree = res.tree!
    const bigIntNode = findNodeAtLocation(tree, ['bigInt'])!
    expect(getNodeSlice(sampleWithBigNumbers, bigIntNode)).toBe('9007199254740993123456789')
    expect(getNodeSafeValue(bigIntNode, sampleWithBigNumbers)).toBe('9007199254740993123456789')

    const decimalNode = findNodeAtLocation(tree, ['decimal'])!
    expect(getNodeSlice(sampleWithBigNumbers, decimalNode)).toBe('1.230000')

    const expNode = findNodeAtLocation(tree, ['exp'])!
    expect(getNodeSlice(sampleWithBigNumbers, expNode)).toBe('1e1000')

    const enabledNode = findNodeAtLocation(tree, ['enabled'])!
    expect(getNodeSafeValue(enabledNode, sampleWithBigNumbers)).toBe(true)
  })

  it('strictly rejects comments, trailing commas, empty string, and non-object roots', () => {
    expect(parseStrictJsonObject('{"a": 1 /* comment */}').ok).toBe(false)
    expect(parseStrictJsonObject('{"a": 1,}').ok).toBe(false)
    expect(parseStrictJsonObject('').ok).toBe(false)
    expect(parseStrictJsonObject('   ').ok).toBe(false)
    expect(parseStrictJsonObject('["array"]').ok).toBe(false)
    expect(parseStrictJsonObject('"string"').ok).toBe(false)
    expect(parseStrictJsonObject('123').ok).toBe(false)
  })

  it('strictly detects duplicate keys in root and nested objects', () => {
    const dupRoot = '{"a": 1, "b": 2, "a": 3}'
    const resRoot = parseStrictJsonObject(dupRoot)
    expect(resRoot.ok).toBe(false)
    expect(resRoot.error).toContain('Duplicate key detected: "a"')

    const dupNested = '{"parent": {"child": 1, "child": 2}}'
    const resNested = parseStrictJsonObject(dupNested)
    expect(resNested.ok).toBe(false)
    expect(resNested.error).toContain('Duplicate key detected: "child"')

    const dupInArray = '{"list": [{"key": 1, "key": 2}]}'
    const resInArray = parseStrictJsonObject(dupInArray)
    expect(resInArray.ok).toBe(false)
    expect(resInArray.error).toContain('Duplicate key detected: "key"')
  })

  it('updates boolean and preserves all untouched number tokens verbatim', () => {
    const result = updateStrictJsonField({
      rawJson: sampleWithBigNumbers,
      path: ['enabled'],
      value: false,
    })

    expect(result.ok).toBe(true)
    const newJson = result.newJson!
    // Untouched tokens are 100% byte-for-byte preserved
    expect(newJson).toContain('9007199254740993123456789')
    expect(newJson).toContain('1.230000')
    expect(newJson).toContain('1e1000')
    expect(newJson).toContain('"enabled":false')
  })

  it('updates existing number token using rawNumberString without losing precision', () => {
    const result = updateStrictJsonField({
      rawJson: sampleWithBigNumbers,
      path: ['decimal'],
      rawNumberString: '9.8765432100000',
    })

    expect(result.ok).toBe(true)
    const newJson = result.newJson!
    expect(newJson).toContain('9.8765432100000')
    expect(newJson).toContain('9007199254740993123456789')
    expect(newJson).toContain('1e1000')
  })

  it('inserts new number field using rawNumberString without losing precision', () => {
    const result = updateStrictJsonField({
      rawJson: '{"enabled":true}',
      path: ['customBigInt'],
      rawNumberString: '123456789012345678901234567890',
    })

    expect(result.ok).toBe(true)
    const newJson = result.newJson!
    expect(newJson).toMatch(/"customBigInt":\s*123456789012345678901234567890/)
  })

  it('validates JSON number literals and rejects invalid ones', () => {
    expect(isValidJsonNumberLiteral('0')).toBe(true)
    expect(isValidJsonNumberLiteral('-0')).toBe(true)
    expect(isValidJsonNumberLiteral('12345')).toBe(true)
    expect(isValidJsonNumberLiteral('-9876')).toBe(true)
    expect(isValidJsonNumberLiteral('1.230000')).toBe(true)
    expect(isValidJsonNumberLiteral('1e1000')).toBe(true)
    expect(isValidJsonNumberLiteral('1.5e-3')).toBe(true)

    expect(isValidJsonNumberLiteral('01')).toBe(false)
    expect(isValidJsonNumberLiteral('1.')).toBe(false)
    expect(isValidJsonNumberLiteral('.5')).toBe(false)
    expect(isValidJsonNumberLiteral('abc')).toBe(false)
    expect(isValidJsonNumberLiteral('1e')).toBe(false)
    expect(isValidJsonNumberLiteral('')).toBe(false)
  })

  it('rejects update with invalid rawNumberString or invalid rawJson', () => {
    const result1 = updateStrictJsonField({
      rawJson: sampleWithBigNumbers,
      path: ['decimal'],
      rawNumberString: 'invalid-num',
    })
    expect(result1.ok).toBe(false)
    expect(result1.error).toContain('Invalid JSON number literal')

    const result2 = updateStrictJsonField({
      rawJson: 'not valid json',
      path: ['enabled'],
      value: false,
    })
    expect(result2.ok).toBe(false)

    // Modification failure on impossible path
    const result3 = updateStrictJsonField({
      rawJson: '{"a": 1}',
      path: ['a', 'b', 'c'],
      value: true,
    })
    expect(result3.ok).toBe(false)
  })

  it('tests getNodeSafeValue on various scalar and structured types', () => {
    const json = '{"text":"hello","flag":true,"nothing":null,"list":[1,2]}'
    const parsed = parseStrictJsonObject(json)
    expect(parsed.ok).toBe(true)
    const tree = parsed.tree!

    const textNode = findNodeAtLocation(tree, ['text'])!
    expect(getNodeSafeValue(textNode, json)).toBe('hello')

    const flagNode = findNodeAtLocation(tree, ['flag'])!
    expect(getNodeSafeValue(flagNode, json)).toBe(true)

    const nullNode = findNodeAtLocation(tree, ['nothing'])!
    expect(getNodeSafeValue(nullNode, json)).toBeNull()

    const listNode = findNodeAtLocation(tree, ['list'])!
    expect(getNodeSafeValue(listNode, json)).toBe('[1,2]')
  })
})
