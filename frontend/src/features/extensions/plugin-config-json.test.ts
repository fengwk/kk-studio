import { describe, expect, it } from 'vitest'
import {
  appendStrictJsonArrayItem,
  getNodeSafeValue,
  getNodeSlice,
  getNodeAtPath,
  isValidJsonIntegerLiteral,
  isValidJsonNumberLiteral,
  MAX_PLUGIN_VALUES_UTF8_BYTES,
  parseStrictJsonContainer,
  parseStrictJsonObject,
  removeStrictJsonNode,
  updateStrictJsonField,
  utf8ByteLength,
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
      const bigIntNode = getNodeAtPath(tree, ['bigInt'])!
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
    expect(isValidJsonIntegerLiteral('1e5')).toBe(false)
    expect(isValidJsonIntegerLiteral('1e0')).toBe(false)
    expect(isValidJsonIntegerLiteral('1e+3')).toBe(false)
    expect(isValidJsonIntegerLiteral('1e-0')).toBe(false)

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

  it('accepts only a single independent container with the requested root type', () => {
    expect(parseStrictJsonContainer('{}', 'object').ok).toBe(true)
    expect(parseStrictJsonContainer('[]', 'array').ok).toBe(true)
    for (const snippet of ['123', '{}', '[] ,"injected":1', '[1,]', '[{"":1,"":2}]']) {
      expect(parseStrictJsonContainer(snippet, 'array').ok).toBe(false)
    }
    for (const snippet of ['123', '[] ,"injected":1', '{"":1,"":2}']) {
      expect(updateStrictJsonField({
        rawJson: '{"items":[],"unchanged":9007199254740993}',
        path: ['items'],
        rawSnippetString: snippet,
      }).ok).toBe(false)
    }
  })
})

describe('plugin-config-json structural edits and byte budget', () => {
  /** 探针文档：长整数、带 scale 的小数、指数都在未改切片里，任何结构编辑都必须逐字节保留它们。 */
  const probe =
    '{"enabled":true,"seed":9007199254740993123456789,"ratio":1.230000,"exp":1e1000,' +
    '"workflows":[{"nodeId":"1","index":0},{"nodeId":"2","index":1},{"nodeId":"3","index":2}]}'

  function expectProbesIntact(json: string) {
    expect(json).toContain('9007199254740993123456789')
    expect(json).toContain('1.230000')
    expect(json).toContain('1e1000')
  }

  it('counts UTF-8 bytes, including multi-byte characters and escape overhead', () => {
    expect(MAX_PLUGIN_VALUES_UTF8_BYTES).toBe(64 * 1024)
    expect(utf8ByteLength('')).toBe(0)
    expect(utf8ByteLength('abc')).toBe(3)
    expect(utf8ByteLength('中')).toBe(3)
    // 原始换行是 1 个字节，进入 valuesJson 后必须写成 \n 两个字节。
    expect(utf8ByteLength('a\nb')).toBe(3)
    expect(utf8ByteLength(JSON.stringify('a\nb'))).toBe(6)
  })

  it('removes array items at any position while keeping every untouched byte', () => {
    const first = removeStrictJsonNode(probe, ['workflows', 0])
    expect(first.ok).toBe(true)
    if (first.ok) {
      expect(JSON.parse(first.newJson).workflows).toEqual([
        { nodeId: '2', index: 1 },
        { nodeId: '3', index: 2 },
      ])
      expectProbesIntact(first.newJson)
    }

    const middle = removeStrictJsonNode(probe, ['workflows', 1])
    expect(middle.ok).toBe(true)
    if (middle.ok) {
      expect(JSON.parse(middle.newJson).workflows).toEqual([
        { nodeId: '1', index: 0 },
        { nodeId: '3', index: 2 },
      ])
    }

    // 末位条目是 jsonc-parser modify 无法正确删除的边界，切片实现必须仍然产出合法 JSON。
    const last = removeStrictJsonNode(probe, ['workflows', 2])
    expect(last.ok).toBe(true)
    if (last.ok) {
      expect(JSON.parse(last.newJson).workflows).toEqual([
        { nodeId: '1', index: 0 },
        { nodeId: '2', index: 1 },
      ])
      expectProbesIntact(last.newJson)
    }
  })

  it('removes object properties, nested properties and the only item without corrupting the document', () => {
    const firstProp = removeStrictJsonNode(probe, ['enabled'])
    expect(firstProp.ok).toBe(true)
    if (firstProp.ok) {
      expect(Object.hasOwn(JSON.parse(firstProp.newJson), 'enabled')).toBe(false)
      expectProbesIntact(firstProp.newJson)
    }

    const nested = removeStrictJsonNode(probe, ['workflows', 0, 'index'])
    expect(nested.ok).toBe(true)
    if (nested.ok) {
      expect(JSON.parse(nested.newJson).workflows[0]).toEqual({ nodeId: '1' })
    }

    const single = removeStrictJsonNode('{"only":1}', ['only'])
    expect(single.ok).toBe(true)
    if (single.ok) {
      expect(single.newJson).toBe('{}')
    }

    const singleItem = removeStrictJsonNode('{"items":[9007199254740993]}', ['items', 0])
    expect(singleItem.ok).toBe(true)
    if (singleItem.ok) {
      expect(singleItem.newJson).toBe('{"items":[]}')
    }
  })

  it('keeps formatted documents valid on removal and treats missing targets as already satisfied', () => {
    const formatted =
      '{\n  "seed": 9007199254740993123456789,\n  "items": [\n    {"a": 1},\n    {"b": 2}\n  ]\n}'
    const removed = removeStrictJsonNode(formatted, ['items', 1])
    expect(removed.ok).toBe(true)
    if (removed.ok) {
      // 逐字节比对：删除后除目标切片外一个字符都没动。
      expect(removed.newJson).toBe(
        '{\n  "seed": 9007199254740993123456789,\n  "items": [\n    {"a": 1}\n  ]\n}',
      )
      // 删除只改目标切片：未改数字 token 保持原文（不是 9.007199254740993e+24）。
      expect(removed.newJson).toContain('9007199254740993123456789')
    }

    const missing = removeStrictJsonNode(probe, ['absent'])
    expect(missing.ok).toBe(true)
    if (missing.ok) {
      expect(missing.newJson).toBe(probe)
    }

    expect(removeStrictJsonNode(probe, []).ok).toBe(false)
    expect(removeStrictJsonNode('not json', ['a']).ok).toBe(false)
  })

  it('appends schema placeholders to existing, empty and missing arrays', () => {
    const appended = appendStrictJsonArrayItem({
      rawJson: probe,
      path: ['workflows'],
      value: { nodeId: '', index: 0 },
    })
    expect(appended.ok).toBe(true)
    if (appended.ok) {
      const workflows = JSON.parse(appended.newJson).workflows
      expect(workflows).toHaveLength(4)
      expect(workflows[3]).toEqual({ nodeId: '', index: 0 })
      expectProbesIntact(appended.newJson)
    }

    const empty = appendStrictJsonArrayItem({
      rawJson: '{"items":[],"seed":9007199254740993}',
      path: ['items'],
      value: { a: '' },
    })
    expect(empty.ok).toBe(true)
    if (empty.ok) {
      expect(empty.newJson).toBe('{"items":[{"a":""}],"seed":9007199254740993}')
    }

    const created = appendStrictJsonArrayItem({
      rawJson: '{"seed":1.230000}',
      path: ['items'],
      value: { a: '' },
    })
    expect(created.ok).toBe(true)
    if (created.ok) {
      expect(JSON.parse(created.newJson).items).toEqual([{ a: '' }])
      expect(created.newJson).toContain('1.230000')
    }

    expect(
      appendStrictJsonArrayItem({ rawJson: '{"items":1}', path: ['items'], value: {} }).ok,
    ).toBe(false)
    expect(
      appendStrictJsonArrayItem({ rawJson: probe, path: ['workflows'], value: undefined }).ok,
    ).toBe(false)
  })
})
