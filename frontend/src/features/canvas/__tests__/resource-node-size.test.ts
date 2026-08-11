import { describe, expect, it } from 'vitest'
import { resourceNodeSize } from '@/features/canvas/resource-node-size'

const fallback = { width: 320, height: 260 }

describe('resourceNodeSize', () => {
  it('uses the source ratio for portrait media nodes', () => {
    // 1122x1402 is the production regression image supplied for this slice.
    expect(resourceNodeSize({
      transform: fallback,
      resources: [{ kind: 'IMAGE', metadata: { width: 1122, height: 1402 } }],
    })).toEqual({ width: 256, height: 344 })
  })

  it('uses the source ratio for landscape media nodes', () => {
    expect(resourceNodeSize({
      transform: fallback,
      resources: [{ kind: 'VIDEO', metadata: { width: 1920, height: 1080 } }],
    })).toEqual({ width: 320, height: 204 })
  })

  it('reserves the resource switcher height for multi-output nodes', () => {
    expect(resourceNodeSize({
      transform: fallback,
      resources: [
        { kind: 'IMAGE', metadata: { width: 1024, height: 1024 } },
        { kind: 'IMAGE', metadata: { width: 512, height: 512 } },
      ],
    })).toEqual({ width: 320, height: 386 })
  })

  it('keeps the persisted fallback when media dimensions are unavailable', () => {
    expect(resourceNodeSize({
      transform: fallback,
      resources: [{ kind: 'IMAGE', metadata: {} }],
    })).toEqual(fallback)
    expect(resourceNodeSize({
      transform: fallback,
      resources: [{ kind: 'TEXT', metadata: { width: 100, height: 100 } }],
    })).toEqual(fallback)
  })
})
