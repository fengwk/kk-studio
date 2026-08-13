import { describe, expect, it } from 'vitest'
import { isNodeCompletelyOutsideGroup } from '@/features/canvas/group-membership'

const group = { x: 100, y: 100, width: 400, height: 300 }

describe('isNodeCompletelyOutsideGroup', () => {
  it('keeps membership while any positive node area overlaps the Group body', () => {
    expect(isNodeCompletelyOutsideGroup(
      { x: 450, y: 200, width: 100, height: 80 },
      group,
    )).toBe(false)
  })

  it('detaches only after the node has fully left the Group body', () => {
    expect(isNodeCompletelyOutsideGroup(
      { x: 520, y: 200, width: 100, height: 80 },
      group,
    )).toBe(true)
  })

  it('treats edge contact without positive overlap as fully outside', () => {
    expect(isNodeCompletelyOutsideGroup(
      { x: 500, y: 200, width: 100, height: 80 },
      group,
    )).toBe(true)
  })

  it('does not count the external Group header row as membership area', () => {
    expect(isNodeCompletelyOutsideGroup(
      { x: 140, y: 100, width: 100, height: 20 },
      group,
    )).toBe(true)
  })
})
