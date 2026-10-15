import { describe, expect, it } from 'vitest'
import type { JSONPath } from 'jsonc-parser'
import {
  draftKeyPath,
  isDraftKeyUnderPath,
  isStaleArrayDraftKey,
  withoutStaleDrafts,
} from './plugin-draft-keys'

const key = (path: JSONPath) => JSON.stringify(path)

describe('plugin draft keys', () => {
  it('treats the canonical stringified path as the only key representation', () => {
    expect(draftKeyPath(key(['entries', 0, 'slot', 'index']))).toEqual([
      'entries',
      0,
      'slot',
      'index',
    ])
  })

  it('matches subtree membership by path segments, never by string prefix', () => {
    // 相似但不同的字段名不能被当成子树：promptX 不属于 prompt。
    expect(isDraftKeyUnderPath(key(['promptX']), ['prompt'])).toBe(false)
    expect(isDraftKeyUnderPath(key(['prompt']), ['prompt'])).toBe(true)
    expect(isDraftKeyUnderPath(key(['prompt', 'nested']), ['prompt'])).toBe(true)
    expect(isDraftKeyUnderPath(key(['entries', 0, 'slot']), ['entries'])).toBe(true)
    expect(isDraftKeyUnderPath(key(['mediaBindings', 0]), ['entries'])).toBe(false)
    // 空路径表示整棵表单。
    expect(isDraftKeyUnderPath(key(['anything']), [])).toBe(true)
    // 数字段必须严格相等，'0' 与 0 不是同一段。
    expect(isDraftKeyUnderPath(key(['entries', 0]), ['entries', '0'])).toBe(false)
  })

  it('marks the removed item and every shifted item as stale', () => {
    expect(isStaleArrayDraftKey(key(['entries', 0, 'slot', 'index']), ['entries'], 0)).toBe(true)
    expect(isStaleArrayDraftKey(key(['entries', 2, 'slot', 'index']), ['entries'], 0)).toBe(true)
    expect(isStaleArrayDraftKey(key(['entries', 1]), ['entries'], 1)).toBe(true)
    // 索引更小的条目没有移动，草稿保持有效。
    expect(isStaleArrayDraftKey(key(['entries', 0, 'slot', 'index']), ['entries'], 1)).toBe(false)
    // 数组自身的键不是条目键。
    expect(isStaleArrayDraftKey(key(['entries']), ['entries'], 0)).toBe(false)
    // 其它数组/字段不受影响。
    expect(isStaleArrayDraftKey(key(['outputs', 2, 'index']), ['entries'], 0)).toBe(false)
    expect(isStaleArrayDraftKey(key(['name']), ['entries'], 0)).toBe(false)
  })

  it('drops exactly the stale drafts and leaves the input untouched', () => {
    const drafts = {
      [key(['entries', 0, 'slot', 'index'])]: 'kept',
      [key(['entries', 1, 'slot', 'index'])]: 'moved',
      [key(['entries', 1])]: 'removed-item-snippet',
      [key(['name'])]: 'unrelated',
    }
    const next = withoutStaleDrafts(drafts, ['entries'], 1)
    expect(next).toEqual({
      [key(['entries', 0, 'slot', 'index'])]: 'kept',
      [key(['name'])]: 'unrelated',
    })
    expect(Object.keys(drafts)).toHaveLength(4)
  })
})
