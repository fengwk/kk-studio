import { describe, expect, it } from 'vitest'
import { resourceGridLayout } from '@/features/canvas/resource-grid-layout'

describe('resourceGridLayout', () => {
  it.each([
    [1, { rows: 1, cols: 1 }],
    [2, { rows: 1, cols: 2 }],
    [3, { rows: 2, cols: 2 }],
    [4, { rows: 2, cols: 2 }],
    [5, { rows: 2, cols: 3 }],
    [7, { rows: 3, cols: 3 }],
    [8, { rows: 3, cols: 3 }],
    [10, { rows: 3, cols: 4 }],
  ])('chooses the minimum-score grid for %i ordered resources', (count, expected) => {
    expect(resourceGridLayout(count)).toEqual(expected)
  })

  it('rejects non-positive and fractional resource counts', () => {
    expect(() => resourceGridLayout(0)).toThrow(RangeError)
    expect(() => resourceGridLayout(1.5)).toThrow(RangeError)
  })
})
