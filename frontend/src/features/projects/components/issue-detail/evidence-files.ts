import { createWorkerHasher, type HashFile } from '@/features/ai/composer'
import type { IssueEvidenceDTO } from '../../types'

/** 计算上传文件的 SHA-256：优先注入的 hasher，其次 Worker，再退回 WebCrypto。 */
export async function calculateFileSha256(file: File, customHasher?: HashFile): Promise<string> {
  if (customHasher) {
    return customHasher(file)
  }
  if (typeof Worker !== 'undefined') {
    try {
      return await createWorkerHasher()(file)
    } catch {
      // fallback
    }
  }
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    const buf = await file.arrayBuffer()
    const digest = await crypto.subtle.digest('SHA-256', buf)
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('')
  }
  return '0'.repeat(64)
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
