import {
  applyEdits,
  findNodeAtLocation,
  modify,
  parseTree,
  type JSONPath,
  type Node,
  type ParseError,
  printParseErrorCode,
} from 'jsonc-parser'

export interface StrictParseResult {
  ok: boolean
  tree?: Node
  error?: string
}

/**
 * 递归检查对象属性是否存在重复键。
 */
function findDuplicateKeyInNode(node: Node, text: string): string | null {
  if (node.type === 'object' && node.children) {
    const seenKeys = new Set<string>()
    for (const child of node.children) {
      if (child.type === 'property' && child.children && child.children.length > 0) {
        const keyNode = child.children[0]
        const rawKey =
          typeof keyNode.value === 'string'
            ? keyNode.value
            : text.slice(keyNode.offset, keyNode.offset + keyNode.length).replace(/^"|"$/g, '')
        if (seenKeys.has(rawKey)) {
          return rawKey
        }
        seenKeys.add(rawKey)
        // 递归检查属性值
        if (child.children.length > 1) {
          const dup = findDuplicateKeyInNode(child.children[1], text)
          if (dup) return dup
        }
      }
    }
  } else if (node.type === 'array' && node.children) {
    for (const item of node.children) {
      const dup = findDuplicateKeyInNode(item, text)
      if (dup) return dup
    }
  }
  return null
}

/**
 * 严格解析 JSON 文本：
 * - 禁止注释 (disallowComments: true)
 * - 禁止尾随逗号 (allowTrailingComma: false)
 * - 禁止空内容 (allowEmptyContent: false)
 * - 必须为严格 JSON object 根节点
 * - 递归拒绝重复键
 */
export function parseStrictJsonObject(text: string): StrictParseResult {
  if (!text || text.trim().length === 0) {
    return { ok: false, error: 'JSON text must not be empty' }
  }

  const errors: ParseError[] = []
  const tree = parseTree(text, errors, {
    disallowComments: true,
    allowTrailingComma: false,
    allowEmptyContent: false,
  })

  if (errors.length > 0) {
    const first = errors[0]
    return {
      ok: false,
      error: `Malformed JSON at offset ${first.offset}: ${printParseErrorCode(first.error)}`,
    }
  }

  if (!tree) {
    return { ok: false, error: 'JSON parsing failed to produce a valid AST' }
  }

  if (tree.type !== 'object') {
    return { ok: false, error: `Root JSON value must be an object, got ${tree.type}` }
  }

  const duplicateKey = findDuplicateKeyInNode(tree, text)
  if (duplicateKey) {
    return { ok: false, error: `Duplicate key detected: "${duplicateKey}"` }
  }

  return { ok: true, tree }
}

/**
 * 获取节点原始文本切片，避免 JS number 导致的精度与科学记数法坍塌。
 */
export function getNodeSlice(text: string, node: Node): string {
  return text.slice(node.offset, node.offset + node.length)
}

/**
 * 定位指定路径的节点。
 */
export function getNodeAtPath(tree: Node, path: JSONPath): Node | undefined {
  return findNodeAtLocation(tree, path)
}

/**
 * 从 AST 中安全读取未知值并收窄。
 */
export function getNodeSafeValue(node: Node, rawJson: string): unknown {
  if (node.type === 'number') {
    return getNodeSlice(rawJson, node)
  }
  if (node.type === 'boolean' || node.type === 'string' || node.type === 'null') {
    return node.value as unknown
  }
  if (node.type === 'array' || node.type === 'object') {
    return getNodeSlice(rawJson, node)
  }
  return node.value as unknown
}

const DEFAULT_FORMATTING = {
  insertSpaces: true,
  tabSize: 2,
  eol: '\n',
}

/**
 * 校验给定的字符串是否符合严格的 JSON 数字格式（支持大整数、高精度小数、指数）。
 */
export function isValidJsonNumberLiteral(val: string): boolean {
  return /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(val.trim())
}

/**
 * 修改 JSON 对象中的标量或原始数值文本。
 * 保持未修改属性的原字节和数字精度。
 */
export function updateStrictJsonField({
  rawJson,
  path,
  value,
  rawNumberString,
}: {
  rawJson: string
  path: JSONPath
  value?: unknown
  rawNumberString?: string
}): { ok: boolean; newJson?: string; error?: string } {
  const parseResult = parseStrictJsonObject(rawJson)
  if (!parseResult.ok || !parseResult.tree) {
    return { ok: false, error: parseResult.error }
  }

  // 1. 如果传了 rawNumberString，需要保留原数字字面量，不走 JS number 精度转换
  if (rawNumberString !== undefined) {
    const trimmed = rawNumberString.trim()
    if (!isValidJsonNumberLiteral(trimmed)) {
      return { ok: false, error: `Invalid JSON number literal: "${trimmed}"` }
    }

    const existingNode = findNodeAtLocation(parseResult.tree, path)
    if (existingNode) {
      // 原位替换该 token
      const nextJson =
        rawJson.slice(0, existingNode.offset) +
        trimmed +
        rawJson.slice(existingNode.offset + existingNode.length)
      const verify = parseStrictJsonObject(nextJson)
      if (!verify.ok) {
        return { ok: false, error: verify.error }
      }
      return { ok: true, newJson: nextJson }
    } else {
      // 字段尚不存在，先用占位数字 0 modify 插入，再将其替换为 rawNumberString
      const edits = modify(rawJson, path, 0, {
        formattingOptions: DEFAULT_FORMATTING,
      })
      const intermediate = applyEdits(rawJson, edits)
      const repParsed = parseStrictJsonObject(intermediate)
      if (!repParsed.ok || !repParsed.tree) {
        return { ok: false, error: repParsed.error }
      }
      const insertedNode = findNodeAtLocation(repParsed.tree, path)
      if (!insertedNode) {
        return { ok: false, error: 'Failed to locate inserted number node' }
      }
      const finalJson =
        intermediate.slice(0, insertedNode.offset) +
        trimmed +
        intermediate.slice(insertedNode.offset + insertedNode.length)
      const verify = parseStrictJsonObject(finalJson)
      if (!verify.ok) {
        return { ok: false, error: verify.error }
      }
      return { ok: true, newJson: finalJson }
    }
  }

  // 2. 普通值（boolean, string, null, 或复杂对象/数组）通过 modify/applyEdits 更改
  try {
    const edits = modify(rawJson, path, value, {
      formattingOptions: DEFAULT_FORMATTING,
    })
    const nextJson = applyEdits(rawJson, edits)
    const verify = parseStrictJsonObject(nextJson)
    if (!verify.ok) {
      return { ok: false, error: verify.error }
    }
    return { ok: true, newJson: nextJson }
  } catch (err) {
    return {
      ok: false,
      error: err instanceof Error ? err.message : String(err),
    }
  }
}
