import type { ImageInputTier } from '@/features/thread/composer/composer-parts'

export interface ImageDimensions {
  width: number
  height: number
}

export interface ScaledImageFile {
  file: File
  format: string
  originalWidth: number
  originalHeight: number
  storedWidth: number
  storedHeight: number
}

const MAX_DECODED_PIXELS = 40_000_000
const SUPPORTED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/gif', 'image/webp'])

/** Matches the backend's orientation-aware tier box and never enlarges an image. */
export function computeScaledDimensions(
  width: number,
  height: number,
  tier: ImageInputTier,
): ImageDimensions {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width <= 0 || height <= 0) {
    throw new Error('Image dimensions must be positive integers')
  }
  if (width * height > MAX_DECODED_PIXELS) {
    throw new Error(`image of ${width}x${height} pixels exceeds the decodable pixel budget ${MAX_DECODED_PIXELS}`)
  }
  if (tier === 'ORIGINAL') {
    return { width, height }
  }
  const landscape = tier === '720P' ? [1280, 720] : [1920, 1080]
  const box = width > height
    ? landscape
    : height > width
      ? [landscape[1], landscape[0]]
      : [landscape[1], landscape[1]]
  if (width <= box[0]! && height <= box[1]!) {
    return { width, height }
  }
  const factor = Math.min(box[0]! / width, box[1]! / height)
  return {
    width: Math.min(box[0]!, Math.max(1, Math.round(width * factor))),
    height: Math.min(box[1]!, Math.max(1, Math.round(height * factor))),
  }
}

/** Uses browser decoding (including EXIF orientation) and preserves source bytes when in bounds. */
export async function scaleImageFile(file: File, tier: ImageInputTier): Promise<ScaledImageFile> {
  if (!SUPPORTED_IMAGE_TYPES.has(file.type)) {
    throw new Error(`unsupported image media type for resizing: ${file.type}`)
  }
  const animation = file.type === 'image/gif' || file.type === 'image/webp'
    ? isAnimatedImage(file.type, new Uint8Array(await file.arrayBuffer()))
    : false
  const bitmap = await createImageBitmap(file)
  try {
    const originalWidth = bitmap.width
    const originalHeight = bitmap.height
    const target = computeScaledDimensions(originalWidth, originalHeight, tier)
    const resized = target.width !== originalWidth || target.height !== originalHeight
    if (resized && animation) {
      throw new Error(`multi-frame image must not be resized to ${tier}`)
    }
    if (!resized) {
      return {
        file,
        format: mimeSubtype(file.type),
        originalWidth,
        originalHeight,
        storedWidth: originalWidth,
        storedHeight: originalHeight,
      }
    }

    const canvas = document.createElement('canvas')
    canvas.width = target.width
    canvas.height = target.height
    const context = canvas.getContext('2d', { willReadFrequently: true })
    if (!context) {
      throw new Error('Unable to create image processing context')
    }
    context.imageSmoothingEnabled = true
    context.imageSmoothingQuality = 'high'
    context.drawImage(bitmap, 0, 0, target.width, target.height)
    const pixels = context.getImageData(0, 0, target.width, target.height).data
    let hasAlpha = false
    for (let i = 3; i < pixels.length; i += 4) {
      if (pixels[i] !== 255) {
        hasAlpha = true
        break
      }
    }
    const outputType = hasAlpha || file.type !== 'image/jpeg' ? 'image/png' : 'image/jpeg'
    const blob = await canvasToBlob(canvas, outputType, outputType === 'image/jpeg' ? 0.92 : undefined)
    if (blob.size === 0) {
      throw new Error('Unable to encode resized image')
    }
    return {
      file: new File([blob], file.name, { type: outputType, lastModified: file.lastModified }),
      format: mimeSubtype(outputType),
      originalWidth,
      originalHeight,
      storedWidth: target.width,
      storedHeight: target.height,
    }
  } finally {
    bitmap.close()
  }
}

function mimeSubtype(mediaType: string): string {
  const slash = mediaType.indexOf('/')
  return slash < 0 ? '' : mediaType.slice(slash + 1)
}

function canvasToBlob(canvas: HTMLCanvasElement, type: string, quality?: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (blob) {
        resolve(blob)
      } else {
        reject(new Error('Unable to encode resized image'))
      }
    }, type, quality)
  })
}

/** Detect only the animation markers needed to avoid throwing away frames on a resize. */
function isAnimatedImage(mediaType: string, bytes: Uint8Array): boolean {
  if (mediaType === 'image/webp') {
    for (let i = 12; i + 8 <= bytes.length;) {
      const type = String.fromCharCode(bytes[i]!, bytes[i + 1]!, bytes[i + 2]!, bytes[i + 3]!)
      const size = bytes[i + 4]! | (bytes[i + 5]! << 8) | (bytes[i + 6]! << 16) | (bytes[i + 7]! << 24)
      if (type === 'ANIM' || type === 'ANMF') {
        return true
      }
      i += 8 + size + (size & 1)
    }
    return false
  }

  let frames = 0
  let i = 13
  const logicalScreenPacked = bytes[10] ?? 0
  if ((logicalScreenPacked & 0x80) !== 0) {
    i += 3 * (1 << ((logicalScreenPacked & 0x07) + 1))
  }
  while (i < bytes.length) {
    const marker = bytes[i++]
    if (marker === 0x2c) {
      frames += 1
      if (frames > 1) {
        return true
      }
      if (i + 9 > bytes.length) {
        return false
      }
      const packed = bytes[i + 8]!
      i += 9
      if ((packed & 0x80) !== 0) {
        i += 3 * (1 << ((packed & 0x07) + 1))
      }
      if (i >= bytes.length) {
        return false
      }
      i += 1
      while (i < bytes.length) {
        const size = bytes[i++]!
        if (size === 0) {
          break
        }
        i += size
      }
    } else if (marker === 0x21) {
      if (i >= bytes.length) {
        return false
      }
      i += 1
      while (i < bytes.length) {
        const size = bytes[i++]!
        if (size === 0) {
          break
        }
        i += size
      }
    } else {
      return false
    }
  }
  return false
}
