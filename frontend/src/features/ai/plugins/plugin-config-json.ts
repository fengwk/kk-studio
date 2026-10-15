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

export type StrictJsonParseResult =
  | { ok: true; tree: Node }
  | { ok: false; error: string }

export type StrictJsonUpdateResult =
  | { ok: true; newJson: string }
  | { ok: false; error: string }

/**
 * 递归检查对象属性是否存在重复键。
 * 返回具体的重复键名（可能为 ""），若无重复返回 null。
 */
function findDuplicateKeyInNode(node: Node): string | null {
  if (node.type === 'object' && node.children) {
    const seenKeys = new Set<string>()
    for (const child of node.children) {
      if (child.type === 'property' && child.children && child.children.length > 0) {
        const keyNode = child.children[0]
        const key = keyNode.value as string
        if (seenKeys.has(key)) {
          return key
        }
        seenKeys.add(key)
        // 递归检查属性值
        if (child.children.length > 1) {
          const dup = findDuplicateKeyInNode(child.children[1])
          if (dup !== null) return dup
        }
      }
    }
  } else if (node.type === 'array' && node.children) {
    for (const item of node.children) {
      const dup = findDuplicateKeyInNode(item)
      if (dup !== null) return dup
    }
  }
  return null
}

/**
 * 严格解析 JSON 文本：
 * - 禁止注释 (disallowComments: true)
 * - 禁止尾随逗号 (allowTrailingComma: false)
 * - 禁止空内容 (allowEmptyContent: false)
 * - 根节点必须是单个对象或数组，可指定所需类型
 * - 递归拒绝重复键（包含空键 "" 和转义等价键）
 */
export function parseStrictJsonContainer(
  text: string,
  expectedType?: 'object' | 'array',
): StrictJsonParseResult {
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

  if (
    (expectedType !== undefined && tree.type !== expectedType) ||
    (tree.type !== 'object' && tree.type !== 'array')
  ) {
    return { ok: false, error: `Root JSON value must be ${expectedType ?? 'an object or array'}` }
  }

  const duplicateKey = findDuplicateKeyInNode(tree)
  if (duplicateKey !== null) {
    return { ok: false, error: `Duplicate key detected: "${duplicateKey}"` }
  }

  return { ok: true, tree }
}

export function parseStrictJsonObject(text: string): StrictJsonParseResult {
  return parseStrictJsonContainer(text, 'object')
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
 * 校验 JSON integer token；小数或指数 token 不是 Jackson 的 integral number。
 */
export function isValidJsonIntegerLiteral(val: string): boolean {
  return /^-?(?:0|[1-9]\d*)$/.test(val.trim())
}

/**
 * 修改 JSON 对象中的标量、原始数值或复杂 rawSnippet 片段。
 * 保持未修改属性的原字节和数字精度。
 */
export function updateStrictJsonField({
  rawJson,
  path,
  value,
  rawNumberString,
  rawSnippetString,
}: {
  rawJson: string
  path: JSONPath
  value?: unknown
  rawNumberString?: string
  rawSnippetString?: string
}): StrictJsonUpdateResult {
  const parseResult = parseStrictJsonObject(rawJson)
  if (!parseResult.ok) {
    return { ok: false, error: parseResult.error }
  }

  // 1. 如果传了 rawNumberString，需要保留原数字字面量
  if (rawNumberString !== undefined) {
    const trimmed = rawNumberString.trim()
    if (!isValidJsonNumberLiteral(trimmed)) {
      return { ok: false, error: `Invalid JSON number literal: "${trimmed}"` }
    }
    return replaceOrInsertRawSlice(rawJson, parseResult.tree, path, trimmed, 0)
  }

  // 2. 如果传了 rawSnippetString（复杂容器数组或对象），直接替换原始文本切片
  if (rawSnippetString !== undefined) {
    const trimmed = rawSnippetString.trim()
    const snippet = parseStrictJsonContainer(trimmed)
    if (!snippet.ok) {
      return snippet
    }
    return replaceOrInsertRawSlice(rawJson, parseResult.tree, path, trimmed, null)
  }

  // 3. 普通值（boolean, string, null）通过 jsonc-parser modify/applyEdits 更改
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

function replaceOrInsertRawSlice(
  rawJson: string,
  tree: Node,
  path: JSONPath,
  rawReplacement: string,
  placeholder: unknown,
): StrictJsonUpdateResult {
  const existingNode = findNodeAtLocation(tree, path)
  if (existingNode) {
    const nextJson =
      rawJson.slice(0, existingNode.offset) +
      rawReplacement +
      rawJson.slice(existingNode.offset + existingNode.length)
    const verify = parseStrictJsonObject(nextJson)
    if (!verify.ok) {
      return { ok: false, error: verify.error }
    }
    return { ok: true, newJson: nextJson }
  }

  // 字段尚不存在，先用占位值 modify 插入，再将其切片替换为 rawReplacement
  try {
    const edits = modify(rawJson, path, placeholder, {
      formattingOptions: DEFAULT_FORMATTING,
    })
    const intermediate = applyEdits(rawJson, edits)
    const repParsed = parseStrictJsonObject(intermediate)
    if (!repParsed.ok) {
      return { ok: false, error: repParsed.error }
    }
    const insertedNode = findNodeAtLocation(repParsed.tree, path)
    if (!insertedNode) {
      return { ok: false, error: 'Failed to locate inserted node' }
    }
    const finalJson =
      intermediate.slice(0, insertedNode.offset) +
      rawReplacement +
      intermediate.slice(insertedNode.offset + insertedNode.length)
    const verify = parseStrictJsonObject(finalJson)
    if (!verify.ok) {
      return { ok: false, error: verify.error }
    }
    return { ok: true, newJson: finalJson }
  } catch (err) {
    return {
      ok: false,
      error: err instanceof Error ? err.message : String(err),
    }
  }
}
