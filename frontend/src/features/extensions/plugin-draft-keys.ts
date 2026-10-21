import type { JSONPath } from 'jsonc-parser'

/**
 * 局部草稿的键就是 `JSON.stringify(path)`，因此这里只按同一条规范路径做判断，不引入第二套路径表示。
 * 键由本编辑器自己生成，调用方保证它可以被解析为路径。
 */
export function draftKeyPath(key: string): JSONPath {
  return JSON.parse(key) as JSONPath
}

/** 该草稿键是否位于 path 之下（含 path 自身）：用于判断某个字段/数组子树里是否存在未解决的草稿错误。 */
export function isDraftKeyUnderPath(key: string, path: JSONPath): boolean {
  const parsed = draftKeyPath(key)
  return path.every((segment, index) => parsed[index] === segment)
}

/**
 * 该草稿键是否已随结构删除失效：位于 arrayPath 之下、索引不小于被删条目。
 * 被删条目自身的键与随后移位的条目键都指向已不存在或已移位的条目，只有索引更小的条目保持原位。
 */
export function isStaleArrayDraftKey(
  key: string,
  arrayPath: JSONPath,
  removedIndex: number,
): boolean {
  const parsed = draftKeyPath(key)
  if (parsed.length <= arrayPath.length) {
    return false
  }
  for (let index = 0; index < arrayPath.length; index += 1) {
    if (parsed[index] !== arrayPath[index]) {
      return false
    }
  }
  const itemIndex = parsed[arrayPath.length]
  return typeof itemIndex === 'number' && itemIndex >= removedIndex
}

/** 丢弃随结构删除失效的草稿，其余键原样保留。 */
export function withoutStaleDrafts<T>(
  record: Record<string, T>,
  arrayPath: JSONPath,
  removedIndex: number,
): Record<string, T> {
  const next: Record<string, T> = {}
  for (const [key, value] of Object.entries(record)) {
    if (!isStaleArrayDraftKey(key, arrayPath, removedIndex)) {
      next[key] = value
    }
  }
  return next
}
