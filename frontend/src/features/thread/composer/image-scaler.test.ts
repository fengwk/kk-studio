import { describe, expect, it } from 'vitest'
import { computeScaledDimensions } from './image-scaler'

describe('computeScaledDimensions', () => {
  it('fits landscape, portrait, and square images within the selected tier', () => {
    expect(computeScaledDimensions(2000, 1000, '720P')).toEqual({ width: 1280, height: 640 })
    expect(computeScaledDimensions(1000, 2000, '720P')).toEqual({ width: 640, height: 1280 })
    expect(computeScaledDimensions(2000, 2000, '720P')).toEqual({ width: 720, height: 720 })
    expect(computeScaledDimensions(4000, 2000, '1080P')).toEqual({ width: 1920, height: 960 })
  })

  it('keeps in-box images and ORIGINAL unchanged and never upscales', () => {
    expect(computeScaledDimensions(1280, 720, '720P')).toEqual({ width: 1280, height: 720 })
    expect(computeScaledDimensions(300, 200, '1080P')).toEqual({ width: 300, height: 200 })
    expect(computeScaledDimensions(4000, 3000, 'ORIGINAL')).toEqual({ width: 4000, height: 3000 })
  })

  it('enforces the backend decoded pixel budget', () => {
    expect(() => computeScaledDimensions(8000, 5001, 'ORIGINAL')).toThrow(
      'image of 8000x5001 pixels exceeds the decodable pixel budget 40000000',
    )
  })
})
