/**
 * 专用哈希 Worker：在独立线程中用 Web Crypto 计算文件 SHA-256，
 * 避免大文件（视频最大 100 MiB）的散列阻塞主线程。
 */
const workerScope = self as unknown as Worker & {
  onmessage: ((event: MessageEvent<HashRequest>) => void) | null
}

interface HashRequest {
  requestId: string
  file: File
}

interface HashResponse {
  requestId: string
  sha256?: string
  error?: string
}

workerScope.onmessage = async (event: MessageEvent<HashRequest>) => {
  const { requestId, file } = event.data
  try {
    const buffer = await file.arrayBuffer()
    const digest = await crypto.subtle.digest('SHA-256', buffer)
    const sha256 = Array.from(new Uint8Array(digest))
      .map((byte) => byte.toString(16).padStart(2, '0'))
      .join('')
    workerScope.postMessage({ requestId, sha256 } satisfies HashResponse)
  } catch (error) {
    workerScope.postMessage({
      requestId,
      error: error instanceof Error ? error.message : String(error),
    } satisfies HashResponse)
  }
}

export {}
