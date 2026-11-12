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

/**
 * Decode with browser EXIF orientation applied, then retain in-box source bytes or resize once.
 * Browsers expose the first frame for animated formats; reject animations that require resizing.
 */
export async function scaleImageFile(file: File, tier: ImageInputTier): Promise<ScaledImageFile> {
  if (!SUPPORTED_IMAGE_TYPES.has(file.type)) {
    throw new Error(`unsupported image media type for resizing: ${file.type}`)
  }
  const sourceBytes = new Uint8Array(await file.arrayBuffer())
  const orientation = readJpegExifOrientation(file.type, sourceBytes)
  const encodedDimensions = readEncodedImageDimensions(file.type, sourceBytes)
  const sourceDimensions = orientation >= 5 && orientation <= 8
    ? { width: encodedDimensions.height, height: encodedDimensions.width }
    : encodedDimensions
  const target = computeScaledDimensions(sourceDimensions.width, sourceDimensions.height, tier)
  const animation = isAnimatedImage(file.type, sourceBytes)
  const bitmap = await createImageBitmap(file, { imageOrientation: 'none' })
  try {
    const bitmapIsEncodedOrientation = (
      bitmap.width === encodedDimensions.width && bitmap.height === encodedDimensions.height
    )
    const bitmapIsDisplayOrientation = (
      bitmap.width === sourceDimensions.width && bitmap.height === sourceDimensions.height
    )
    if (!bitmapIsEncodedOrientation && !bitmapIsDisplayOrientation) {
      throw new Error(`image content of ${file.type} does not match its declared dimensions`)
    }
    const bitmapOrientation = bitmapIsEncodedOrientation ? orientation : 1
    const originalWidth = sourceDimensions.width
    const originalHeight = sourceDimensions.height
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
    drawExifOrientedImage(context, bitmap, bitmapOrientation, target.width, originalWidth)
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

function drawExifOrientedImage(
  context: CanvasRenderingContext2D,
  bitmap: ImageBitmap,
  orientation: number,
  targetWidth: number,
  displayWidth: number,
): void {
  const scale = targetWidth / displayWidth
  const width = bitmap.width * scale
  const height = bitmap.height * scale
  const transforms: Record<number, [number, number, number, number, number, number]> = {
    1: [scale, 0, 0, scale, 0, 0],
    2: [-scale, 0, 0, scale, width, 0],
    3: [-scale, 0, 0, -scale, width, height],
    4: [scale, 0, 0, -scale, 0, height],
    5: [0, scale, scale, 0, 0, 0],
    6: [0, scale, -scale, 0, height, 0],
    7: [0, -scale, -scale, 0, height, width],
    8: [0, -scale, scale, 0, 0, width],
  }
  context.setTransform(...(transforms[orientation] ?? transforms[1]!))
  context.drawImage(bitmap, 0, 0)
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

function readEncodedImageDimensions(mediaType: string, bytes: Uint8Array): ImageDimensions {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  if (mediaType === 'image/png' && bytes.length >= 24) {
    return { width: view.getUint32(16, false), height: view.getUint32(20, false) }
  }
  if (mediaType === 'image/gif' && bytes.length >= 10) {
    return { width: view.getUint16(6, true), height: view.getUint16(8, true) }
  }
  if (mediaType === 'image/jpeg' && bytes[0] === 0xff && bytes[1] === 0xd8) {
    let offset = 2
    while (offset + 3 < bytes.length) {
      if (bytes[offset] !== 0xff) {
        break
      }
      while (bytes[offset] === 0xff) {
        offset++
      }
      const marker = bytes[offset++]!
      if (marker === 0xda || marker === 0xd9) {
        break
      }
      if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
        continue
      }
      if (offset + 2 > bytes.length) {
        break
      }
      const length = view.getUint16(offset, false)
      const data = offset + 2
      const end = offset + length
      if (length < 2 || end > bytes.length) {
        break
      }
      if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker)) {
        if (length < 7) {
          break
        }
        return { width: view.getUint16(data + 3, false), height: view.getUint16(data + 1, false) }
      }
      offset = end
    }
  }
  if (
    mediaType === 'image/webp'
    && bytes.length >= 30
    && String.fromCharCode(...bytes.subarray(0, 4)) === 'RIFF'
    && String.fromCharCode(...bytes.subarray(8, 12)) === 'WEBP'
  ) {
    for (let offset = 12; offset + 8 <= bytes.length;) {
      const chunk = String.fromCharCode(...bytes.subarray(offset, offset + 4))
      const size = view.getUint32(offset + 4, true)
      const payload = offset + 8
      if (payload + size > bytes.length) {
        break
      }
      if (chunk === 'VP8X' && size >= 10) {
        return {
          width: 1 + bytes[payload + 4]! + (bytes[payload + 5]! << 8) + (bytes[payload + 6]! << 16),
          height: 1 + bytes[payload + 7]! + (bytes[payload + 8]! << 8) + (bytes[payload + 9]! << 16),
        }
      }
      if (chunk === 'VP8L' && size >= 5 && bytes[payload] === 0x2f) {
        const b1 = bytes[payload + 1]!
        const b2 = bytes[payload + 2]!
        const b3 = bytes[payload + 3]!
        const b4 = bytes[payload + 4]!
        return {
          width: 1 + (b1 & 0x3f) + ((b2 & 0x3f) << 6),
          height: 1 + ((b2 & 0xc0) >> 6) + (b3 << 2) + ((b4 & 0x0f) << 10),
        }
      }
      if (chunk === 'VP8 ' && size >= 10 && bytes[payload + 3] === 0x9d && bytes[payload + 4] === 0x01 && bytes[payload + 5] === 0x2a) {
        return {
          width: view.getUint16(payload + 6, true) & 0x3fff,
          height: view.getUint16(payload + 8, true) & 0x3fff,
        }
      }
      offset = payload + size + (size & 1)
    }
  }
  throw new Error(`image content declared as ${mediaType} cannot be decoded`)
}

function readJpegExifOrientation(mediaType: string, bytes: Uint8Array): number {
  if (mediaType !== 'image/jpeg' || bytes[0] !== 0xff || bytes[1] !== 0xd8) {
    return 1
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let offset = 2
  while (offset < bytes.length) {
    if (bytes[offset] !== 0xff) {
      return exifError('invalid JPEG marker')
    }
    while (bytes[offset] === 0xff) {
      offset++
    }
    const marker = bytes[offset++]
    if (marker === 0xda || marker === 0xd9) {
      return 1
    }
    if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      continue
    }
    if (offset + 2 > bytes.length) {
      return exifError('truncated JPEG segment')
    }
    const length = view.getUint16(offset, false)
    const segmentStart = offset + 2
    const segmentEnd = offset + length
    if (length < 2 || segmentEnd > bytes.length) {
      return exifError('invalid JPEG segment length')
    }
    if (
      marker === 0xe1
      && length >= 8
      && bytes[segmentStart] === 0x45
      && bytes[segmentStart + 1] === 0x78
      && bytes[segmentStart + 2] === 0x69
      && bytes[segmentStart + 3] === 0x66
      && bytes[segmentStart + 4] === 0
      && bytes[segmentStart + 5] === 0
    ) {
      return parseTiffOrientation(view, segmentStart + 6, segmentEnd)
    }
    offset = segmentEnd
  }
  return 1
}

function parseTiffOrientation(view: DataView, start: number, end: number): number {
  const fail = (reason: string): never => {
    throw new Error(`EXIF orientation cannot be read: ${reason}`)
  }
  if (start + 8 > end) {
    return fail('truncated TIFF header')
  }
  const byteOrder = view.getUint16(start, false)
  const littleEndian = byteOrder === 0x4949
  if (!littleEndian && byteOrder !== 0x4d4d) {
    return fail('invalid TIFF byte order')
  }
  const uint16 = (offset: number) => {
    if (offset + 2 > end) {
      return fail('truncated TIFF value')
    }
    return view.getUint16(offset, littleEndian)
  }
  const uint32 = (offset: number) => {
    if (offset + 4 > end) {
      return fail('truncated TIFF value')
    }
    return view.getUint32(offset, littleEndian)
  }
  if (uint16(start + 2) !== 42) {
    return fail('invalid TIFF magic')
  }
  const ifd = start + uint32(start + 4)
  const count = uint16(ifd)
  if (ifd + 2 + count * 12 > end) {
    return fail('truncated TIFF directory')
  }
  for (let i = 0; i < count; i += 1) {
    const entry = ifd + 2 + i * 12
    if (uint16(entry) !== 0x0112) {
      continue
    }
    if (uint16(entry + 2) !== 3 || uint32(entry + 4) !== 1) {
      return fail('orientation must be a single SHORT')
    }
    const orientation = uint16(entry + 8)
    if (orientation < 1 || orientation > 8) {
      return fail('orientation is outside 1..8')
    }
    return orientation
  }
  return 1
}

function exifError(reason: string): never {
  throw new Error(`EXIF orientation cannot be read: ${reason}`)
}

function isAnimatedImage(mediaType: string, bytes: Uint8Array): boolean {
  if (mediaType === 'image/gif') {
    let frames = 0
    let i = 13
    const logicalScreenPacked = bytes[10] ?? 0
    if ((logicalScreenPacked & 0x80) !== 0) {
      i += 3 * (1 << ((logicalScreenPacked & 0x07) + 1))
    }
    for (; i < bytes.length;) {
      const marker = bytes[i++]
      if (marker === 0x2c) {
        frames++
        if (frames > 1) {
          return true
        }
        if (i + 9 > bytes.length) {
          break
        }
        const packed = bytes[i + 8]!
        i += 9
        if ((packed & 0x80) !== 0) {
          i += 3 * (1 << ((packed & 0x07) + 1))
        }
        if (i >= bytes.length) {
          break
        }
        i++
        while (i < bytes.length) {
          const size = bytes[i++]!
          if (size === 0) {
            break
          }
          i += size
        }
      } else if (marker === 0x21) {
        if (i >= bytes.length) {
          break
        }
        i++
        while (i < bytes.length) {
          const size = bytes[i++]!
          if (size === 0) {
            break
          }
          i += size
        }
      } else {
        break
      }
    }
    return false
  }
  if (mediaType === 'image/webp' && bytes.length >= 12) {
    for (let i = 12; i + 8 <= bytes.length;) {
      const type = String.fromCharCode(bytes[i]!, bytes[i + 1]!, bytes[i + 2]!, bytes[i + 3]!)
      const size = bytes[i + 4]! | (bytes[i + 5]! << 8) | (bytes[i + 6]! << 16) | (bytes[i + 7]! << 24)
      if (type === 'ANIM' || type === 'ANMF') {
        return true
      }
      i += 8 + size + (size & 1)
    }
  }
  return false
}
