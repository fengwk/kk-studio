import { describe, expect, it, vi } from 'vitest'

import {
  ResourceUploadError,
  type ResourceUploader,
} from '../src/contracts.js'
import {
  boundaryUpload,
  describeUploadFailure,
} from '../src/runtime-upload-boundary.js'

const content = {
  type: 'binary' as const,
  bytes: Uint8Array.of(1),
  mediaType: 'application/octet-stream',
}

function uploaderWhich(behavior: () => Promise<never>): ResourceUploader {
  return { upload: vi.fn(behavior) }
}

describe('resource upload boundary contract', () => {
  it('keeps a contract error with cleanupConfirmed=true as-is', async () => {
    const failure = new ResourceUploadError('put', 'storage-rejected', true)
    const uploader = uploaderWhich(() => Promise.reject(failure))
    const error = await boundaryUpload(uploader, 'inv-1', content, new AbortController().signal).then(
      () => undefined,
      (reason: unknown) => reason,
    )
    expect(error).toBe(failure)
  })

  it('keeps a contract error without cleanup confirmation as-is', async () => {
    // uploader 已声明未确认释放：边界原样交付，调用方据此不 ACK 退出。
    const failure = new ResourceUploadError('put', 'cleanup-failed', false)
    const uploader = uploaderWhich(() => Promise.reject(failure))
    const error = await boundaryUpload(uploader, 'inv-1', content, new AbortController().signal).then(
      () => undefined,
      (reason: unknown) => reason,
    )
    expect(error).toBe(failure)
  })

  it('treats an off-contract rejection as a conservative boundary violation with exit unknown', async () => {
    // 未按契约实现的 uploader：不能自动认定释放成功；错误类型与诊断都必须保留。
    const stderr = vi.spyOn(process.stderr, 'write').mockImplementation(() => true)
    try {
      const uploader = uploaderWhich(() => Promise.reject(new Error('mysterious failure')))
      const error = await boundaryUpload(uploader, 'inv-2', content, new AbortController().signal).then(
        () => undefined,
        (reason: unknown) => reason,
      )
      expect(error).toBeInstanceOf(ResourceUploadError)
      const typed = error as ResourceUploadError
      expect(typed.stage).toBe('internal')
      expect(typed.category).toBe('boundary-violation')
      expect(typed.cleanupConfirmed).toBe(false)
      // 边界违约诊断只含 invocationId 与错误类型，不回显 message 正文。
      const diagnostics = stderr.mock.calls.map((call) => String(call[0])).join('')
      expect(diagnostics).toContain('inv-2')
      expect(diagnostics).toContain('Error')
      expect(diagnostics).not.toContain('mysterious failure')
    } finally {
      stderr.mockRestore()
    }
  })

  it('resolves when the underlying uploader succeeds', async () => {
    const uploaded = {
      type: 'resource' as const,
      uploadId: '88888888-8888-4888-8888-888888888888',
      mediaType: 'application/octet-stream',
      name: null,
      size: 1,
      sha256: 'a'.repeat(64),
    }
    const uploader: ResourceUploader = { upload: vi.fn(async () => uploaded) }
    await expect(
      boundaryUpload(uploader, 'inv-3', content, new AbortController().signal),
    ).resolves.toBe(uploaded)
  })

  it('describes failures with stage/category/cleanup facts only', () => {
    const failure = new ResourceUploadError('put', 'storage-rejected', true, {
      cause: new Error('storage rejected with https://signed-url-secret'),
    })
    const line = describeUploadFailure(failure)
    expect(line).toBe('stage=put category=storage-rejected cleanupConfirmed=true')
    // 安全诊断绝不回显 cause 文案（可能携带预签名 URL）。
    expect(line).not.toContain('signed-url-secret')
  })
})
