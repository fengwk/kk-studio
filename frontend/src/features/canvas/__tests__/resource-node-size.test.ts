import { describe, expect, it } from 'vitest'
import {
  CANVAS_MULTI_RESOURCE_TILE_HEIGHT,
  CANVAS_MULTI_RESOURCE_TILE_WIDTH,
  CANVAS_NODE_HEADER_GAP,
  CANVAS_NODE_HEADER_HEIGHT,
  CANVAS_RESOURCE_GRID_GAP,
  resourceNodeSize,
} from '@/features/canvas/resource-node-size'

const fallback = { width: 320, height: 260 }

describe('resourceNodeSize', () => {
  it('lets a single media renderer declare its source-ratio size', () => {
    expect(resourceNodeSize({
      transform: fallback,
      resources: [{ kind: 'IMAGE', width: 1122, height: 1402 }],
    })).toEqual({ width: 256, height: 346 })
    expect(resourceNodeSize({
      transform: fallback,
      resources: [{ kind: 'VIDEO', width: 1920, height: 1080 }],
    })).toEqual({ width: 320, height: 206 })
  })

  it('lets audio use a compact renderer footprint while text keeps a reading surface', () => {
    expect(resourceNodeSize({
      transform: fallback,
      resources: [{ kind: 'AUDIO', width: null, height: null }],
    })).toEqual({ width: 320, height: 138 })
    expect(resourceNodeSize({
      transform: fallback,
      resources: [{ kind: 'TEXT', width: null, height: null }],
    })).toEqual({ width: 320, height: 246 })
  })

  it('uses the default workspace footprint when media dimensions or resources are unavailable', () => {
    expect(resourceNodeSize({
      transform: fallback,
      resources: [{ kind: 'IMAGE', width: null, height: null }],
    })).toEqual({ width: 320, height: 246 })
    expect(resourceNodeSize({
      transform: fallback,
      resources: [],
    })).toEqual({ width: 320, height: 246 })
  })

  it('grows one common container around the score-selected fixed multi-resource grid', () => {
    expect(resourceNodeSize({
      transform: fallback,
      resources: Array.from({ length: 5 }, () => ({ kind: 'IMAGE' })),
    })).toEqual({
      width: CANVAS_MULTI_RESOURCE_TILE_WIDTH * 3 + CANVAS_RESOURCE_GRID_GAP * 2,
      height: (
        CANVAS_NODE_HEADER_HEIGHT
        + CANVAS_NODE_HEADER_GAP
        + CANVAS_MULTI_RESOURCE_TILE_HEIGHT * 2
        + CANVAS_RESOURCE_GRID_GAP
      ),
    })
    expect(resourceNodeSize({
      transform: fallback,
      resources: Array.from({ length: 10 }, () => ({ kind: 'VIDEO' })),
    })).toEqual({
      width: CANVAS_MULTI_RESOURCE_TILE_WIDTH * 4 + CANVAS_RESOURCE_GRID_GAP * 3,
      height: (
        CANVAS_NODE_HEADER_HEIGHT
        + CANVAS_NODE_HEADER_GAP
        + CANVAS_MULTI_RESOURCE_TILE_HEIGHT * 3
        + CANVAS_RESOURCE_GRID_GAP * 2
      ),
    })
  })
})
