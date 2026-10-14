import { describe, expect, it } from 'vitest'
import {
  getNodeSafeValue,
  getNodeSlice,
  isValidJsonIntegerLiteral,
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
    if (res.ok) {
      const tree = res.tree
      const bigIntNode = findNodeAtLocation(tree, ['bigInt'])!
      expect(getNodeSlice(sampleWithBigNumbers, bigIntNode)).toBe('9007199254740993123456789')
      expect(getNodeSafeValue(bigIntNode, sampleWithBigNumbers)).toBe('9007199254740993123456789')

      const decimalNode = findNodeAtLocation(tree, ['decimal'])!
      expect(getNodeSlice(sampleWithBigNumbers, decimalNode)).toBe('1.230000')

      const expNode = findNodeAtLocation(tree, ['exp'])!
      expect(getNodeSlice(sampleWithBigNumbers, expNode)).toBe('1e1000')

      const enabledNode = findNodeAtLocation(tree, ['enabled'])!
      expect(getNodeSafeValue(enabledNode, sampleWithBigNumbers)).toBe(true)
    }
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

  it('strictly detects duplicate keys including empty string key and escape equivalent keys', () => {
    // 1. Root duplicate
    const dupRoot = '{"a": 1, "b": 2, "a": 3}'
    const resRoot = parseStrictJsonObject(dupRoot)
    expect(resRoot.ok).toBe(false)
    if (!resRoot.ok) {
      expect(resRoot.error).toContain('Duplicate key detected: "a"')
    }

    // 2. Empty string key duplicate (previously bypassed with truthiness check)
    const dupEmptyKey = '{"": 1, "": 2}'
    const resEmptyKey = parseStrictJsonObject(dupEmptyKey)
    expect(resEmptyKey.ok).toBe(false)
    if (!resEmptyKey.ok) {
      expect(resEmptyKey.error).toContain('Duplicate key detected: ""')
    }

    // 3. Escape equivalent keys
    const dupEscaped = '{"foo": 1, "\\u0066oo": 2}'
    const resEscaped = parseStrictJsonObject(dupEscaped)
    expect(resEscaped.ok).toBe(false)
    if (!resEscaped.ok) {
      expect(resEscaped.error).toContain('Duplicate key detected: "foo"')
    }

    // 4. Nested duplicate empty key
    const dupNestedEmpty = '{"parent": {"": "first", "": "second"}}'
    const resNestedEmpty = parseStrictJsonObject(dupNestedEmpty)
    expect(resNestedEmpty.ok).toBe(false)
    if (!resNestedEmpty.ok) {
      expect(resNestedEmpty.error).toContain('Duplicate key detected: ""')
    }

    // 5. Duplicate in array item
    const dupInArray = '{"list": [{"": 1, "": 2}]}'
    const resInArray = parseStrictJsonObject(dupInArray)
    expect(resInArray.ok).toBe(false)
    if (!resInArray.ok) {
      expect(resInArray.error).toContain('Duplicate key detected: ""')
    }
  })

  it('updates boolean and preserves all untouched number tokens verbatim', () => {
    const result = updateStrictJsonField({
      rawJson: sampleWithBigNumbers,
      path: ['enabled'],
      value: false,
    })

    expect(result.ok).toBe(true)
    if (result.ok) {
      const newJson = result.newJson
      expect(newJson).toContain('9007199254740993123456789')
      expect(newJson).toContain('1.230000')
      expect(newJson).toContain('1e1000')
      expect(newJson).toContain('"enabled":false')
    }
  })

  it('updates existing number token using rawNumberString without losing precision', () => {
    const result = updateStrictJsonField({
      rawJson: sampleWithBigNumbers,
      path: ['decimal'],
      rawNumberString: '9.8765432100000',
    })

    expect(result.ok).toBe(true)
    if (result.ok) {
      const newJson = result.newJson
      expect(newJson).toContain('9.8765432100000')
      expect(newJson).toContain('9007199254740993123456789')
      expect(newJson).toContain('1e1000')
    }
  })

  it('inserts new number field using rawNumberString without losing precision', () => {
    const result = updateStrictJsonField({
      rawJson: '{"enabled":true}',
      path: ['customBigInt'],
      rawNumberString: '123456789012345678901234567890',
    })

    expect(result.ok).toBe(true)
    if (result.ok) {
      const newJson = result.newJson
      expect(newJson).toMatch(/"customBigInt":\s*123456789012345678901234567890/)
    }
  })

  it('updates complex containers (array and object) with rawSnippetString without precision loss', () => {
    const jsonWithContainers =
      '{"items":[10,20],"meta":{"version":1},"big":9007199254740993123456789}'

    // 1. Update array with big numbers snippet
    const arraySnippet = '[9007199254740993123456789,1.230000,1e1000]'
    const resArray = updateStrictJsonField({
      rawJson: jsonWithContainers,
      path: ['items'],
      rawSnippetString: arraySnippet,
    })
    expect(resArray.ok).toBe(true)
    if (resArray.ok) {
      expect(resArray.newJson).toContain('[9007199254740993123456789,1.230000,1e1000]')
      expect(resArray.newJson).toContain('9007199254740993123456789')
    }

    // 2. Insert new nested object with big numbers snippet
    const objectSnippet = '{"deepBig":999999999999999999999,"ratio":0.0000001}'
    const resObject = updateStrictJsonField({
      rawJson: jsonWithContainers,
      path: ['nestedConfig'],
      rawSnippetString: objectSnippet,
    })
    expect(resObject.ok).toBe(true)
    if (resObject.ok) {
      expect(resObject.newJson).toContain(objectSnippet)
    }
  })

  it('validates JSON number literals and integer literals', () => {
    expect(isValidJsonNumberLiteral('0')).toBe(true)
    expect(isValidJsonNumberLiteral('-0')).toBe(true)
    expect(isValidJsonNumberLiteral('-12345')).toBe(true)
    expect(isValidJsonNumberLiteral('1.230000')).toBe(true)
    expect(isValidJsonNumberLiteral('1e1000')).toBe(true)
    expect(isValidJsonNumberLiteral('-1.5e-3')).toBe(true)

    expect(isValidJsonNumberLiteral('01')).toBe(false)
    expect(isValidJsonNumberLiteral('1.')).toBe(false)
    expect(isValidJsonNumberLiteral('.5')).toBe(false)
    expect(isValidJsonNumberLiteral('abc')).toBe(false)
    expect(isValidJsonNumberLiteral('')).toBe(false)

    // Integer validation
    expect(isValidJsonIntegerLiteral('0')).toBe(true)
    expect(isValidJsonIntegerLiteral('-100')).toBe(true)
    expect(isValidJsonIntegerLiteral('9007199254740993123456789')).toBe(true)
    expect(isValidJsonIntegerLiteral('1e5')).toBe(true)

    expect(isValidJsonIntegerLiteral('1.23')).toBe(false)
    expect(isValidJsonIntegerLiteral('1.')).toBe(false)
    expect(isValidJsonIntegerLiteral('-1.0')).toBe(false)
    expect(isValidJsonIntegerLiteral('1e-2')).toBe(false)
  })

  it('rejects update with invalid rawNumberString, invalid rawSnippetString or malformed rawJson', () => {
    const result1 = updateStrictJsonField({
      rawJson: sampleWithBigNumbers,
      path: ['decimal'],
      rawNumberString: 'invalid-num',
    })
    expect(result1.ok).toBe(false)

    const result2 = updateStrictJsonField({
      rawJson: 'not valid json',
      path: ['enabled'],
      value: false,
    })
    expect(result2.ok).toBe(false)

    const result3 = updateStrictJsonField({
      rawJson: sampleWithBigNumbers,
      path: ['nested'],
      rawSnippetString: '{malformed: json}',
    })
    expect(result3.ok).toBe(false)

    const result4 = updateStrictJsonField({
      rawJson: sampleWithBigNumbers,
      path: ['decimal'],
      rawSnippetString: '{"duplicate":1,"duplicate":2}',
    })
    expect(result4.ok).toBe(false)

    const result5 = updateStrictJsonField({
      rawJson: '{"a": 1}',
      path: ['a', 'b', 'c'],
      value: true,
    })
    expect(result5.ok).toBe(false)

    const result6 = updateStrictJsonField({
      rawJson: '{"a": 1}',
      path: ['a', 'b', 'c'],
      rawNumberString: '123',
    })
    expect(result6.ok).toBe(false)
  })

  it('tests getNodeSafeValue on various scalar and structured types', () => {
    const json = '{"text":"hello","flag":true,"nothing":null,"list":[1,2]}'
    const parsed = parseStrictJsonObject(json)
    expect(parsed.ok).toBe(true)
    if (parsed.ok) {
      const tree = parsed.tree
      const textNode = findNodeAtLocation(tree, ['text'])!
      expect(getNodeSafeValue(textNode, json)).toBe('hello')

      const flagNode = findNodeAtLocation(tree, ['flag'])!
      expect(getNodeSafeValue(flagNode, json)).toBe(true)

      const nullNode = findNodeAtLocation(tree, ['nothing'])!
      expect(getNodeSafeValue(nullNode, json)).toBeNull()

      const listNode = findNodeAtLocation(tree, ['list'])!
      expect(getNodeSafeValue(listNode, json)).toBe('[1,2]')
    }
  })
})
