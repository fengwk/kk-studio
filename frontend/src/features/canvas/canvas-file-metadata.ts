import type { CanvasResourceKind } from '@/shared/api/contracts/studio'

/**
 * 上传完成前从本地文件探测媒体尺寸，用于 CREATE_RESOURCE_NODE 的
 * 自适应节点尺寸；探测失败（或 AUDIO/无尺寸信息）返回 null，
 * 调用方回退默认 transform。命令响应中的 CanvasResource 宽高才是权威值。
 */
export interface CanvasLocalFileMetadata {
  width: number | null
  height: number | null
}

export async function probeCanvasFileMetadata(
  file: File,
  kind: Exclude<CanvasResourceKind, 'TEXT'>,
): Promise<CanvasLocalFileMetadata> {
  if (kind === 'IMAGE') {
    return decodeImageSize(file)
  }
  if (kind === 'VIDEO') {
    return probeVideoSize(file)
  }
  return { width: null, height: null }
}

async function decodeImageSize(file: File): Promise<CanvasLocalFileMetadata> {
  if (typeof createImageBitmap !== 'function') {
    return { width: null, height: null }
  }
  try {
    const bitmap = await createImageBitmap(file)
    try {
      return { width: bitmap.width, height: bitmap.height }
    } finally {
      bitmap.close()
    }
  } catch {
    return { width: null, height: null }
  }
}

function probeVideoSize(file: File): Promise<CanvasLocalFileMetadata> {
  return new Promise((resolve) => {
    const video = document.createElement('video')
    const url = URL.createObjectURL(file)
    let settled = false
    const finish = (metadata: CanvasLocalFileMetadata) => {
      if (settled) {
        return
      }
      settled = true
      URL.revokeObjectURL(url)
      resolve(metadata)
    }
    const timer = window.setTimeout(() => finish({ width: null, height: null }), 3000)
    video.addEventListener(
      'loadedmetadata',
      () => {
        window.clearTimeout(timer)
        finish({
          width: positiveDimension(video.videoWidth),
          height: positiveDimension(video.videoHeight),
        })
      },
      { once: true },
    )
    video.addEventListener(
      'error',
      () => {
        window.clearTimeout(timer)
        finish({ width: null, height: null })
      },
      { once: true },
    )
    video.preload = 'metadata'
    video.src = url
  })
}

function positiveDimension(value: number): number | null {
  return Number.isFinite(value) && value > 0 ? value : null
}
