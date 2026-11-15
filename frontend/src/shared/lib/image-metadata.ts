import { computeScaledDimensions } from '@/shared/lib/image-scaler'

/** 原文件字节对应的图片事实：四个尺寸相同，因为这里不缩放。 */
export interface OriginalImageMetadata {
  format: string
  originalWidth: number
  originalHeight: number
  storedWidth: number
  storedHeight: number
}

/**
 * 按文件媒体类型填入 format，并用浏览器解码读取原始图片的像素尺寸。
 * 解码失败或超出可解码像素上限时抛错，调用方不得继续 reserve。
 */
export async function readOriginalImageMetadata(file: File): Promise<OriginalImageMetadata> {
  const format = imageFormatOf(file.type)
  if (!format) {
    throw new Error(`unsupported image media type: ${file.type || 'unknown'}`)
  }
  const bitmap = await createImageBitmap(file)
  try {
    const { width, height } = computeScaledDimensions(bitmap.width, bitmap.height, 'ORIGINAL')
    return {
      format,
      originalWidth: width,
      originalHeight: height,
      storedWidth: width,
      storedHeight: height,
    }
  } finally {
    bitmap.close()
  }
}

/** file.type 去掉 image/ 前缀；非图片或缺少子类型时返回空串。 */
function imageFormatOf(mediaType: string): string {
  if (!mediaType.startsWith('image/')) {
    return ''
  }
  return mediaType.slice('image/'.length)
}
