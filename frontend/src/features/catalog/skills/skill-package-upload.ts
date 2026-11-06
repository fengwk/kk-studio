import { ApiError } from '@/shared/api/client'
import { storageService } from '@/shared/api/storage-service'
import { FORBIDDEN_UPLOAD_HEADERS } from '@/shared/api/storage-service'
import type { StoragePresignedUrlDTO } from '@/shared/api/generated/contracts'
import { createWorkerHasher, type HashFile } from '@/shared/lib/upload-file'

const defaultHashFile: HashFile = createWorkerHasher()

export interface UploadProgress {
  loaded: number
  total: number
}

/** 归档的媒体类型按扩展名确定：某些浏览器给出的 File.type 为空或不稳定。 */
export function skillArchiveMediaType(filename: string): string {
  return filename.toLowerCase().endsWith('.zip') ? 'application/zip' : 'application/gzip'
}

/**
 * 直传归档到预签名 URL。进度必须来自 XHR 的真实上传事件，
 * 因此这里不复用基于 fetch 的通用直传；禁止发送的请求头与共享直传使用同一份清单。
 */
export function putFileWithProgress(
  presigned: StoragePresignedUrlDTO,
  file: File,
  onProgress: (progress: UploadProgress) => void,
  signal?: AbortSignal,
): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('The operation was aborted', 'AbortError'))
      return
    }
    const xhr = new XMLHttpRequest()
    xhr.open(presigned.method, presigned.url)
    for (const [name, value] of Object.entries(presigned.headers)) {
      if (!FORBIDDEN_UPLOAD_HEADERS.has(name.toLowerCase())) {
        xhr.setRequestHeader(name, value)
      }
    }
    const cleanup = () => {
      signal?.removeEventListener('abort', abort)
    }
    const abort = () => {
      xhr.abort()
    }
    const fail = (error: Error) => {
      cleanup()
      reject(error)
    }
    xhr.upload.onprogress = (event) => {
      onProgress({
        loaded: event.loaded,
        total: event.lengthComputable && event.total > 0 ? event.total : file.size,
      })
    }
    xhr.onload = () => {
      cleanup()
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve()
        return
      }
      reject(new ApiError(`Direct upload failed with HTTP ${xhr.status}`, xhr.status))
    }
    xhr.onerror = () => fail(new ApiError('Direct upload failed'))
    xhr.onabort = () => fail(new DOMException('The operation was aborted', 'AbortError'))
    signal?.addEventListener('abort', abort, { once: true })
    xhr.send(file)
  })
}

export interface UploadSkillArchiveOptions {
  file: File
  sha256: string
  onProgress: (progress: UploadProgress) => void
  signal?: AbortSignal
}

/** 预留上传、直传原始压缩包并完成，返回供预览消费的 uploadId。 */
export async function uploadSkillArchive({
  file,
  sha256,
  onProgress,
  signal,
}: UploadSkillArchiveOptions): Promise<string> {
  const reservation = await storageService.reserveUpload({
    filename: file.name,
    mediaType: skillArchiveMediaType(file.name),
    sizeBytes: String(file.size),
    sha256,
  })
  if (reservation.type === 'PENDING') {
    await putFileWithProgress(reservation.presignedPut, file, onProgress, signal)
  } else {
    // 摘要命中已有内容时服务端不会要求直传，进度直接视为完成。
    onProgress({ loaded: file.size, total: file.size })
  }
  const completed = await storageService.completeUpload(reservation.id)
  return completed.id
}

/** 摘要校验在 Worker 中执行，校验阶段只呈现不可量化的阶段状态。 */
export function hashSkillArchive(
  file: File,
  signal?: AbortSignal,
  hashFile: HashFile = defaultHashFile,
): Promise<string> {
  return hashFile(file, signal)
}
