import { describe, expect, it } from 'vitest'
import {
  canvasViewportStorageKey,
  DEFAULT_CANVAS_VIEWPORT,
  loadCanvasViewport,
  saveCanvasViewport,
} from '@/features/canvas/viewport-storage'

describe('canvas viewport storage', () => {
  it('isolates viewport by canvas id and clamps zoom', () => {
    const values = new Map<string, string>()
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    }

    saveCanvasViewport('1', { x: 12, y: -9, zoom: 99 }, storage)
    saveCanvasViewport('2', { x: 1, y: 2, zoom: 0.5 }, storage)

    expect(loadCanvasViewport('1', storage)).toEqual({ x: 12, y: -9, zoom: 1.45 })
    expect(loadCanvasViewport('2', storage)).toEqual({ x: 1, y: 2, zoom: 0.5 })
    expect(values.has(canvasViewportStorageKey('1'))).toBe(true)
  })

  it('falls back for malformed or non-finite persisted values', () => {
    const storage = {
      getItem: () => '{"x":0,"y":0,"zoom":"NaN"}',
    }
    expect(loadCanvasViewport('1', storage)).toEqual(DEFAULT_CANVAS_VIEWPORT)
    expect(loadCanvasViewport('1', { getItem: () => '{' })).toEqual(DEFAULT_CANVAS_VIEWPORT)
  })

  it('ignores non-finite values instead of corrupting storage', () => {
    let writeCount = 0
    saveCanvasViewport('1', { x: Number.NaN, y: 0, zoom: 1 }, {
      setItem: () => {
        writeCount += 1
      },
    })
    expect(writeCount).toBe(0)
  })
})
