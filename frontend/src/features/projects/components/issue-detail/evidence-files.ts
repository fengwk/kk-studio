import { sharedWorkerHasher, type HashFile } from '@/shared/lib/upload-file'
import type { IssueEvidenceDTO } from '@/shared/api/generated/contracts'

/** 主线程 Web Crypto SHA-256；仅在没有 Worker 时作为明确能力分支。 */
async function digestFileSha256(file: File): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

/**
 * 计算真正上传字节的 SHA-256。注入 hasher 优先；
 * 否则复用共享 Worker。没有 Worker 时才使用 Web Crypto，两者都不可用则失败。
 * 实际计算失败不会改写成成功或假摘要。
 */
export async function calculateFileSha256(file: File, customHasher?: HashFile): Promise<string> {
  if (customHasher) {
    return customHasher(file)
  }
  if (typeof Worker !== 'undefined') {
    return sharedWorkerHasher()(file)
  }
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    return digestFileSha256(file)
  }
  throw new Error('SHA-256 is unavailable')
}

/** 本地刚上传的证据在前、服务端列表在后，按 blob 与名称去重。 */
export function mergeEvidences(local: IssueEvidenceDTO[], server: IssueEvidenceDTO[]): IssueEvidenceDTO[] {
  const seen = new Set<string>()
  const merged: IssueEvidenceDTO[] = []
  for (const ev of [...local, ...server]) {
    const key = `${ev.blobId}-${ev.name ?? ''}`
    if (!seen.has(key)) {
      seen.add(key)
      merged.push(ev)
    }
  }
  return merged
}
