import { describe, expect, it } from 'vitest'
import type { JSONPath } from 'jsonc-parser'
import {
  isDraftKeyUnderPath,
  withoutStaleDrafts,
} from './plugin-draft-keys'

const key = (path: JSONPath) => JSON.stringify(path)

describe('plugin draft keys', () => {
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

  it('drops exactly the stale drafts and leaves the input untouched', () => {
    const drafts = {
      // 被删条目自身与随后移位的条目都指向已不存在的条目。
      [key(['entries', 1, 'slot', 'index'])]: 'removed-item',
      [key(['entries', 2, 'slot', 'index'])]: 'shifted-item',
      [key(['entries', 1])]: 'removed-item-snippet',
      // 索引更小的条目没有移动，草稿保持有效。
      [key(['entries', 0, 'slot', 'index'])]: 'kept',
      // 数组自身的键与其它数组/字段不受影响。
      [key(['entries'])]: 'array-itself',
      [key(['outputs', 2, 'index'])]: 'other-array',
      [key(['name'])]: 'unrelated',
    }
    const next = withoutStaleDrafts(drafts, ['entries'], 1)
    expect(next).toEqual({
      [key(['entries', 0, 'slot', 'index'])]: 'kept',
      [key(['entries'])]: 'array-itself',
      [key(['outputs', 2, 'index'])]: 'other-array',
      [key(['name'])]: 'unrelated',
    })
    expect(Object.keys(drafts)).toHaveLength(7)
  })
})
